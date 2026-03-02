"""Ollama local streaming agent (runs on user's PC, not cloud)."""
import json
import os
from typing import AsyncGenerator

import httpx

BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")
MODEL = "llama3.2"


def is_available() -> bool:
    """Only available when OLLAMA_BASE_URL is reachable — checked at startup."""
    return bool(os.environ.get("_OLLAMA_AVAILABLE"))


def _build_messages(history: list[dict], message: str, system_prompt: str) -> list:
    msgs = [{"role": "system", "content": system_prompt}]
    for h in history[-20:]:
        if h.get("content"):
            msgs.append({"role": h["role"], "content": h["content"]})
    msgs.append({"role": "user", "content": message})
    return msgs


async def stream(
    history: list[dict],
    message: str,
    system_prompt: str,
) -> AsyncGenerator[str, None]:
    url = f"{BASE_URL}/api/chat"
    payload = {
        "model": MODEL,
        "messages": _build_messages(history, message, system_prompt),
        "stream": True,
    }
    async with httpx.AsyncClient(timeout=120.0) as client:
        async with client.stream("POST", url, json=payload) as resp:
            async for line in resp.aiter_lines():
                if not line:
                    continue
                data = json.loads(line)
                text = data.get("message", {}).get("content", "")
                if text:
                    yield text
                if data.get("done"):
                    break
