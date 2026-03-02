"""
OpenRouter agent — single API key, 100+ models.
Supports model selection so we can swap in free fallbacks when primary models hit limits.

Free models (as of 2026):
  meta-llama/llama-3.3-70b-instruct    — Groq fallback
  google/gemini-flash-1.5              — Gemini fallback
  qwen/qwen-2.5-coder-32b-instruct     — best free code model
  mistralai/mistral-7b-instruct        — quick tasks
  deepseek/deepseek-chat               — reasoning (cheap, not free)
"""
import json
import os
from typing import AsyncGenerator

import httpx

# Default model — free Llama 3.3 70B (no cost, high quality)
DEFAULT_MODEL = "meta-llama/llama-3.3-70b-instruct"

# Free fallback map — when a primary provider is unavailable, use these
FREE_FALLBACKS = {
    "groq":   "meta-llama/llama-3.3-70b-instruct",
    "gemini": "google/gemini-flash-1.5",
    "code":   "qwen/qwen-2.5-coder-32b-instruct",
    "quick":  "mistralai/mistral-7b-instruct",
}

BASE_URL = "https://openrouter.ai/api/v1/chat/completions"


def is_available() -> bool:
    return bool(os.environ.get("OPENROUTER_API_KEY"))


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
    model: str = DEFAULT_MODEL,
) -> AsyncGenerator[str, None]:
    headers = {
        "Authorization": f"Bearer {os.environ['OPENROUTER_API_KEY']}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://assistant.local",
    }
    body = {
        "model": model,
        "messages": _build_messages(history, message, system_prompt),
        "stream": True,
    }
    async with httpx.AsyncClient(timeout=60.0) as client:
        async with client.stream("POST", BASE_URL, headers=headers, json=body) as resp:
            async for line in resp.aiter_lines():
                if not line.startswith("data: "):
                    continue
                payload = line[6:]
                if payload == "[DONE]":
                    break
                data = json.loads(payload)
                delta = data["choices"][0]["delta"].get("content", "")
                if delta:
                    yield delta
