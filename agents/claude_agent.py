"""
Claude agent — uses Claude Code CLI (Pro subscription, no API credits needed).
Falls back to Anthropic API if CLI is unavailable or fails.
"""
import asyncio
import os
import shutil
from typing import AsyncGenerator

MODEL = "claude-sonnet-4-6"
CLI_TIMEOUT = 10  # seconds before giving up on CLI and falling back to API

CLAUDE_CLI = shutil.which("claude") or r"C:\Users\drona\AppData\Roaming\npm\claude"


def is_available() -> bool:
    return bool(shutil.which("claude")) or bool(os.environ.get("ANTHROPIC_API_KEY"))


def _build_prompt(history: list[dict], message: str, system_prompt: str) -> str:
    parts = []
    if system_prompt:
        parts.append(f"<system>\n{system_prompt}\n</system>\n")
    for h in history[-10:]:
        if not h.get("content"):
            continue
        role = "Human" if h["role"] == "user" else "Assistant"
        parts.append(f"{role}: {h['content']}")
    parts.append(f"Human: {message}")
    parts.append("Assistant:")
    return "\n\n".join(parts)


async def stream(
    history: list[dict],
    message: str,
    system_prompt: str,
) -> AsyncGenerator[str, None]:
    # ── Try Claude Code CLI first (uses Pro subscription, no API credits) ──────
    cli = shutil.which("claude") or CLAUDE_CLI
    if cli and os.path.exists(cli):
        env = os.environ.copy()
        # Strip vars that signal we're inside a Claude session — let CLI run fresh
        for key in ("CLAUDECODE", "CLAUDE_CODE", "CLAUDE_CODE_ENTRYPOINT"):
            env.pop(key, None)

        prompt = _build_prompt(history, message, system_prompt)

        try:
            # On Windows, .CMD files need to be run via cmd /c
            import sys
            if sys.platform == "win32" and cli.upper().endswith((".CMD", ".BAT")):
                cmd_args = ["cmd", "/c", cli, "--print"]
            else:
                cmd_args = [cli, "--print"]

            proc = await asyncio.create_subprocess_exec(
                *cmd_args,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env,
            )
            stdout, stderr = await asyncio.wait_for(
                proc.communicate(input=prompt.encode("utf-8")),
                timeout=CLI_TIMEOUT,
            )

            if proc.returncode == 0 and stdout.strip():
                text = stdout.decode("utf-8", errors="replace").strip()
                chunk_size = 80
                for i in range(0, len(text), chunk_size):
                    yield text[i:i + chunk_size]
                    await asyncio.sleep(0)
                return

            # CLI returned non-zero or empty — fall through to API
        except asyncio.TimeoutError:
            pass  # CLI timed out — fall through to API
        except Exception:
            pass  # CLI not usable — fall through to API

    # ── Fallback: Anthropic API ────────────────────────────────────────────────
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        raise Exception("Claude CLI unavailable and no ANTHROPIC_API_KEY set")

    from anthropic import AsyncAnthropic

    msgs = [
        {"role": h["role"], "content": h["content"]}
        for h in history[-20:]
        if h.get("content")
    ]
    msgs.append({"role": "user", "content": message})

    client = AsyncAnthropic(api_key=api_key)
    async with client.messages.stream(
        model=MODEL,
        max_tokens=4096,
        system=system_prompt,
        messages=msgs,
    ) as s:
        async for text in s.text_stream:
            yield text
