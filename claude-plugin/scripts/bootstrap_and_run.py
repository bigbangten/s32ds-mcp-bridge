#!/usr/bin/env python3
"""
Cross-platform MCP server bootstrap.
Checks Python version, installs missing dependencies, then runs the server.

Runs on any machine with Python 3.10+ preinstalled. No other prereqs.
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


def die(msg: str, code: int = 1) -> None:
    print(f"[s32ds-mcp bootstrap] {msg}", file=sys.stderr)
    sys.exit(code)


def check_python() -> None:
    if sys.version_info < (3, 10):
        die(
            f"Python 3.10+ required (found {sys.version_info.major}.{sys.version_info.minor}). "
            "On Windows: winget install Python.Python.3.11"
        )


def ensure_module(module_name: str, pip_spec: str | None = None) -> None:
    try:
        __import__(module_name)
    except ImportError:
        pip_spec = pip_spec or module_name
        print(f"[s32ds-mcp bootstrap] installing {pip_spec} ...", file=sys.stderr)
        try:
            subprocess.check_call(
                [sys.executable, "-m", "pip", "install", "--user", "--quiet", pip_spec],
                stderr=sys.stderr,
            )
        except subprocess.CalledProcessError as e:
            die(f"pip install {pip_spec} failed: {e}")


def install_server_package() -> None:
    """Install (or editable-install) the s32ds_mcp_server package bundled with this plugin."""
    # Repo install: <repo_root>/claude-plugin/scripts/bootstrap_and_run.py
    # Standalone Codex install: <plugin_root>/scripts/bootstrap_and_run.py
    here = Path(__file__).resolve().parent
    candidates = [
        here.parent / "mcp-server",
        here.parent.parent / "mcp-server",
    ]
    server_dir = next((path for path in candidates if path.exists()), None)
    if server_dir is not None:
        # Always prefer this plugin's bundled source over an older editable or
        # user-site installation left by a previous plugin version.
        bundled_src = server_dir / "src"
        if bundled_src.exists():
            sys.path.insert(0, str(bundled_src))
            try:
                import s32ds_mcp_server  # noqa: F401
                return
            except ImportError:
                pass

    # A manually installed package is only a fallback when bundled source is
    # unavailable or incomplete.
    try:
        import s32ds_mcp_server  # noqa: F401
        return
    except ImportError:
        pass

    if server_dir is None:
        # Fallback: maybe the plugin was installed standalone (without mcp-server/ sibling).
        # Try pip install from PyPI once we publish; for now require sibling.
        die(
            "mcp-server source not found next to this plugin. "
            "Make sure the plugin was installed from a git repo containing mcp-server/."
        )

    print(f"[s32ds-mcp bootstrap] installing s32ds-mcp-server from {server_dir} ...", file=sys.stderr)
    try:
        subprocess.check_call(
            [sys.executable, "-m", "pip", "install", "--user", "--quiet", "-e", str(server_dir)],
            stderr=sys.stderr,
        )
    except subprocess.CalledProcessError as e:
        die(f"pip install -e {server_dir} failed: {e}")


def ensure_token_readable() -> None:
    """On Windows, the bridge tightens the token file's ACL and blocks even the owner's
    bash read. Re-grant read access to the current user. Idempotent."""
    if os.name != "nt":
        return
    workspace = os.environ.get("S32DS_WORKSPACE") or os.path.expanduser("~/workspaceS32DS.3.5")
    token_path = Path(workspace) / ".metadata" / ".plugins" / "com.example.s32ds.agent.bridge" / "token"
    if not token_path.exists():
        return  # bridge not started yet; nothing to do
    try:
        # Read test
        token_path.read_text()
    except PermissionError:
        user = os.environ.get("USERNAME", "")
        if user:
            try:
                subprocess.run(
                    ["icacls", str(token_path), "/grant", f"{user}:R"],
                    check=False,
                    capture_output=True,
                )
            except Exception:
                pass


def main() -> None:
    check_python()
    # Install external deps first (anyio/mcp both needed for server import)
    ensure_module("httpx", "httpx>=0.27,<1")
    ensure_module("mcp", "mcp>=1.0.0")
    install_server_package()
    ensure_token_readable()

    # Defaults if the plugin.json didn't set them
    os.environ.setdefault("S32DS_BRIDGE_URL", "http://127.0.0.1:39231")

    # Load token from workspace metadata if env var is unset
    if not os.environ.get("S32DS_BRIDGE_TOKEN"):
        workspace = os.environ.get("S32DS_WORKSPACE") or os.path.expanduser("~/workspaceS32DS.3.5")
        token_path = Path(workspace) / ".metadata" / ".plugins" / "com.example.s32ds.agent.bridge" / "token"
        if token_path.exists():
            try:
                os.environ["S32DS_BRIDGE_TOKEN"] = token_path.read_text().strip()
            except PermissionError:
                print(
                    f"[s32ds-mcp bootstrap] cannot read {token_path}; "
                    "run /s32:setup to repair ACL, or ensure S32DS is running with the agent plug-in.",
                    file=sys.stderr,
                )

    from s32ds_mcp_server.server import main as server_main  # noqa: E402
    server_main()


if __name__ == "__main__":
    main()
