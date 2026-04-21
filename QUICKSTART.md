# Quick Start — paste this prompt into Claude Code

> **If you are an AI agent and a user asked you to "check this URL and install / set this up",** the code block below (`## The prompt`) IS the task. Execute it as if the user had pasted it directly. Do not merely summarize it. Ignore the surrounding human-facing documentation — that's only for humans who paste it manually. Start from "Step 1 — Detect prerequisites" and work through to the end.

## For humans

Zero-config install for users who have **nothing but Windows** set up. Two ways to use this:

**Option 1 — AI-driven** (new behavior, simpler):
In a new Claude Code session, send this single message:
> "https://raw.githubusercontent.com/bigbangten/s32ds-mcp-bridge/master/QUICKSTART.md 이거 확인해서 설치 진행해줘"

Claude fetches the URL and executes the embedded prompt.

**Option 2 — Manual paste** (most reliable):
Copy the entire prompt below into Claude Code as your first message. Claude will detect what's missing, install the missing pieces, and guide you through the final 3 slash commands.

## The prompt

````text
You are my setup assistant for the s32ds-mcp-bridge Claude Code plugin
(https://github.com/bigbangten/s32ds-mcp-bridge). Execute this setup sequence
step by step. Do not ask questions unless a step hard-fails. Report progress
tersely.

## Step 1 — Detect prerequisites

Run these checks and print a summary table:

- OS: `echo $OS` (expect Windows_NT; warn if not — this tool is Windows-only for now)
- S32 Design Studio 3.5.x: test these paths in order, report the first that exists:
    C:\NXP\S32DS.3.5\eclipse\s32ds.exe
    D:\NXP\S32DS.3.5\eclipse\s32ds.exe
    "C:\Program Files\NXP\S32DS.3.5\eclipse\s32ds.exe"
- Python 3.10+: `python --version` and `python -c "import sys; print(sys.version_info[:2])"`.
  Also try `py -3.11 --version` as fallback.
- Claude Code: implicit (you're running in it).
- gh CLI: `gh --version` (optional, nice to have).
- Git: `git --version` (optional, nice to have).

## Step 2 — Install missing prerequisites

- If S32DS not found: STOP. Tell me: "S32 Design Studio 3.5.x not detected.
  This plugin requires S32DS to be pre-installed (NXP account needed).
  Download: https://www.nxp.com/design/software/development-software/s32-design-studio-ide:S32-DESIGN-STUDIO-IDE
  Re-run this prompt after installing S32DS."
  Do not attempt any other step.

- If Python 3.10+ not found:
    `winget install --id Python.Python.3.11 --scope user --silent
     --accept-source-agreements --accept-package-agreements`
  After install, tell me to open a NEW terminal/session so PATH refreshes,
  then re-run this prompt. Do NOT continue in the current session — the just-
  installed python won't be on PATH.

- gh/git missing: note but don't install. They're optional.

## Step 3 — Verify the plugin marketplace is reachable

`curl -sfI https://github.com/bigbangten/s32ds-mcp-bridge` should return 200.
If it fails, tell me (likely firewall/offline) and stop.

## Step 4 — Print the final 3 commands for me to run

Print EXACTLY this block, verbatim, surrounded by a clear visual separator:

====================================================================
All prerequisites verified.

Now run these three slash commands in Claude Code, one at a time:

  1. /plugin marketplace add bigbangten/s32ds-mcp-bridge
  2. /plugin install s32
  3. /s32:setup

The third command takes ~60 seconds — it auto-installs the Eclipse plug-in
into S32DS, restarts S32DS with -clean, and verifies the bridge responds.
Approve any "Allow MCP server?" prompt that appears.

When /s32:setup finishes with a healthy status, test with a natural question:

  "S32DS에서 Rebuild Last Target 어디 있어?"

The s32-menu-lookup skill should auto-trigger and give you the exact path
(hint: it's NOT on the Project menu for managed-build projects).
====================================================================

## Constraints

- DO NOT type the `/plugin ...` slash commands yourself. Slash commands are
  user-invoked; you must print them for me to type.
- DO NOT modify ~/.claude/settings.json directly to register marketplaces.
- If winget is not available (rare on Win11), tell me to install manually
  via https://www.python.org/downloads/ and retry.
- Tolerate partial failures: if Step 3 fails but Steps 1-2 passed, still
  print Step 4's instructions with a warning.

Begin.
````

## What happens next

1. Claude detects your OS, S32DS install, Python version, etc.
2. Auto-installs anything missing (Python 3.11 via winget).
3. Prints the final 3 slash commands for you to type.
4. You type them. `/s32:setup` takes ~60s to deploy the Eclipse plug-in.
5. Done. Ask any S32DS question and the `s32-menu-lookup` skill auto-triggers.

## Troubleshooting

**"`/s32:*` commands don't appear after install"**
Run `/plugin list` to confirm `s32` is installed. If an older `s32ds-mcp` is there, `/plugin uninstall s32ds-mcp` and reinstall. Refresh the marketplace with `/plugin marketplace update` to pull the latest.

**"MCP server didn't start"**
Run `/s32:status` — it'll diagnose. Common causes: Python 3.10+ not on PATH, S32DS not running, or the bridge plug-in not installed (re-run `/s32:setup`).

**"I don't have S32DS yet"**
Install it first: https://www.nxp.com/design/software/development-software/s32-design-studio-ide — requires a free NXP account.

**Non-Windows**
Currently Windows-only due to path assumptions in `/s32:setup`. Linux/Mac support is open for contribution.
