# App Development Context

## Stack Preferences
- **Backend**: Python (FastAPI), Node.js if needed
- **Frontend**: Vanilla JS or minimal framework — no React unless justified
- **Database**: SQLite for local, Postgres for cloud
- **Auth**: JWT, simple password-based for personal tools
- **Deployment**: Railway (free tier), Cloudflare Pages for static

## Existing Projects
| Project | Stack | Status |
|---------|-------|--------|
| E:/reddit-pipeline | Python, FFmpeg, edge-tts | Working |
| E:/mythology-pipeline | Python, FLUX Schnell, FFmpeg | Paused |
| E:/assistant | FastAPI, WebSocket, multi-model | Active |

## Code Style
- Prefer simple over clever
- No over-engineering — minimum code for the task
- Type hints where they add clarity
- Error messages should be actionable (not generic)

## AI APIs in Use
| Model | API Key Env Var | Use Case |
|-------|-----------------|----------|
| Claude Sonnet 4.6 | ANTHROPIC_API_KEY | Code, debugging, architecture |
| Groq Llama-4/Llama-3.3-70b | GROQ_API_KEY | Quick tasks, scoring, fallback |
| Gemini 2.0 Flash | GEMINI_API_KEY | Creative, writing, default free |
| Deepseek via OpenRouter | OPENROUTER_API_KEY | Alternative reasoning |
| Ollama (local) | OLLAMA_BASE_URL | Private/offline tasks |

## Deployment Checklist
1. `git push` to GitHub private repo
2. Railway auto-deploys from main branch
3. Set env vars in Railway dashboard
4. Test WebSocket from phone via Railway URL
