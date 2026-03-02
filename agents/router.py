"""
Smart model router — ported from reddit-pipeline's model_router.py.
No countdown logic here; countdown is handled in the WebSocket layer.
"""

_CLAUDE_SIGNALS = [
    "debug", "error", "traceback", "fix the bug", "why is this failing",
    "refactor", "architecture", "design pattern", "security",
    "explain this code", "review my code", "optimize",
    "i need the best", "use claude", "best quality",
]

_GROQ_SIGNALS = [
    "quick question", "what is", "how do i", "list", "give me examples",
    "translate", "summarize", "score", "rank", "rate",
]

_CREATIVE_SIGNALS = [
    "write", "rewrite", "story", "script", "narrat", "creative",
    "ending", "twist", "hook", "video", "youtube",
]

MODEL_LABELS = {
    "claude":      "Claude Sonnet 4.6",
    "groq":        "Groq Llama-3.3",
    "gemini":      "Gemini Flash",
    "ollama":      "Ollama (local)",
    "openrouter":  "Llama 3.3 (OpenRouter free)",
}

ALL_MODELS = ["claude", "groq", "gemini", "openrouter", "ollama"]


def route_chat(message: str, available: set[str]) -> tuple[str, str]:
    """
    Return (model_key, reason). Falls back within available set.
    OpenRouter (free Llama 3.3) replaces Ollama — same model, no local install needed.
    """
    msg = message.lower()

    if any(sig in msg for sig in ["offline", "local", "private", "ollama"]):
        model, reason = "openrouter", "free cloud Llama 3.3 via OpenRouter — no local install needed"
    elif any(sig in msg for sig in _CLAUDE_SIGNALS):
        model, reason = "claude", "code/debug task — Claude via CLI (Pro)"
    elif any(sig in msg for sig in _GROQ_SIGNALS):
        model, reason = "groq", "quick factual query — Groq is fastest"
    elif any(sig in msg for sig in _CREATIVE_SIGNALS):
        model, reason = "claude", "creative/script task — Claude via CLI (Pro)"
    else:
        model, reason = "groq", "general task — Groq (default, free)"

    if model in available:
        return model, reason

    # Smart fallback: OpenRouter has free equivalents for groq/gemini
    if "openrouter" in available:
        from agents.openrouter_agent import FREE_FALLBACKS
        if model in FREE_FALLBACKS:
            # Store the OR sub-model in reason so server.py can pass it along
            or_model = FREE_FALLBACKS[model]
            return "openrouter", f"{reason} [OpenRouter/{or_model.split('/')[-1]}]"
        return "openrouter", f"{reason} [fell back to OpenRouter]"

    # Hard fallback chain — openrouter (free) before ollama (requires local install)
    for fallback in ["groq", "gemini", "openrouter", "claude", "ollama"]:
        if fallback in available:
            return fallback, f"{reason} [fell back: {model} unavailable]"

    return model, reason  # caller will surface the error
