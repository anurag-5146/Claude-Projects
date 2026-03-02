"""
CIRCUIT Assistant — Python launcher.
Reads .env explicitly before starting uvicorn so all API keys are loaded.
Run: python run.py
"""
import os
import sys
from pathlib import Path

# ── Load .env manually (bypasses any inherited empty vars) ─────────────────────
env_file = Path(__file__).parent / ".env"
if env_file.exists():
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        os.environ[key.strip()] = val.strip()

# ── Verify keys loaded ─────────────────────────────────────────────────────────
keys = ["ANTHROPIC_API_KEY", "GROQ_API_KEY", "GEMINI_API_KEY", "OPENROUTER_API_KEY"]
print("\n CIRCUIT OS v2.0 -- Booting...\n")
for k in keys:
    val = os.environ.get(k, "")
    status = "[OK]" if val else "[MISSING]"
    print(f"  {k[:20]:<20} {status}")
print()

# ── Start uvicorn ──────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host="0.0.0.0", port=8000, reload=False)
