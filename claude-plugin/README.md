# s32 plugin

This directory is the shared plugin root for Claude Code and Codex.

It contains:

- `.claude-plugin/plugin.json` for Claude Code
- `.codex-plugin/plugin.json` for Codex
- `.mcp.json` for Codex MCP registration
- `commands/` for `/s32:*` workflows
- `skills/s32-menu-lookup/` for live S32DS verification and learned recipes
- `scripts/bootstrap_and_run.py` for starting the Python MCP server

## Claude Code Install

```text
/plugin marketplace add bigbangten/s32ds-mcp-bridge
/plugin install s32
/s32:setup
```

## Codex Install

```bash
codex plugin marketplace add bigbangten/s32ds-mcp-bridge --ref v0.4.3
codex plugin add s32@s32ds-mcp-bridge
```

Start a new Codex thread after installing so the new skill and MCP tools are loaded.

## Setup

Run `/s32:setup` where slash commands are available, or follow the repo-level [`INSTALL.md`](../INSTALL.md). Setup installs the Eclipse bundle into S32DS, restarts S32DS once with `-clean`, and verifies the local bridge.

## Safety

The bridge binds to `127.0.0.1` and requires a bearer token. Mutating debug/launch operations are blocked until danger mode is explicitly enabled and expire automatically.
