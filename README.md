# s32ds-mcp-bridge

> Make your AI coding assistant actually know what is in your S32 Design Studio.
> Live workbench introspection, guarded debug control, MCP tools, and shared Claude Code / Codex plugin packaging.

## What This Is

AI assistants often guess from generic Eclipse documentation and point S32DS users to menus that do not exist in their installed plugin set. This bridge fixes that by installing a small Eclipse bundle inside NXP S32 Design Studio 3.5.x and exposing the live workbench over a local HTTP API.

The Python MCP server wraps that HTTP API as tools. The packaged plugin supports both Claude Code and Codex from the same `claude-plugin/` root, with skills, commands, scripts, and MCP configuration shared between clients.

## Architecture

```text
Claude Code / Codex
        |
        | MCP stdio
        v
s32ds-mcp-server (Python 3.10+)
        |
        | HTTP + bearer token, 127.0.0.1 only
        v
Eclipse bundle inside S32DS JVM
        |
        | Eclipse Workbench / CDT / DSF APIs
        v
S32 Design Studio 3.5.x
```

## Install

### Claude Code

```text
/plugin marketplace add bigbangten/s32ds-mcp-bridge
/plugin install s32
/s32:setup
```

`/s32:setup` detects the S32DS install, deploys the Eclipse bundle, restarts S32DS with the right working directory, and verifies that the bridge responds.

### Codex

```bash
codex plugin marketplace add bigbangten/s32ds-mcp-bridge --ref v0.4.2
codex plugin add s32@s32ds-mcp-bridge
```

The Codex marketplace metadata lives at `.agents/plugins/marketplace.json` and points to `./claude-plugin`. That plugin root contains both `.claude-plugin/` and `.codex-plugin/`, so Claude Code and Codex use the same implementation.

After installing, start a new Codex thread and ask it to run the S32DS setup flow, or follow [`INSTALL.md`](./INSTALL.md).

### Manual MCP Client

1. Download the latest update-site zip or bridge JAR from [Releases](https://github.com/bigbangten/s32ds-mcp-bridge/releases/latest).
2. Install the Eclipse bundle into S32DS and start S32DS once with `-clean`.
3. Install the Python server from this repo:

```bash
pip install -e ./mcp-server
```

4. Point your MCP client at:

```bash
python -m s32ds_mcp_server.server
```

### Build From Source

Requires JDK 17+ and Maven. The bridge is built with Tycho:

```bash
export MAVEN_OPTS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0 -Djdk.xml.elementAttributeLimit=0 -Djdk.xml.maxOccurLimit=0 -Djdk.xml.maxXMLNameLimit=0"
mvn -f eclipse-bridge/releng/pom.xml clean verify
```

The update site and bundle JAR are produced under:

```text
eclipse-bridge/releng/com.example.s32ds.agent.repository/target/
```

## What The AI Can Do

Core workbench:
- Health, active perspective/editor/selection, open editors, Problems view, views, perspectives, commands, menus, legacy actions, dialogs, wizards, and console output.
- Open views, switch perspectives, open files, save all, and build projects.

S32DS environment:
- Installed NXP bundles, Config Tools, debuggers, toolchains, launch configs, and launch attributes.

Guarded debug and launch:
- Analyze launch configs before running.
- Run launch configs and run temporary launch copies with in-memory attribute overrides.
- DSF-aware debug status, location, stack frames, variables, registers, memory, breakpoints, and console reads.
- Danger-gated resume, step, suspend, terminate, restart, breakpoint set/clear, memory/register writes, expression evaluation, watch expressions, variable writes, run-to-line, and jump-to-line.

Self-improving skill:
- `s32-menu-lookup` checks learned recipes before inventing workarounds.
- `/s32:learn` records verified repeatable S32DS/Eclipse/SWT/MCP lessons into `claude-plugin/skills/s32-menu-lookup/references/lessons.md`.

## Safety Model

- HTTP binds to `127.0.0.1` only.
- Every endpoint requires a bearer token generated inside the S32DS workspace.
- Mutating debug and launch operations require explicit danger enablement and expire automatically.
- The bridge uses Eclipse/CDT/DSF APIs, not screen scraping or arbitrary OS keyboard input.

## Repository Layout

```text
s32ds-mcp-bridge/
  .agents/plugins/              Codex marketplace metadata
  .claude-plugin/               Claude Code marketplace metadata
  claude-plugin/                Shared Claude Code / Codex plugin root
    .claude-plugin/
    .codex-plugin/
    .mcp.json
    commands/
    scripts/
    skills/s32-menu-lookup/
  eclipse-bridge/               Java OSGi bundle, feature, and Tycho releng
  mcp-server/                   Python MCP server
  docs/                         Architecture, security, install notes
  samples/                      Example MCP client configs
  .github/workflows/            CI and release automation
```

## Releases

Pushing a `v*.*.*` tag builds the Eclipse bundle on GitHub Actions and publishes:

- `com.example.s32ds.agent.bridge_*.jar`
- `s32ds-mcp-update-site.zip`

Latest release: <https://github.com/bigbangten/s32ds-mcp-bridge/releases/latest>

## License

MIT. See [`LICENSE`](./LICENSE).

## Contributing

For menu or UI-path issues, include `/s32:status` output and the exact question that produced the wrong answer. For run-control issues, include `/debug/status`, `/debug/location`, and the relevant console output.
