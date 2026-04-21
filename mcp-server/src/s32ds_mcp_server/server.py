from __future__ import annotations

import json
from types import SimpleNamespace
from typing import Any, Optional
from urllib.parse import quote, quote_plus

try:
    from mcp.server.fastmcp import FastMCP
except ImportError:  # pragma: no cover - offline fallback for local verification
    class FastMCP:  # type: ignore[no-redef]
        def __init__(self, name: str) -> None:
            self.name = name
            self._tool_specs: list[dict[str, Any]] = []
            self._resource_specs: list[dict[str, Any]] = []
            self._mcp_server = self

        def resource(self, uri: str):
            def decorator(func):
                self._resource_specs.append({"uri": uri, "name": func.__name__, "func": func})
                return func

            return decorator

        def tool(self, name: Optional[str] = None):
            def decorator(func):
                self._tool_specs.append(
                    {
                        "name": name or func.__name__,
                        "description": (func.__doc__ or "").strip(),
                        "func": func,
                    }
                )
                return func

            return decorator

        async def list_tools(self):
            return [
                SimpleNamespace(name=spec["name"], description=spec["description"], inputSchema={})
                for spec in self._tool_specs
            ]

        async def list_resources(self):
            return [SimpleNamespace(uri=spec["uri"], name=spec["name"]) for spec in self._resource_specs]

        def create_initialization_options(self) -> dict[str, Any]:
            return {"server_name": self.name}

        def run(self, transport: str = "stdio") -> None:
            return None

        async def run_stdio_async(self) -> None:
            return None

from .bridge_client import BridgeClient

bridge_client = BridgeClient()


def _resource_text(payload: Any) -> str:
    return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True)


# ─────────────────────────── Phase 1 (read-only) ───────────────────────────

async def fetch_health() -> dict[str, Any]:
    return await bridge_client.get_json("/health")


async def fetch_state() -> dict[str, Any]:
    return await bridge_client.get_json("/state")


async def fetch_commands() -> dict[str, Any]:
    return await bridge_client.get_json("/commands")


async def fetch_command_search(query: str) -> dict[str, Any]:
    return await bridge_client.get_json(f"/commands/search?q={quote_plus(query)}")


async def fetch_registry_menus(filter_text: Optional[str] = None) -> dict[str, Any]:
    suffix = f"?q={quote_plus(filter_text)}" if filter_text else ""
    return await bridge_client.get_json(f"/registry/menus{suffix}")


async def fetch_legacy_actions() -> dict[str, Any]:
    return await bridge_client.get_json("/registry/legacy-actions")


async def fetch_views() -> dict[str, Any]:
    return await bridge_client.get_json("/views")


async def fetch_perspectives() -> dict[str, Any]:
    return await bridge_client.get_json("/perspectives")


async def fetch_wizards(wizard_type: str = "all") -> dict[str, Any]:
    return await bridge_client.get_json(f"/wizards?type={wizard_type}")


async def fetch_visible_menu(location_uri: str = "menu:org.eclipse.ui.main.menu") -> dict[str, Any]:
    return await bridge_client.post_json("/visible-menu", {"locationUri": location_uri})


async def fetch_problems(project: Optional[str] = None) -> dict[str, Any]:
    suffix = f"?project={quote_plus(project)}" if project else ""
    return await bridge_client.get_json(f"/markers/problems{suffix}")


# ────────────────────── Phase 2 (safe workbench writes) ──────────────────

async def post_show_view(view_id: str) -> dict[str, Any]:
    return await bridge_client.post_json("/show-view", {"viewId": view_id})


async def post_switch_perspective(perspective_id: str) -> dict[str, Any]:
    return await bridge_client.post_json("/switch-perspective", {"perspectiveId": perspective_id})


async def post_open_file(workspace_path: str) -> dict[str, Any]:
    return await bridge_client.post_json("/open-file", {"workspacePath": workspace_path})


async def post_save_all() -> dict[str, Any]:
    return await bridge_client.post_json("/save-all", {})


async def post_build_project(project_name: str, kind: str = "incremental",
                             timeout_ms: int = 300000) -> dict[str, Any]:
    return await bridge_client.post_json(
        "/build-project",
        {"projectName": project_name, "kind": kind, "timeoutMs": timeout_ms},
    )


async def fetch_editors() -> dict[str, Any]:
    return await bridge_client.get_json("/editors")


# ─────────────────────── Phase 3 (S32DS-specific discovery) ───────────────

async def fetch_s32ds_inventory() -> dict[str, Any]:
    return await bridge_client.get_json("/s32ds/inventory")


async def fetch_s32ds_config_tools() -> dict[str, Any]:
    return await bridge_client.get_json("/s32ds/config-tools")


async def fetch_s32ds_debuggers() -> dict[str, Any]:
    return await bridge_client.get_json("/s32ds/debuggers")


async def fetch_s32ds_toolchains() -> dict[str, Any]:
    return await bridge_client.get_json("/s32ds/toolchains")


# ──────────────────────── Phase 3.5 (guardrails) ─────────────────────────

async def fetch_launch_configs() -> dict[str, Any]:
    return await bridge_client.get_json("/launch-configs")


async def fetch_launch_analyze(name: str) -> dict[str, Any]:
    return await bridge_client.get_json(f"/launch-configs/{quote(name, safe='')}/analyze")


async def fetch_launch_details(name: str) -> dict[str, Any]:
    return await bridge_client.get_json(f"/launch-configs/{quote(name, safe='')}")


async def fetch_debug_sessions() -> dict[str, Any]:
    return await bridge_client.get_json("/debug/sessions")


async def fetch_debug_stackframes() -> dict[str, Any]:
    return await bridge_client.get_json("/debug/stackframes")


async def fetch_debug_variables(frame: int = 0) -> dict[str, Any]:
    return await bridge_client.get_json(f"/debug/variables?frame={frame}")


async def fetch_debug_breakpoints() -> dict[str, Any]:
    return await bridge_client.get_json("/debug/breakpoints")


async def fetch_dialogs_open() -> dict[str, Any]:
    return await bridge_client.get_json("/dialogs/open")


async def fetch_dialog_widgets(index: int, depth: int = 6) -> dict[str, Any]:
    return await bridge_client.get_json(f"/dialogs/{index}/widgets?depth={depth}")


async def fetch_console_list() -> dict[str, Any]:
    return await bridge_client.get_json("/console/list")


async def fetch_console_tail(name: Optional[str] = None,
                             index: Optional[int] = None,
                             lines: int = 100) -> dict[str, Any]:
    q = []
    if name is not None:
        q.append(f"name={quote_plus(name)}")
    if index is not None:
        q.append(f"index={index}")
    q.append(f"lines={lines}")
    return await bridge_client.get_json(f"/console/tail?{'&'.join(q)}")


# ─────────────────────────── MCP server setup ───────────────────────────

def create_server() -> FastMCP:
    mcp = FastMCP("s32ds")

    # Resources (read-only URIs)
    @mcp.resource("s32ds://health")
    async def health_resource() -> str:
        return _resource_text(await fetch_health())

    @mcp.resource("s32ds://state")
    async def state_resource() -> str:
        return _resource_text(await fetch_state())

    @mcp.resource("s32ds://commands")
    async def commands_resource() -> str:
        return _resource_text(await fetch_commands())

    @mcp.resource("s32ds://menus")
    async def menus_resource() -> str:
        return _resource_text(await fetch_registry_menus())

    @mcp.resource("s32ds://legacy-actions")
    async def legacy_actions_resource() -> str:
        return _resource_text(await fetch_legacy_actions())

    @mcp.resource("s32ds://views")
    async def views_resource() -> str:
        return _resource_text(await fetch_views())

    @mcp.resource("s32ds://perspectives")
    async def perspectives_resource() -> str:
        return _resource_text(await fetch_perspectives())

    @mcp.resource("s32ds://wizards")
    async def wizards_resource() -> str:
        return _resource_text(await fetch_wizards("all"))

    @mcp.resource("s32ds://problems")
    async def problems_resource() -> str:
        return _resource_text(await fetch_problems())

    @mcp.resource("s32ds://launch-configs")
    async def launch_configs_resource() -> str:
        return _resource_text(await fetch_launch_configs())

    # ─── Phase 1 tools ───
    @mcp.tool()
    async def health() -> dict[str, Any]:
        """Check whether the S32DS bridge is reachable and S32DS is running."""
        return await fetch_health()

    @mcp.tool()
    async def get_state() -> dict[str, Any]:
        """Return current S32DS workbench state (perspective, editor, selection, projects)."""
        return await fetch_state()

    @mcp.tool()
    async def list_commands() -> dict[str, Any]:
        """Return the full Eclipse/S32DS command list from the bridge."""
        return await fetch_commands()

    @mcp.tool()
    async def find_command(query: str) -> dict[str, Any]:
        """Search commands by id, name, description, category, or contributor."""
        return await fetch_command_search(query)

    @mcp.tool()
    async def list_registry_menus(filter_text: Optional[str] = None) -> dict[str, Any]:
        """Return menu contribution candidates from the Eclipse extension registry."""
        return await fetch_registry_menus(filter_text)

    @mcp.tool()
    async def list_legacy_actions() -> dict[str, Any]:
        """Return legacy action set and popup menu contributions (right-click menus too)."""
        return await fetch_legacy_actions()

    @mcp.tool()
    async def list_views() -> dict[str, Any]:
        """Return views discovered from org.eclipse.ui.views."""
        return await fetch_views()

    @mcp.tool()
    async def list_perspectives() -> dict[str, Any]:
        """Return perspectives discovered from org.eclipse.ui.perspectives."""
        return await fetch_perspectives()

    @mcp.tool()
    async def list_wizards(wizard_type: str = "all") -> dict[str, Any]:
        """Return new/import/export/all wizards from the Eclipse registry."""
        return await fetch_wizards(wizard_type)

    @mcp.tool()
    async def list_visible_menu(location_uri: str = "menu:org.eclipse.ui.main.menu") -> dict[str, Any]:
        """Materialize a menu for the current workbench context (visible + enabled)."""
        return await fetch_visible_menu(location_uri)

    @mcp.tool()
    async def list_problems(project: Optional[str] = None) -> dict[str, Any]:
        """Return Eclipse Problems-view markers. Optionally filter by project name."""
        return await fetch_problems(project)

    # ─── Phase 2 tools (safe writes) ───
    @mcp.tool()
    async def show_view(view_id: str) -> dict[str, Any]:
        """Open a view in the workbench by its view id (e.g. 'org.eclipse.ui.views.TaskList')."""
        return await post_show_view(view_id)

    @mcp.tool()
    async def switch_perspective(perspective_id: str) -> dict[str, Any]:
        """Switch the active perspective by id (e.g. 'com.nxp.swtools.clocks.clocks.perspective')."""
        return await post_switch_perspective(perspective_id)

    @mcp.tool()
    async def open_file(workspace_path: str) -> dict[str, Any]:
        """Open a file in an editor by workspace path (e.g. '/ProjectName/src/main.c')."""
        return await post_open_file(workspace_path)

    @mcp.tool()
    async def save_all() -> dict[str, Any]:
        """Save all dirty editors without prompting."""
        return await post_save_all()

    @mcp.tool()
    async def build_project(project_name: str, kind: str = "incremental",
                            timeout_ms: int = 300000) -> dict[str, Any]:
        """Build a project. kind in {incremental, full, clean, auto}. Returns job status + elapsed time."""
        return await post_build_project(project_name, kind, timeout_ms)

    @mcp.tool()
    async def list_editors() -> dict[str, Any]:
        """List currently open editor tabs with their dirty state."""
        return await fetch_editors()

    # ─── Phase 3 tools (S32DS discovery) ───
    @mcp.tool()
    async def s32ds_inventory() -> dict[str, Any]:
        """NXP/Freescale/PEmicro bundles installed in this S32DS (with state ACTIVE/RESOLVED/etc)."""
        return await fetch_s32ds_inventory()

    @mcp.tool()
    async def s32ds_config_tools() -> dict[str, Any]:
        """S32 Configuration Tools (Pins/Clocks/Peripherals/DCD/...) with perspective IDs, views, commands."""
        return await fetch_s32ds_config_tools()

    @mcp.tool()
    async def s32ds_debuggers() -> dict[str, Any]:
        """Installed debugger integrations (S32 Debugger, PEmicro, J-Link, GDB Hardware, ...)."""
        return await fetch_s32ds_debuggers()

    @mcp.tool()
    async def s32ds_toolchains() -> dict[str, Any]:
        """Installed NXP ARM toolchains (GCC versions, bare-metal vs Linux targets)."""
        return await fetch_s32ds_toolchains()

    # ─── Phase 3.5 tools (guardrails — read only, advise only) ───
    @mcp.tool()
    async def list_launch_configs() -> dict[str, Any]:
        """List all launch/debug/flash configurations with risk classification."""
        return await fetch_launch_configs()

    @mcp.tool()
    async def launch_config_details(name: str) -> dict[str, Any]:
        """Full dump of a launch config — every attribute, equivalent to opening
        the Debug Configurations dialog and reading every field.

        Use when the user asks what's inside a specific Debug/Run/Flash config
        without wanting them to open the Debug Configurations dialog.
        """
        return await fetch_launch_details(name)

    @mcp.tool()
    async def analyze_launch_config(name: str) -> dict[str, Any]:
        """Deep safety + sanity check of a launch config BEFORE the user launches it.

        Verifies: .elf exists, project accessible, target device, PEmicro server mode,
        risk classification. Use this any time the user asks 'can I flash/debug?'.
        """
        return await fetch_launch_analyze(name)

    @mcp.tool()
    async def debug_sessions() -> dict[str, Any]:
        """Currently active debug sessions, their threads, suspended state. Empty list when not debugging."""
        return await fetch_debug_sessions()

    @mcp.tool()
    async def debug_stackframes() -> dict[str, Any]:
        """Stack frames of the first suspended thread (when debugging and halted)."""
        return await fetch_debug_stackframes()

    @mcp.tool()
    async def debug_variables(frame: int = 0) -> dict[str, Any]:
        """Variables visible in the given stack frame (when debugging and halted)."""
        return await fetch_debug_variables(frame)

    @mcp.tool()
    async def debug_breakpoints() -> dict[str, Any]:
        """All registered breakpoints with enabled/resolved/line info."""
        return await fetch_debug_breakpoints()

    @mcp.tool()
    async def dialogs_open() -> dict[str, Any]:
        """Currently visible SWT shells (main window, dialogs, wizards, popups).

        Use when the user mentions a dialog/wizard is in front of them. Pair with
        dialog_widgets(index) to read its fields — never to click buttons.
        """
        return await fetch_dialogs_open()

    @mcp.tool()
    async def dialog_widgets(index: int, depth: int = 6) -> dict[str, Any]:
        """Widget tree of dialog at given index (from dialogs_open).

        Returns field values, button labels, combo selections, tab labels. Read-only.
        """
        return await fetch_dialog_widgets(index, depth)

    @mcp.tool()
    async def console_list() -> dict[str, Any]:
        """List all available Eclipse Consoles (Build, PEmicro GDB Server, etc.)."""
        return await fetch_console_list()

    @mcp.tool()
    async def console_tail(name: Optional[str] = None, index: Optional[int] = None,
                           lines: int = 100) -> dict[str, Any]:
        """Last N lines of a console. Match by partial name (e.g. 'PEMicro') or index from console_list."""
        return await fetch_console_tail(name, index, lines)

    return mcp


def main() -> None:
    create_server().run(transport="stdio")


if __name__ == "__main__":
    main()
