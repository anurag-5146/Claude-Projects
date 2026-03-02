"""
Personal AI Assistant — FastAPI + WebSocket server.
Deploy to Railway; run locally with: uvicorn server:app --reload --port 8000
"""
import asyncio
import json
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from jose import JWTError, jwt
from pydantic import BaseModel

load_dotenv(override=True)

from tools.github_sync import is_configured as _github_configured
from tools import github_sync

# ── Config ─────────────────────────────────────────────────────────────────────
APP_PASSWORD = os.environ.get("APP_PASSWORD", "changeme")
JWT_SECRET   = os.environ.get("JWT_SECRET", "change-this-to-a-random-32-char-string")
ALGORITHM    = "HS256"
BASE_DIR     = Path(__file__).parent
CONTEXT_DIR  = BASE_DIR / "context"
MEMORY_DIR   = BASE_DIR / "memory"
HISTORY_FILE = MEMORY_DIR / "history.json"

MODE_CONTEXT_FILES = {
    "video":   ["system_base.md", "video_pipeline.md"],
    "dev":     ["system_base.md", "app_development.md"],
    "general": ["system_base.md", "general.md"],
}

# Global FFmpeg priority (changed by set_priority message)
_ffmpeg_priority = "low"

# ── Model health tracker ────────────────────────────────────────────────────────
# Tracks models that recently failed — routes around them for HEALTH_COOLDOWN seconds.
# Reset automatically; no manual intervention needed.
import time as _time

HEALTH_COOLDOWN = 300  # 5 minutes
_model_failures: dict[str, float] = {}  # model_key → timestamp of last failure

FALLBACK_CHAIN = ["claude", "groq", "gemini", "openrouter", "ollama"]  # ollama last — requires local install


def mark_model_failed(model: str):
    _model_failures[model] = _time.monotonic()


def is_model_healthy(model: str) -> bool:
    t = _model_failures.get(model)
    if t is None:
        return True
    if _time.monotonic() - t > HEALTH_COOLDOWN:
        del _model_failures[model]
        return True
    return False


def get_healthy_available(available: set[str]) -> set[str]:
    return {m for m in available if is_model_healthy(m)}

# ── Auth ───────────────────────────────────────────────────────────────────────

def create_token() -> str:
    exp = datetime.now(timezone.utc) + timedelta(days=30)
    return jwt.encode({"sub": "user", "exp": exp}, JWT_SECRET, algorithm=ALGORITHM)


def verify_token(token: str) -> bool:
    try:
        jwt.decode(token, JWT_SECRET, algorithms=[ALGORITHM])
        return True
    except JWTError:
        return False


# ── Context loading ────────────────────────────────────────────────────────────

def load_context(mode: str) -> str:
    files = MODE_CONTEXT_FILES.get(mode, MODE_CONTEXT_FILES["general"])
    parts = []
    for fname in files:
        p = CONTEXT_DIR / fname
        if p.exists():
            parts.append(p.read_text(encoding="utf-8"))
    return "\n\n---\n\n".join(parts) if parts else "You are a helpful assistant."


# ── History persistence ────────────────────────────────────────────────────────

def _load_history() -> dict:
    if HISTORY_FILE.exists():
        try:
            return json.loads(HISTORY_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"sessions": {}}


def _save_history(data: dict):
    MEMORY_DIR.mkdir(exist_ok=True)
    serialized = json.dumps(data, ensure_ascii=False, indent=2)
    tmp = HISTORY_FILE.with_suffix(".tmp")
    tmp.write_text(serialized, encoding="utf-8")
    tmp.replace(HISTORY_FILE)  # atomic rename — no half-written files
    # Push to GitHub in background if configured
    if _github_configured():
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                asyncio.ensure_future(github_sync.push_history(serialized))
        except RuntimeError:
            pass


_history_store: dict = _load_history()


# ── Model availability ─────────────────────────────────────────────────────────

def get_available_models() -> set[str]:
    from agents import claude_agent, gemini_agent, groq_agent, ollama_agent, openrouter_agent
    available: set[str] = set()
    if claude_agent.is_available():     available.add("claude")
    if groq_agent.is_available():       available.add("groq")
    if gemini_agent.is_available():     available.add("gemini")
    if openrouter_agent.is_available(): available.add("openrouter")
    if ollama_agent.is_available():     available.add("ollama")
    return available


def get_agent(model: str):
    from agents import claude_agent, gemini_agent, groq_agent, ollama_agent, openrouter_agent
    return {
        "claude":     claude_agent,
        "groq":       groq_agent,
        "gemini":     gemini_agent,
        "openrouter": openrouter_agent,
        "ollama":     ollama_agent,
    }[model]


# ── App ────────────────────────────────────────────────────────────────────────

app = FastAPI(title="Personal Assistant")


@app.on_event("startup")
async def _startup():
    """Probe Ollama on startup so is_available() works without a live request."""
    base = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            r = await client.get(f"{base}/api/tags")
            if r.status_code == 200:
                os.environ["_OLLAMA_AVAILABLE"] = "1"
    except Exception:
        pass


# ── REST endpoints ─────────────────────────────────────────────────────────────

class LoginBody(BaseModel):
    password: str


@app.post("/login")
async def login(body: LoginBody):
    if body.password != APP_PASSWORD:
        raise HTTPException(status_code=401, detail="Wrong password")
    return {"token": create_token()}


@app.get("/models")
async def list_models():
    from agents.router import MODEL_LABELS
    available = get_available_models()
    healthy   = get_healthy_available(available)
    degraded  = available - healthy
    return {
        "available": list(available),
        "healthy":   list(healthy),
        "degraded":  list(degraded),
        "labels":    MODEL_LABELS,
    }


# ── WebSocket ──────────────────────────────────────────────────────────────────

@app.websocket("/ws")
async def ws_endpoint(websocket: WebSocket, token: str = Query(...)):
    if not verify_token(token):
        await websocket.close(code=4001)
        return

    await websocket.accept()

    session_id = f"s_{int(time.time() * 1000)}"
    session = _history_store["sessions"].setdefault(session_id, {
        "title":    "New Session",
        "created":  datetime.now(timezone.utc).isoformat(),
        "messages": [],
        "mode":     "general",
    })

    available = get_available_models()
    paused    = False

    def _save():
        _history_store["sessions"][session_id] = session
        _save_history(_history_store)

    # ── Chat handler ───────────────────────────────────────────────────────────

    async def handle_chat(data: dict):
        nonlocal paused
        message = data.get("message", "").strip()
        if not message:
            return

        mode = data.get("mode", session.get("mode", "general"))
        session["mode"] = mode

        system_prompt = load_context(mode)

        from agents.router import ALL_MODELS, MODEL_LABELS, route_chat
        model, reason = route_chat(message, available)
        alternatives  = [m for m in ALL_MODELS if m != model and m in available][:2]

        await websocket.send_json({
            "type":         "routing_decision",
            "chosen":       model,
            "reason":       reason,
            "countdown":    5,
            "alternatives": alternatives,
            "labels":       MODEL_LABELS,
        })

        # ── 5-second countdown: poll for override / pause ──────────────────────
        final_model = model
        deadline    = asyncio.get_event_loop().time() + 5.0

        while asyncio.get_event_loop().time() < deadline:
            remaining = deadline - asyncio.get_event_loop().time()
            try:
                msg = await asyncio.wait_for(
                    websocket.receive_json(), timeout=min(0.15, remaining)
                )
                msg_type = msg.get("type")
                if msg_type == "override_model":
                    final_model = msg["model"]
                    break
                elif msg_type == "pause":
                    paused = True
                    _save()
                    await websocket.send_json({"type": "paused"})
                    return
                elif msg_type == "ping":
                    await websocket.send_json({"type": "pong"})
                # Other messages during countdown are silently skipped
            except asyncio.TimeoutError:
                pass

        if paused:
            return

        # ── Stream response (with automatic fallback) ─────────────────────────
        # Filter empty entries (from past failed sessions) — APIs reject empty content
        history = [h for h in session["messages"] if h.get("content")]
        session["messages"] = history  # sync back so appends are saved to the session
        full_response = ""

        # Build cascade: chosen model first, then healthy alternatives
        tried: set[str] = set()
        cascade = [final_model] + [
            m for m in FALLBACK_CHAIN
            if m != final_model and m in available and is_model_healthy(m)
        ]

        streamed_ok = False
        for attempt_model in cascade:
            if attempt_model in tried:
                continue
            tried.add(attempt_model)

            if not is_model_healthy(attempt_model):
                continue

            try:
                agent = get_agent(attempt_model)
            except KeyError:
                continue

            if attempt_model != final_model:
                # Notify client we switched
                await websocket.send_json({
                    "type": "routing_decision",
                    "chosen":       attempt_model,
                    "reason":       f"Falling back — {final_model} is down",
                    "countdown":    0,
                    "alternatives": [],
                    "labels":       {},
                })

            full_response = ""
            try:
                async for token in agent.stream(history, message, system_prompt):
                    if paused:
                        _save()
                        await websocket.send_json({"type": "paused"})
                        return
                    full_response += token
                    await websocket.send_json({
                        "type": "token", "delta": token, "model": attempt_model
                    })
                final_model = attempt_model
                streamed_ok = True
                break  # success — stop cascading

            except Exception as e:
                mark_model_failed(attempt_model)
                err_short = str(e)[:120]
                await websocket.send_json({
                    "type":    "error",
                    "message": f"{attempt_model} failed ({err_short}) — trying next model...",
                })

        if not streamed_ok:
            await websocket.send_json({
                "type":    "error",
                "message": "All models are down right now. Check your API keys or try again later.",
            })
            return

        await websocket.send_json({"type": "done", "model": final_model})

        # Append to history
        history.append({"role": "user",      "content": message})
        history.append({"role": "assistant", "content": full_response})

        # Set session title from first message
        if session["title"] == "New Session":
            session["title"] = message[:60]

        _save()

    # ── Pipeline handler ───────────────────────────────────────────────────────

    async def handle_pipeline(data: dict):
        from tools.pipeline import run_pipeline
        script_key = data.get("script", "reddit")
        priority   = _ffmpeg_priority

        async def _send(payload: dict):
            await websocket.send_json(payload)

        await run_pipeline(script_key, _send, priority=priority)

    # ── Main receive loop ──────────────────────────────────────────────────────

    try:
        while True:
            data     = await websocket.receive_json()
            msg_type = data.get("type")

            if msg_type == "ping":
                await websocket.send_json({"type": "pong"})

            elif msg_type == "chat":
                if not paused:
                    await handle_chat(data)
                else:
                    await websocket.send_json({
                        "type": "error",
                        "message": "Assistant is paused. Click Resume to continue.",
                    })

            elif msg_type == "pause":
                paused = True
                _save()
                await websocket.send_json({"type": "paused"})

            elif msg_type == "resume":
                paused = False
                await websocket.send_json({"type": "resumed"})

            elif msg_type == "save":
                _save()
                await websocket.send_json({"type": "saved"})

            elif msg_type == "set_priority":
                global _ffmpeg_priority
                level = data.get("level", "low")
                if level in ("low", "normal", "boost"):
                    _ffmpeg_priority = level
                await websocket.send_json({"type": "priority_set", "level": _ffmpeg_priority})

            elif msg_type == "pipeline_run":
                await handle_pipeline(data)

            elif msg_type == "github_push_code":
                if not _github_configured():
                    await websocket.send_json({
                        "type": "error",
                        "message": "GitHub not configured — add GITHUB_TOKEN and GITHUB_REPO to .env",
                    })
                else:
                    await websocket.send_json({"type": "github_sync_start"})
                    results = await github_sync.push_code_files(BASE_DIR)
                    pushed  = sum(1 for ok in results.values() if ok)
                    failed  = sum(1 for ok in results.values() if not ok)
                    await websocket.send_json({
                        "type":   "github_synced",
                        "pushed": pushed,
                        "failed": failed,
                        "repo":   os.environ.get("GITHUB_REPO", ""),
                    })

            elif msg_type == "get_sessions":
                sessions_summary = [
                    {"id": sid, "title": s["title"], "created": s["created"]}
                    for sid, s in list(_history_store["sessions"].items())[-5:]
                ]
                await websocket.send_json({"type": "sessions", "sessions": sessions_summary})

            elif msg_type == "get_history":
                sid    = data.get("session_id", session_id)
                target = _history_store["sessions"].get(sid, {})
                await websocket.send_json({
                    "type":     "history",
                    "messages": target.get("messages", []),
                })

    except WebSocketDisconnect:
        _save()
    except Exception:
        _save()
        raise


# ── Static frontend ────────────────────────────────────────────────────────────
# Mount after all API routes so /ws and /login are not shadowed.
app.mount("/", StaticFiles(directory=str(BASE_DIR / "frontend"), html=True), name="frontend")
