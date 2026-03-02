"""Groq Llama streaming agent."""
import os
from typing import AsyncGenerator
from groq import AsyncGroq

MODEL = "llama-3.3-70b-versatile"


def is_available() -> bool:
    return bool(os.environ.get("GROQ_API_KEY"))


def _build_messages(history: list[dict], message: str, system_prompt: str) -> list[dict]:
    msgs = [{"role": "system", "content": system_prompt}]
    for h in history[-20:]:
        msgs.append({"role": h["role"], "content": h["content"]})
    msgs.append({"role": "user", "content": message})
    return msgs


async def stream(
    history: list[dict],
    message: str,
    system_prompt: str,
) -> AsyncGenerator[str, None]:
    client = AsyncGroq(api_key=os.environ["GROQ_API_KEY"])
    response = await client.chat.completions.create(
        model=MODEL,
        messages=_build_messages(history, message, system_prompt),
        stream=True,
    )
    async for chunk in response:
        delta = chunk.choices[0].delta.content
        if delta:
            yield delta
