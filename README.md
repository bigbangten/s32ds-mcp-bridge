# s32ds-mcp-bridge

> **Make your AI coding assistant actually know what's in your S32 Design Studio.**
> Live workbench introspection + MCP server + Claude Code plugin.

## What this is

If you've used an AI coding assistant (Claude Code, Codex, Copilot, etc.) with **NXP S32 Design Studio**, you've probably seen this:

> AI: "Click **Project → Make Target → Rebuild Last Target**"
> You: *(right-click, no such submenu)*
> You: "that menu doesn't exist"
> AI: "Let me try… **Window → Preferences → …**"

The AI is guessing from generic Eclipse documentation. Your S32DS install has a specific combination of plugins, perspectives, project natures, and visibility rules that no amount of training data captures.

**This project fixes that.** It installs a small Eclipse plug-in inside S32DS that exposes the **live** Workbench state (commands, menus, views, perspectives, problems, active editor, selection) over a local HTTP API. An MCP server wraps that API so AI clients can query it as tools. A Claude Code plugin bundles the whole thing with a skill that *forces* the AI to verify before answering.

## Architecture

```
┌──────────────────────┐    MCP (stdio)    ┌──────────────────────┐
│  Claude Code / Codex │ ◀──────────────── │ s32ds-mcp-server     │
└──────────────────────┘                   │ (Python 3.10+)       │
                                           └──────────┬───────────┘
                                                      │ HTTP + Bearer
                                                      ▼
                                           ┌──────────────────────┐
                                           │ Eclipse bundle       │
                                           │ bound to 127.0.0.1   │
                                           │ inside S32DS JVM     │
                                           └──────────┬───────────┘
                                                      │ ICommandService
                                                      │ IExtensionRegistry
                                                      │ IMenuService
                                                      │ IWorkbenchPage
                                                      ▼
                                           ┌──────────────────────┐
                                           │ S32 Design Studio    │
                                           │ 3.5.x (Eclipse 4.20) │
                                           └──────────────────────┘
```

## Install paths

Choose based on your AI client:

### Path A₀ — Zero-config (recommended for newcomers)

Don't have Python / haven't installed anything yet? Paste the [**QUICKSTART prompt**](./QUICKSTART.md) into Claude Code as your first message. Claude will detect missing prereqs, install them via `winget`, and guide you through the final 3 slash commands. Works from a fresh Windows install.

### Path A — Claude Code users (already have Python 3.10+)

```
/plugin marketplace add bigbangten/s32ds-mcp-bridge
/plugin install s32
/s32:setup
```

`/s32:setup` detects your S32DS, installs the Eclipse plug-in, restarts S32DS, verifies the bridge responds. No manual copying.

### Path B — Codex / other MCP clients

1. Download the latest Eclipse plug-in JAR from [Releases](./releases).
2. Install into S32DS: `Help → Install New Software → Add → Archive…` (pick the update-site zip), OR drop the single JAR into `<S32DS>\eclipse\dropins\` and start S32DS with `-clean` once.
3. `pip install s32ds-mcp-server` (from `mcp-server/` directory of this repo).
4. Point your MCP client at `mcp-server` via the sample config in `samples/codex_config_stdio.toml`.

### Path C — Build from source

Requires JDK ≥ 17 and Maven. The bridge must be built with Tycho:

```bash
export MAVEN_OPTS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0"
mvn -f eclipse-bridge/releng/pom.xml clean verify
```

Output JAR at `eclipse-bridge/releng/com.example.s32ds.agent.repository/target/repository/plugins/com.example.s32ds.agent.bridge_*.jar`.

## What the AI can now do

With the plugin installed, slash commands become available:

**Core workflow**
- `/s32 status` — bridge alive? current perspective / editor / problems count
- `/s32 menu "<keyword>"` — exact UI path for any command, via live introspection
- `/s32 state` — full workbench snapshot
- `/s32 view <name>` — open a view by name (Tasks, Problems, Console, Pins, …)
- `/s32 perspective <name>` — switch perspective (C/C++, Debug, Clocks, …)

**Build + diagnose**
- `/s32 build [project] [kind]` — save → build → report problems
- `/s32 problems [project]` — grouped errors/warnings

**Guardrails (read-only safety)**
- `/s32 flash-check <config>` — pre-flight check for Flash/Debug launch (never launches)
- `/s32 debug-state` — active debug session + breakpoints + suspended frame
- `/s32 dialogs` — currently open dialogs/wizards (for guidance, never clicks)
- `/s32 console [name] [lines]` — tail Eclipse Console (build/PEmicro/RTT)

**Environment discovery**
- `/s32 inventory` — installed NXP bundles + Config Tools + debuggers + toolchains

**Setup**
- `/s32:setup` — detect S32DS, install plug-in, restart, verify
- `/s32:status` — detailed health snapshot

The included **`s32ds-menu-lookup` skill** auto-triggers on any S32DS-related question, forcing the AI to verify against live state before answering (instead of guessing from generic Eclipse documentation).

## What it does NOT do (yet)

- **Write actions** beyond `show-view` and `switch-perspective`. No build/clean/debug/flash triggers yet — by design (Phase 1 is read-heavy + safe writes only).
- **Linux or macOS**. Build targets Eclipse 2021-06 which is cross-platform, but the installer scripts assume Windows paths. PRs welcome.
- **Key binding discovery**. `org.eclipse.ui.bindings` extension isn't indexed yet. On the roadmap.

## Repository layout

```
s32ds-mcp-bridge/
├── eclipse-bridge/              ← Java/OSGi plug-in, built with Tycho
│   ├── bundles/                 ← the actual plug-in source
│   ├── features/                ← p2 feature wrapper
│   └── releng/                  ← Tycho target platform + update site
├── mcp-server/                  ← Python FastMCP server that wraps the HTTP API
├── claude-plugin/               ← Claude Code plugin (skill + /s32* commands)
│   ├── .claude-plugin/
│   ├── skills/s32ds-menu-lookup/
│   ├── commands/
│   └── scripts/
├── samples/                     ← example MCP client configs
├── docs/                        ← architecture, security, install detail
├── scripts/                     ← helper scripts (build_bridge.sh etc.)
└── .github/workflows/           ← CI: Tycho build + release automation
```

## How the AI reads this repo to self-install

An AI agent (including Claude Code running this plugin) can install everything by following this recipe:

1. Read [`INSTALL.md`](./INSTALL.md) — the deterministic step-by-step.
2. Run the Claude Code slash command `/s32:setup` (full source in `claude-plugin/commands/s32:setup.md`).
3. Verify with `/s32:status` or raw `curl http://127.0.0.1:39231/health`.

The installer is idempotent: repeated runs overwrite the installed JAR safely.

## Security model

- Bridge binds to `127.0.0.1` only — never exposed to the network.
- Bearer token (32-byte random) generated on first run, stored at `<workspace>/.metadata/.plugins/com.example.s32ds.agent.bridge/token` with owner-only ACL.
- All endpoints require `Authorization: Bearer <token>`.
- Writes limited to `show-view` and `switch-perspective`; no arbitrary command execution, no filesystem writes, no debug/flash actions.
- Full policy in [`docs/security_model.md`](./docs/security_model.md).

## License

MIT — see [`LICENSE`](./LICENSE).

## Contributing

- Issues and PRs welcome. For menu-lookup bugs, please include the output of `/s32:status` and the exact question that produced the wrong answer.
- Phase 2+ (build/debug/flash safe wrappers) is a design discussion before code. Open an issue first.
