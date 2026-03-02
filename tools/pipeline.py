"""
Pipeline runner — executes reddit-pipeline / mythology-pipeline scripts,
streams stdout to the WebSocket, and optionally uploads outputs to Google Drive.

Google Drive upload requires:
  pip install google-api-python-client google-auth-httplib2 google-auth-oauthlib
  GOOGLE_SERVICE_ACCOUNT_JSON env var (path to service account JSON)
  GOOGLE_DRIVE_FOLDER_ID env var (target Drive folder)
"""
import asyncio
import os
import sys
from pathlib import Path

# Priority flags (Windows)
_PRIORITY = {
    "low":    0x00004000,  # BELOW_NORMAL_PRIORITY_CLASS  — gaming mode
    "normal": 0x00000020,  # NORMAL_PRIORITY_CLASS
    "boost":  0x00008000,  # ABOVE_NORMAL_PRIORITY_CLASS  — performance mode
}

PIPELINE_SCRIPTS = {
    "reddit":     Path("E:/reddit-pipeline/main.py"),
    "reddit_batch": Path("E:/reddit-pipeline/batch_run.py"),
    "mythology":  Path("E:/mythology-pipeline/main.py"),
}


async def run_pipeline(script_key: str, send_fn, priority: str = "low"):
    """
    Run a pipeline script and stream each stdout line to the WebSocket via send_fn.
    send_fn is an async callable: send_fn({"type": "pipeline_line", "text": "..."})
    """
    script = PIPELINE_SCRIPTS.get(script_key)
    if script is None:
        await send_fn({"type": "error", "message": f"Unknown script: {script_key}"})
        return

    if not script.exists():
        await send_fn({"type": "error", "message": f"Script not found: {script}"})
        return

    flags = _PRIORITY.get(priority, _PRIORITY["low"]) if sys.platform == "win32" else 0

    # Build subprocess command
    cmd = [sys.executable, str(script)]
    kwargs = dict(
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        cwd=str(script.parent),
    )
    if sys.platform == "win32":
        kwargs["creationflags"] = flags

    proc = await asyncio.create_subprocess_exec(*cmd, **kwargs)

    async for raw_line in proc.stdout:
        line = raw_line.decode("utf-8", errors="replace").rstrip()
        await send_fn({"type": "pipeline_line", "text": line})

    await proc.wait()
    await send_fn({"type": "pipeline_done", "returncode": proc.returncode})

    # Auto-upload outputs if configured
    if proc.returncode == 0:
        folder_id = os.environ.get("GOOGLE_DRIVE_FOLDER_ID")
        sa_json = os.environ.get("GOOGLE_SERVICE_ACCOUNT_JSON")
        if folder_id and sa_json:
            output_dir = script.parent / "output"
            await send_fn({"type": "pipeline_line", "text": "[Drive] Uploading outputs..."})
            try:
                await asyncio.to_thread(upload_outputs_to_drive, output_dir, folder_id, sa_json)
                await send_fn({"type": "pipeline_line", "text": "[Drive] Upload complete."})
            except Exception as e:
                await send_fn({"type": "pipeline_line", "text": f"[Drive] Upload failed: {e}"})


def upload_outputs_to_drive(output_dir: Path, folder_id: str, sa_json_path: str):
    """
    Upload all .mp4 files in output_dir to Google Drive folder.
    Uses a service account — no browser OAuth needed.

    Setup:
      1. Create a service account in Google Cloud Console
      2. Enable Drive API
      3. Download JSON key → set GOOGLE_SERVICE_ACCOUNT_JSON=/path/to/key.json
      4. Share your Drive folder with the service account email
      5. Set GOOGLE_DRIVE_FOLDER_ID=<folder_id_from_URL>
    """
    try:
        from google.oauth2 import service_account
        from googleapiclient.discovery import build
        from googleapiclient.http import MediaFileUpload
    except ImportError:
        raise RuntimeError(
            "Google Drive support not installed. "
            "Run: pip install google-api-python-client google-auth-httplib2"
        )

    scopes = ["https://www.googleapis.com/auth/drive.file"]
    creds = service_account.Credentials.from_service_account_file(sa_json_path, scopes=scopes)
    service = build("drive", "v3", credentials=creds, cache_discovery=False)

    for mp4 in sorted(output_dir.glob("*.mp4")):
        # Check if file already exists in Drive (by name)
        q = f"name='{mp4.name}' and '{folder_id}' in parents and trashed=false"
        existing = service.files().list(q=q, fields="files(id)").execute().get("files", [])
        if existing:
            continue  # skip duplicates

        meta = {"name": mp4.name, "parents": [folder_id]}
        media = MediaFileUpload(str(mp4), mimetype="video/mp4", resumable=True)
        service.files().create(body=meta, media_body=media, fields="id").execute()
