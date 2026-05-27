# Architecture

The bridge has three layers:

1. An Eclipse/OSGi bundle loaded inside S32 Design Studio.
2. A localhost HTTP API with bearer-token authentication.
3. A Python MCP server and shared Claude Code / Codex plugin package.

## Eclipse Bundle

The bundle starts during S32DS workbench startup and binds an HTTP server to `127.0.0.1`.

Important components:

- `AgentEarlyStartup`: starts the bridge during S32DS startup.
- `BridgeServer`: owns the local HTTP server and discovery file.
- `Router`: request routing, JSON envelopes, auth checks, and danger-gate enforcement.
- `TokenStore`: creates and stores the workspace bearer token.
- `DiscoveryFile`: writes `%USERPROFILE%\.s32ds-mcp\bridge.json`.
- `UiThread`: runs SWT/Workbench operations on the UI thread.
- `DangerGate`: time-limited gate for mutating debug/launch operations.

Workbench and environment helpers:

- `StateInspector`, `CommandIndexer`, `ExtensionRegistryIndexer`, `MenuMaterializer`
- `ViewIndexer`, `PerspectiveIndexer`, `WizardIndexer`
- `S32dsInventory`, launch config and console helpers

Debug helpers:

- `DebugInspector`, `DebugContextPicker`, `DebugContextDiagnostics`
- `DebugController`, `BreakpointController`, `ExpressionController`, `LaunchRunner`

The debug path is CDT/DSF-aware because S32DS/PEmicro sessions often expose active stack frames as DSF view-model contexts rather than classic `IDebugTarget`/`IThread` objects.

## Python MCP Server

`mcp-server/src/s32ds_mcp_server` wraps the HTTP API as MCP resources and tools.

The client resolves bridge connection data in this order:

1. `S32DS_BRIDGE_URL` / `S32DS_BRIDGE_TOKEN` environment variables.
2. `%USERPROFILE%\.s32ds-mcp\bridge.json`.
3. Legacy workspace token-file fallback.

The shared plugin root also includes a copy of the MCP server under `claude-plugin/mcp-server` so Claude Code and Codex marketplace installs are self-contained.

## Plugin Packaging

The same plugin root supports both clients:

```text
claude-plugin/
  .claude-plugin/plugin.json
  .codex-plugin/plugin.json
  .mcp.json
  commands/
  scripts/
  skills/
  mcp-server/
```

Repository-level marketplace metadata:

- `.claude-plugin/marketplace.json` for Claude Code.
- `.agents/plugins/marketplace.json` for Codex.

## Response Envelope

HTTP responses use a consistent JSON envelope:

```json
{
  "ok": true,
  "data": {},
  "warnings": [],
  "error": null
}
```

Errors set `ok=false`, `data=null`, and include `error.code`, `error.message`, and optional details.
