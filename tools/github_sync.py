"""
GitHub sync — push/pull files from the configured repo via GitHub REST API.
Uses httpx (async) — no aiohttp dependency.

Config (in .env):
  GITHUB_TOKEN  — Fine-grained PAT with Contents: Read & Write
  GITHUB_REPO   — e.g. "anurag-5146/Claude-Projects"
"""
import base64
import os
from pathlib import Path
from typing import Optional

import httpx

GITHUB_API     = "https://api.github.com"
DEFAULT_BRANCH = "main"

# Files/dirs to exclude from code push
_EXCLUDE = {
    ".env", ".env.local",
    "history.json",         # synced separately
    "history.tmp",
    "__pycache__", ".git", ".claude",
    "start.bat",            # Windows-only launcher
    "node_modules",
}
_EXCLUDE_EXTENSIONS = {".pyc", ".pyo", ".tmp"}


def is_configured() -> bool:
    return bool(os.environ.get("GITHUB_TOKEN")) and bool(os.environ.get("GITHUB_REPO"))


def _headers() -> dict:
    return {
        "Authorization": f"Bearer {os.environ.get('GITHUB_TOKEN', '')}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }


def _repo() -> str:
    return os.environ.get("GITHUB_REPO", "")


async def _get_sha(client: httpx.AsyncClient, repo_path: str) -> Optional[str]:
    """Get current file SHA (required to update an existing file in GitHub API)."""
    r = await client.get(
        f"{GITHUB_API}/repos/{_repo()}/contents/{repo_path}",
        headers=_headers(),
    )
    if r.status_code == 200:
        data = r.json()
        if isinstance(data, dict):
            return data.get("sha")
    return None


async def push_file(repo_path: str, content: str, message: str) -> bool:
    """Create or update a single file in the repo. Returns True on success."""
    if not is_configured():
        return False

    encoded = base64.b64encode(content.encode("utf-8")).decode()

    async with httpx.AsyncClient(timeout=20.0) as client:
        sha = await _get_sha(client, repo_path)
        body: dict = {
            "message": message,
            "content": encoded,
            "branch":  DEFAULT_BRANCH,
        }
        if sha:
            body["sha"] = sha

        r = await client.put(
            f"{GITHUB_API}/repos/{_repo()}/contents/{repo_path}",
            headers=_headers(),
            json=body,
        )
        return r.status_code in (200, 201)


async def pull_file(repo_path: str) -> Optional[str]:
    """Read a file from the repo. Returns decoded content string or None."""
    if not is_configured():
        return None

    async with httpx.AsyncClient(timeout=15.0) as client:
        r = await client.get(
            f"{GITHUB_API}/repos/{_repo()}/contents/{repo_path}",
            headers=_headers(),
        )
        if r.status_code != 200:
            return None
        data = r.json()
        if isinstance(data, list):
            return None  # it's a directory
        return base64.b64decode(data["content"]).decode("utf-8")


def _collect_code_files(base_dir: Path) -> dict[str, str]:
    """Walk base_dir and return {repo_path: content} for all pushable files."""
    files: dict[str, str] = {}

    for path in base_dir.rglob("*"):
        if not path.is_file():
            continue

        # Skip excluded dirs/files
        parts = set(path.parts)
        if parts & _EXCLUDE or path.name in _EXCLUDE:
            continue
        if path.suffix in _EXCLUDE_EXTENSIONS:
            continue
        # Skip anything inside memory/ (history synced separately)
        try:
            path.relative_to(base_dir / "memory")
            continue
        except ValueError:
            pass

        try:
            content = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, PermissionError):
            continue  # skip binary files

        repo_path = path.relative_to(base_dir).as_posix()
        files[repo_path] = content

    return files


async def push_code_files(base_dir: Path) -> dict[str, bool]:
    """
    Push all code files to the repo.
    Returns {repo_path: success} for each file attempted.
    """
    if not is_configured():
        return {}

    files = _collect_code_files(base_dir)
    results: dict[str, bool] = {}

    # Use a single client for all requests to reuse the connection
    async with httpx.AsyncClient(timeout=20.0) as client:
        for repo_path, content in files.items():
            encoded = base64.b64encode(content.encode("utf-8")).decode()
            sha = await _get_sha(client, repo_path)
            body: dict = {
                "message": f"Circuit: sync {repo_path}",
                "content": encoded,
                "branch":  DEFAULT_BRANCH,
            }
            if sha:
                body["sha"] = sha

            r = await client.put(
                f"{GITHUB_API}/repos/{_repo()}/contents/{repo_path}",
                headers=_headers(),
                json=body,
            )
            results[repo_path] = r.status_code in (200, 201)

    return results


async def push_history(history_data: str) -> bool:
    """Push the session history JSON to memory/history.json in the repo."""
    return await push_file(
        "memory/history.json",
        history_data,
        "Circuit: auto-sync session history",
    )
