# s32ds-mcp (Claude Code plugin)

This directory is the **Claude Code plugin** part of [s32ds-mcp-bridge](../). It packages:

- a skill (`s32ds-menu-lookup`) that forces the AI to verify S32DS UI paths against the live workbench;
- slash commands (`/s32`, `/s32:setup`, `/s32:status`) that drive the MCP bridge;
- an MCP server registration (via `plugin.json`) that auto-starts the Python wrapper.

## Install

From Claude Code:

```
/plugin marketplace add bigbangten/s32ds-mcp-bridge
/plugin install s32
```

Then run once:

```
/s32:setup
```

This detects your S32DS install, deploys the Eclipse bundle, restarts S32DS, and verifies the bridge responds.

## After install

- `/s32 status` — quick health + current perspective/editor
- `/s32 menu "<keyword>"` — find where any command lives in the UI (via live workbench, not documentation)
- `/s32 view <name>` — open a closed view
- `/s32 perspective <name>` — switch perspective
- `/s32 problems` — dump Problems view

Any time you ask a free-form S32DS UI question, the `s32ds-menu-lookup` skill auto-activates and follows its 5-step verification protocol. This is the design goal — no more "click the menu that doesn't exist".

## Why this plugin exists

Every AI assistant confidently points users to S32DS menus that don't exist in their specific install. This plugin replaces the guessing with live introspection so the AI has actual ground truth.

See the [top-level README](../README.md) for architecture and the motivating story.
