"""Gemini Flash streaming agent."""
import os
from typing import AsyncGenerator

MODEL = "gemini-2.0-flash"


def is_available() -> bool:
    return bool(os.environ.get("GEMINI_API_KEY"))


def _build_contents(history: list[dict], message: str) -> list:
    contents = []
    for h in history[-20:]:
        if not h.get("content"):
            continue
        # Gemini uses "model" role, not "assistant"
        role = "model" if h["role"] == "assistant" else h["role"]
        contents.append({"role": role, "parts": [{"text": h["content"]}]})
    contents.append({"role": "user", "parts": [{"text": message}]})
    return contents


async def stream(
    history: list[dict],
    message: str,
    system_prompt: str,
) -> AsyncGenerator[str, None]:
    # Lazy import — google.genai triggers google.auth credential discovery on
    # module load which can hang. Import only when actually needed.
    from google import genai
    from google.genai import types

    client = genai.Client(api_key=os.environ["GEMINI_API_KEY"])
    config = types.GenerateContentConfig(system_instruction=system_prompt)
    response = await client.aio.models.generate_content_stream(
        model=MODEL,
        contents=_build_contents(history, message),
        config=config,
    )
    async for chunk in response:
        if chunk.text:
            yield chunk.text
