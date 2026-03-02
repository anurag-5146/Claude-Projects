# Video Pipeline Context

## Reddit Story Pipeline (E:/reddit-pipeline)

### Pipeline Flow
fetch Reddit post → score with Groq → write script (Claude/Gemini) → voice (edge-tts Ryan 1.15x) → subtitles (faster-whisper) → render (FFmpeg)

### Key Files
- `main.py` — orchestrator
- `agents/reddit_agent.py` — Reddit JSON API (no auth needed), Groq scoring
- `agents/script_agent.py` — Claude primary, Groq fallback
- `agents/voice_agent.py` — edge-tts en-GB-RyanNeural at +0% (atempo=1.15 via FFmpeg)
- `agents/subtitle_agent.py` — ASS format, 4 words/card long, 3 words/card shorts
- `agents/video_agent.py` — FFmpeg compositor
- `config.py` — all paths, model names, tunables

### Assets
- `assets/backgrounds/minecraft_bg.mp4` — 10s Minecraft loop
- `assets/music/music.mp3` — Clash Defiant

### Output
- `output/<slug>_youtube.mp4` — 1920x1080 landscape
- `output/<slug>_shorts.mp4` — 1080x1920 portrait

### Known Issues / Notes
- edge-tts returns 0 word boundaries → use faster-whisper tiny model on final audio for subtitle timestamps
- FFmpeg runs at BELOW_NORMAL priority (0x00004000) so gaming is unaffected
- Subtitles: Impact font, white text, black outline, 4 words per card

### Script Rules
- Hook in first 5 seconds
- No "OP said" — write as first-person story
- Keep under 90 seconds for Shorts compatibility
- End with consequence/resolution, not cliff-hanger

### Running the Pipeline
```bash
cd E:/reddit-pipeline
python main.py                # interactive: pick subreddit + post
python batch_run.py           # auto: top posts from config subreddits
```
