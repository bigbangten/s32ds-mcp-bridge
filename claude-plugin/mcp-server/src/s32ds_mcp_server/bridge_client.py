from __future__ import annotations

import json as _json
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional, Tuple

import httpx


_DISCOVERY_FILE = Path.home() / ".s32ds-mcp" / "bridge.json"


class BridgeHttpError(RuntimeError):
    def __init__(self, status_code: int, payload: Any):
        self.status_code = status_code
        self.payload = payload
        super().__init__(f"Bridge request failed with HTTP {status_code}")


@dataclass(frozen=True)
class BridgeSettings:
    base_url: str
    token: str

    @classmethod
    def from_env(cls) -> "BridgeSettings":
        return cls(
            base_url=os.environ.get("S32DS_BRIDGE_URL", "http://127.0.0.1:39231").rstrip("/"),
            token=os.environ.get("S32DS_BRIDGE_TOKEN", "").strip(),
        )


def _read_discovery_file() -> Optional[Tuple[str, str]]:
    """Read the user-profile discovery file written by the bridge plug-in.

    Returns (bridge_url, token) or None. This is the preferred discovery path
    because it works regardless of where the user's S32DS workspace is.
    """
    if not _DISCOVERY_FILE.exists():
        return None
    try:
        raw = _DISCOVERY_FILE.read_text(encoding="utf-8")
        doc = _json.loads(raw)
        token = str(doc.get("token") or "").strip()
        url = str(doc.get("url") or "").strip()
        if token and url:
            return url, token
    except Exception as e:  # noqa: BLE001
        print(f"[s32ds-mcp] discovery file read failed: {e}", file=sys.stderr)
    return None


def _candidate_token_paths() -> list[Path]:
    """Fallback: workspace-local token files. Used only if discovery file is missing."""
    out: list[Path] = []
    ws = os.environ.get("S32DS_WORKSPACE")
    if ws:
        out.append(Path(ws) / ".metadata" / ".plugins" / "com.example.s32ds.agent.bridge" / "token")
    out.append(Path.home() / "workspaceS32DS.3.5" / ".metadata" / ".plugins"
               / "com.example.s32ds.agent.bridge" / "token")
    up = os.environ.get("USERPROFILE")
    if up:
        out.append(Path(up) / "workspaceS32DS.3.5" / ".metadata" / ".plugins"
                   / "com.example.s32ds.agent.bridge" / "token")
    return out


def _try_grant_read_acl(path: Path) -> None:
    """Best-effort icacls grant on Windows. Silent on failure."""
    if os.name != "nt":
        return
    user = os.environ.get("USERNAME") or ""
    if not user:
        return
    try:
        subprocess.run(
            ["icacls", str(path), "/grant", f"{user}:R"],
            check=False, capture_output=True, timeout=5,
        )
    except Exception:
        pass


def _read_token_from_disk() -> Optional[str]:
    """Resolve token. Discovery file (user-profile) preferred; workspace-local fallback."""
    # Preferred: discovery file written by the bridge plug-in at a fixed path
    disc = _read_discovery_file()
    if disc is not None:
        return disc[1]
    # Fallback: direct workspace-local token files
    for path in _candidate_token_paths():
        if not path.exists():
            continue
        for attempt in range(2):
            try:
                text = path.read_text(encoding="utf-8").strip()
                if text:
                    return text
            except PermissionError:
                if attempt == 0:
                    _try_grant_read_acl(path)
                    continue
                print(f"[s32ds-mcp] token file unreadable: {path}", file=sys.stderr)
                break
            except Exception as e:
                print(f"[s32ds-mcp] token read failed ({path}): {e}", file=sys.stderr)
                break
    return None


class BridgeClient:
    """HTTP client for the S32DS bridge.

    Refreshes the bearer token on-demand: if a request returns 401, re-reads
    the token from the workspace metadata (which may have rotated after a
    `-clean` restart of S32DS) and retries once.
    """

    def __init__(
        self,
        settings: Optional[BridgeSettings] = None,
        *,
        transport: Optional[httpx.AsyncBaseTransport] = None,
    ) -> None:
        self.settings = settings or BridgeSettings.from_env()
        self.transport = transport
        # If no token was injected via env, resolve from discovery file / disk.
        # Also prefer the URL declared in the discovery file if present — lets
        # the bridge pick a different port and clients still find it.
        if not self.settings.token:
            disc = _read_discovery_file()
            if disc is not None:
                url, tok = disc
                self.settings = BridgeSettings(base_url=url.rstrip("/"), token=tok)
            else:
                disk = _read_token_from_disk()
                if disk:
                    self.settings = BridgeSettings(base_url=self.settings.base_url, token=disk)
        self._last_refresh: float = 0.0

    def headers(self) -> dict[str, str]:
        if not self.settings.token:
            return {}
        return {"Authorization": f"Bearer {self.settings.token}"}

    def _refresh_token(self) -> bool:
        """Re-read token from disk. Returns True if it changed."""
        # Rate-limit refreshes so we don't spam disk/icacls on every failed call
        now = time.monotonic()
        if now - self._last_refresh < 1.0:
            return False
        self._last_refresh = now

        fresh = _read_token_from_disk()
        if fresh and fresh != self.settings.token:
            self.settings = BridgeSettings(base_url=self.settings.base_url, token=fresh)
            return True
        return False

    async def get_json(self, path: str, *, timeout: float = 30.0) -> Any:
        return await self.request_json("GET", path, timeout=timeout)

    async def post_json(self, path: str, payload: dict[str, Any], *, timeout: float = 30.0) -> Any:
        return await self.request_json("POST", path, json=payload, timeout=timeout)

    async def delete_json(self, path: str, *, timeout: float = 30.0) -> Any:
        return await self.request_json("DELETE", path, timeout=timeout)

    async def request_json(
        self,
        method: str,
        path: str,
        *,
        json: Optional[dict[str, Any]] = None,
        timeout: float = 30.0,
    ) -> Any:
        async def _do_request() -> httpx.Response:
            async with httpx.AsyncClient(
                base_url=self.settings.base_url,
                timeout=timeout,
                transport=self.transport,
            ) as client:
                return await client.request(method, path, json=json, headers=self.headers())

        response = await _do_request()

        # Token rotation / stale-env recovery: on 401, try re-reading and retry once.
        if response.status_code == 401 and self._refresh_token():
            response = await _do_request()

        try:
            payload = response.json()
        except ValueError:
            payload = response.text

        if response.is_error:
            raise BridgeHttpError(response.status_code, payload)
        return payload
