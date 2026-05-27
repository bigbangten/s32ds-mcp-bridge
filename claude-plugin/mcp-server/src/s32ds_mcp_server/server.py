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


# ??????????????????????????? Phase 1 (read-only) ???????????????????????????

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


# ?????????????????????? Phase 2 (safe workbench writes) ??????????????????

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


# ??????????????????????? Phase 3 (S32DS-specific discovery) ???????????????

async def fetch_s32ds_inventory() -> dict[str, Any]:
    return await bridge_client.get_json("/s32ds/inventory")


async def fetch_s32ds_config_tools() -> dict[str, Any]:
    return await bridge_client.get_json("/s32ds/config-tools")


async def fetch_s32ds_debuggers() -> dict[str, Any]:
    return await bridge_client.get_json("/s32ds/debuggers")


async def fetch_s32ds_toolchains() -> dict[str, Any]:
    return await bridge_client.get_json("/s32ds/toolchains")


# ???????????????????????? Phase 3.5 (guardrails) ?????????????????????????

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


async def fetch_debug_status() -> dict[str, Any]:
    return await bridge_client.get_json("/debug/status")


async def fetch_debug_location() -> dict[str, Any]:
    return await bridge_client.get_json("/debug/location")


async def fetch_debug_registers() -> dict[str, Any]:
    return await bridge_client.get_json("/debug/registers")


async def fetch_debug_memory(addr: str, length: int = 64) -> dict[str, Any]:
    return await bridge_client.get_json(f"/debug/memory?addr={quote_plus(addr)}&length={length}")


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


# ??????????????????????????? Phase 5 (danger gate + mutators) ???????????????????????????

async def fetch_danger_state() -> dict[str, Any]:
    return await bridge_client.get_json("/danger/state")


async def call_danger_enable(ttl_minutes: Optional[int] = None) -> dict[str, Any]:
    payload: dict[str, Any] = {}
    if ttl_minutes is not None and ttl_minutes > 0:
        payload["ttlMs"] = int(ttl_minutes) * 60 * 1000
    return await bridge_client.post_json("/danger/enable", payload)


async def call_danger_disable() -> dict[str, Any]:
    return await bridge_client.post_json("/danger/disable", {})


async def call_debug_step(kind: str = "over") -> dict[str, Any]:
    return await bridge_client.post_json(f"/debug/step?kind={quote_plus(kind)}", {})


async def call_debug_resume() -> dict[str, Any]:
    return await bridge_client.post_json("/debug/resume", {})


async def call_debug_suspend() -> dict[str, Any]:
    return await bridge_client.post_json("/debug/suspend", {})


async def call_debug_terminate() -> dict[str, Any]:
    return await bridge_client.post_json("/debug/terminate", {})


async def call_debug_restart() -> dict[str, Any]:
    return await bridge_client.post_json("/debug/restart", {})


async def call_debug_breakpoint_set(file: str, line: int,
                                    condition: Optional[str] = None) -> dict[str, Any]:
    body: dict[str, Any] = {"file": file, "line": int(line)}
    if condition:
        body["condition"] = condition
    return await bridge_client.post_json("/debug/breakpoints", body)


async def call_debug_breakpoint_clear(file: Optional[str] = None,
                                      line: Optional[int] = None,
                                      all: bool = False) -> dict[str, Any]:
    if all:
        return await bridge_client.delete_json("/debug/breakpoints?all=true")
    q = []
    if file:
        q.append(f"file={quote_plus(file)}")
    if line is not None:
        q.append(f"line={int(line)}")
    return await bridge_client.delete_json(
        "/debug/breakpoints" + ("?" + "&".join(q) if q else "")
    )


async def call_debug_memory_write(addr: str, hex: str) -> dict[str, Any]:
    return await bridge_client.post_json("/debug/memory", {"addr": addr, "hex": hex})


async def call_debug_register_write(name: str, value: str,
                                    group: Optional[str] = None) -> dict[str, Any]:
    body: dict[str, Any] = {"name": name, "value": value}
    if group:
        body["group"] = group
    return await bridge_client.post_json("/debug/registers", body)


async def call_launch_run(config_name: str, mode: str = "run") -> dict[str, Any]:
    return await bridge_client.post_json(
        "/launch/run", {"configName": config_name, "mode": mode}
    )

async def call_launch_run_with_overrides(config_name: str, mode: str = "run",
                                         copy_name: Optional[str] = None,
                                         overrides: Optional[dict[str, Any]] = None) -> dict[str, Any]:
    body: dict[str, Any] = {
        "configName": config_name,
        "mode": mode,
        "overrides": overrides or {},
    }
    if copy_name:
        body["copyName"] = copy_name
    return await bridge_client.post_json("/launch/run-with-overrides", body)



# ??????????????????????????? Phase 6 (expression / watch / variable / run-to-line) ???????

async def call_debug_evaluate(expression: str, frame: int = 0) -> dict[str, Any]:
    return await bridge_client.post_json(
        "/debug/evaluate", {"expression": expression, "frame": int(frame)}
    )


async def fetch_debug_watch_list() -> dict[str, Any]:
    return await bridge_client.get_json("/debug/watch")


async def call_debug_watch_add(expression: str) -> dict[str, Any]:
    return await bridge_client.post_json("/debug/watch", {"expression": expression})


async def call_debug_watch_remove(expression: Optional[str] = None,
                                  all: bool = False) -> dict[str, Any]:
    if all:
        return await bridge_client.delete_json("/debug/watch?all=true")
    q = f"?expression={quote_plus(expression)}" if expression else ""
    return await bridge_client.delete_json(f"/debug/watch{q}")


async def call_debug_variable_write(name: str, value: str, frame: int = 0) -> dict[str, Any]:
    return await bridge_client.post_json(
        "/debug/variable", {"name": name, "value": value, "frame": int(frame)}
    )


async def call_debug_run_to_line(file: str, line: int,
                                 skip_breakpoints: bool = False) -> dict[str, Any]:
    return await bridge_client.post_json(
        "/debug/run-to-line",
        {"file": file, "line": int(line), "skipBreakpoints": skip_breakpoints},
    )


async def call_debug_jump_to_line(file: str, line: int) -> dict[str, Any]:
    return await bridge_client.post_json(
        "/debug/jump-to-line", {"file": file, "line": int(line)}
    )


# ??????????????????????????? MCP server setup ???????????????????????????

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

    # ??? Phase 1 tools ???
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

    # ??? Phase 2 tools (safe writes) ???
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

    # ??? Phase 3 tools (S32DS discovery) ???
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

    # ??? Phase 3.5 tools (guardrails ??read only, advise only) ???
    @mcp.tool()
    async def list_launch_configs() -> dict[str, Any]:
        """List all launch/debug/flash configurations with risk classification."""
        return await fetch_launch_configs()

    @mcp.tool()
    async def launch_config_details(name: str) -> dict[str, Any]:
        """Full dump of a launch config ??every attribute, equivalent to opening
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
    async def debug_status() -> dict[str, Any]:
        """Lightweight debug status: anyLive/anyHalted + per-launch halted flags.

        Fast one-shot answer to "is something being debugged?" / "is it halted?" without
        pulling full stack/variable state. Use before debug_location / debug_registers.
        """
        return await fetch_debug_status()

    @mcp.tool()
    async def debug_location() -> dict[str, Any]:
        """Current halt location: {halted, configName, threadName, function, lineNumber, sourceElement}.

        Answers "where is it stopped?" ??returns the top stack frame's function name,
        source line, and (best-effort) file path for the first suspended thread. Returns
        {halted:false} when no target is suspended.
        """
        return await fetch_debug_location()

    @mcp.tool()
    async def debug_registers() -> dict[str, Any]:
        """Register groups and values for the first suspended thread's top frame.

        Returns {halted, hasRegisterGroups, groups:[{name, registers:[{name, value, size, type}]}]}.
        Read-only ??never writes registers. Works when the CDT debugger exposes
        standard IRegisterGroup (PEmicro, S32 Debugger, J-Link, GDB Hardware all do).
        """
        return await fetch_debug_registers()

    @mcp.tool()
    async def debug_memory(addr: str, length: int = 64) -> dict[str, Any]:
        """Read target memory as hex. addr accepts '0x...', pure hex, or decimal.

        length capped server-side to 4096 bytes to avoid stalling the target MCU.
        Requires a suspended debug target that adapts to IMemoryBlockRetrieval
        (standard for CDT + PEmicro/GDB). Never writes memory.
        """
        return await fetch_debug_memory(addr, length)

    @mcp.tool()
    async def dialogs_open() -> dict[str, Any]:
        """Currently visible SWT shells (main window, dialogs, wizards, popups).

        Use when the user mentions a dialog/wizard is in front of them. Pair with
        dialog_widgets(index) to read its fields ??never to click buttons.
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

    # ??? Phase 5 tools (danger gate + mutating debug/launch operations) ???
    #
    # Every "*_write", debug_step/resume/suspend/terminate/restart, breakpoint_*,
    # and launch_run is gated server-side. While the gate is OFF (default), the
    # bridge returns 403 with a hint to run /s32:danger on first. The gate has a
    # TTL (default 30 min, max 4 hours) and resets on bridge restart.

    @mcp.tool()
    async def danger_state() -> dict[str, Any]:
        """Show the current danger-mode state: {enabled, remainingMs, expiresAtEpochMs}.

        Read-only. Useful before calling any mutating tool to know whether the gate is open.
        """
        return await fetch_danger_state()

    @mcp.tool()
    async def danger_enable(ttl_minutes: int = 30) -> dict[str, Any]:
        """Open the danger gate so mutating debug/launch ops are allowed.

        ttl_minutes default 30, max 240. After the TTL expires the gate auto-closes;
        bridge restart also closes it. Pair with danger_disable() to close early.
        """
        return await call_danger_enable(ttl_minutes)

    @mcp.tool()
    async def danger_disable() -> dict[str, Any]:
        """Close the danger gate immediately."""
        return await call_danger_disable()

    @mcp.tool()
    async def debug_step(kind: str = "over") -> dict[str, Any]:
        """Single-step the first suspended thread. kind in {into, over, return}.

        Requires danger mode ON. Returns {ok, kind, threadName} on success or
        {ok:false, error} when no thread is suspended or the step kind isn't supported.
        """
        return await call_debug_step(kind)

    @mcp.tool()
    async def debug_resume() -> dict[str, Any]:
        """Resume the first suspended debug target. Requires danger mode."""
        return await call_debug_resume()

    @mcp.tool()
    async def debug_suspend() -> dict[str, Any]:
        """Suspend the first running debug target. Requires danger mode."""
        return await call_debug_suspend()

    @mcp.tool()
    async def debug_terminate() -> dict[str, Any]:
        """Terminate ALL live launches. Requires danger mode. Returns count terminated."""
        return await call_debug_terminate()

    @mcp.tool()
    async def debug_restart() -> dict[str, Any]:
        """Terminate the first live launch and re-launch its configuration in the same mode."""
        return await call_debug_restart()

    @mcp.tool()
    async def debug_breakpoint_set(file: str, line: int,
                                   condition: Optional[str] = None) -> dict[str, Any]:
        """Add a C/C++ line breakpoint via CDT (CDIDebugModel.createLineBreakpoint).

        file: workspace-relative path (e.g. '/myproj/src/main.c') or absolute filesystem path.
        line: 1-based line number.
        condition: optional GDB conditional expression (e.g. 'i == 5').

        Requires danger mode. Returns {ok, sourceHandle, line} or error if CDT not available
        or the file isn't in the workspace.
        """
        return await call_debug_breakpoint_set(file, line, condition)

    @mcp.tool()
    async def debug_breakpoint_clear(file: Optional[str] = None,
                                     line: Optional[int] = None,
                                     all: bool = False) -> dict[str, Any]:
        """Remove a breakpoint.

        Either pass file+line for a precise match, or all=True to wipe every breakpoint
        in the workspace. Requires danger mode.
        """
        return await call_debug_breakpoint_clear(file, line, all)

    @mcp.tool()
    async def debug_memory_write(addr: str, hex: str) -> dict[str, Any]:
        """Write hex bytes to target memory at addr. Requires danger mode + suspended target.

        addr accepts '0x...', pure hex (3+ chars), or decimal. hex is the byte payload
        without 0x prefix, even length, max 4096 bytes (8192 hex chars).
        Whitespace and underscores in the hex string are ignored.
        """
        return await call_debug_memory_write(addr, hex)

    @mcp.tool()
    async def debug_register_write(name: str, value: str,
                                   group: Optional[str] = None) -> dict[str, Any]:
        """Set a register on the first suspended thread's top frame.

        name: register name (e.g. 'r0', 'pc', 'cpsr'). Case-insensitive.
        value: string accepted by IRegister.verifyValue (typically hex like '0x1234' or decimal).
        group: optional register group name (e.g. 'General Registers') to disambiguate.

        Requires danger mode. Returns {ok, group, register, newValue} or error.
        """
        return await call_debug_register_write(name, value, group)

    @mcp.tool()
    async def launch_run(config_name: str, mode: str = "run") -> dict[str, Any]:
        """Run a stored launch configuration by name, in the given mode.

        config_name: matches the displayed launch-config name in 'Run > Run Configurations??.
        mode: 'run' | 'debug' (S32DS flash/debug launches and most other custom launch types
              use 'run' mode). Use launch_configs() to discover available configs first.

        Requires danger mode. This is the canonical way to run S32DS/PEmicro flash
        launch configs, run unit tests, or start a debug session programmatically.
        """
        return await call_launch_run(config_name, mode)
    @mcp.tool()
    async def launch_run_with_overrides(config_name: str, mode: str = "run",
                                        copy_name: Optional[str] = None,
                                        overrides_json: str = "{}") -> dict[str, Any]:
        """Clone a stored launch config, override attributes, then run the clone.

        Requires danger mode. Use for controlled S32DS/PEmicro experiments when
        the live launch manager has cached settings and editing .launch files on
        disk is not enough. overrides_json must be a JSON object whose keys are
        raw Eclipse launch attribute names.
        """
        overrides = json.loads(overrides_json or "{}")
        if not isinstance(overrides, dict):
            raise ValueError("overrides_json must decode to a JSON object")
        return await call_launch_run_with_overrides(config_name, mode, copy_name, overrides)



    # ??? Phase 6 tools (expression eval / watch / variable write / run-to-line) ???

    @mcp.tool()
    async def debug_evaluate(expression: str, frame: int = 0) -> dict[str, Any]:
        """Evaluate a C/C++ expression in the context of a suspended stack frame.

        Returns {ok, type, value, children?} on success or {ok:false, errors|error}.
        Side-effecting expressions (e.g. function calls, assignment) actually run on
        the target ??that's why this is gate-checked.

        Examples:
          debug_evaluate("counter")            ??primitive read
          debug_evaluate("buf[3].flags & 0x80") ??struct field bitmask
          debug_evaluate("counter = 0")        ??write via assignment expression
          debug_evaluate("g_init_done()")      ??call a target function
        """
        return await call_debug_evaluate(expression, frame)

    @mcp.tool()
    async def debug_watch_list() -> dict[str, Any]:
        """List all expressions currently in the IDE's Expressions view (read-only)."""
        return await fetch_debug_watch_list()

    @mcp.tool()
    async def debug_watch_add(expression: str) -> dict[str, Any]:
        """Add a watch expression to the IDE's Expressions view.

        It auto-evaluates against the currently suspended frame (if any) and re-evaluates
        on each subsequent stop. Persists in the workspace until removed.
        """
        return await call_debug_watch_add(expression)

    @mcp.tool()
    async def debug_watch_remove(expression: Optional[str] = None,
                                 all: bool = False) -> dict[str, Any]:
        """Remove a watch expression by exact text match, or all=True to clear them all."""
        return await call_debug_watch_remove(expression, all)

    @mcp.tool()
    async def debug_variable_write(name: str, value: str, frame: int = 0) -> dict[str, Any]:
        """Modify a local variable's value in the suspended frame.

        Looks up the variable by exact name in the frame's locals. For nested struct
        fields, prefer debug_evaluate("obj.field = NEW") instead ??that goes through
        the C/C++ expression parser. Requires danger mode + suspended thread.
        """
        return await call_debug_variable_write(name, value, frame)

    @mcp.tool()
    async def debug_run_to_line(file: str, line: int,
                                skip_breakpoints: bool = False) -> dict[str, Any]:
        """Resume execution and stop at a specific line.

        file: workspace-relative path (e.g. '/myproj/src/main.c') or absolute filesystem path.
        line: 1-based line number.
        skip_breakpoints: if true, ignore intermediate breakpoints between PC and target line.

        Requires danger mode + an active CDT debug session.
        """
        return await call_debug_run_to_line(file, line, skip_breakpoints)

    @mcp.tool()
    async def debug_jump_to_line(file: str, line: int) -> dict[str, Any]:
        """Move the PC to a given source line WITHOUT running there.

        Implementation: CDT IJumpToLine. The intermediate code is NOT executed ??        the program counter is simply set. This can leave variables/registers in an
        unexpected state and is the most dangerous of the run-control operations.

        Requires danger mode.
        """
        return await call_debug_jump_to_line(file, line)

    return mcp


def main() -> None:
    create_server().run(transport="stdio")


if __name__ == "__main__":
    main()
