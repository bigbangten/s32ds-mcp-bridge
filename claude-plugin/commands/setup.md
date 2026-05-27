---
description: One-shot installer for the S32 Design Studio bridge plug-in. Detects S32DS install, installs the bundle JAR, registers it with Eclipse, restarts S32DS, verifies the bridge responds.
argument-hint: [--s32ds-path <path>] [--rebuild] [--no-restart]
---

Install the `com.example.s32ds.agent.bridge` Eclipse plug-in into the user's S32 Design Studio so the `/s32` commands work.

This is a **non-trivial, mildly invasive install**. Communicate clearly at each step. If anything feels wrong, stop and ask.

## Preconditions the AI must verify BEFORE touching anything

1. S32 Design Studio 3.5.x is installed (any update level).
2. The user is on Windows (current build supports Windows only; Linux would need path tweaks).
3. The user has write access to the S32DS install directory (no admin prompt required).
4. A JDK ≥ 17 is available **only if rebuilding from source**. If a prebuilt JAR is present (via GitHub Release or `releases/` in repo), no JDK needed.

## Installation procedure

### Step 1 — Locate S32DS

Scan standard install locations, in order:
- `C:\NXP\S32DS.3.5\eclipse`
- `C:\NXP\S32DS.3.5\*\eclipse`
- `D:\NXP\S32DS.3.5\eclipse`
- `C:\Program Files\NXP\S32DS.3.5\eclipse`

Confirm presence of `eclipsec.exe`, `plugins/`, `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`. Record the path as `$S32DS_ECLIPSE`.

If the user passed `--s32ds-path`, use that instead.

If none found: ask the user for the path. Do not proceed without a valid one.

### Step 2 — Locate or obtain the plug-in JAR

Priority order:

**1. Prebuilt JAR in repo** — `${CLAUDE_PLUGIN_ROOT}/../eclipse-bridge/releng/com.example.s32ds.agent.repository/target/repository/plugins/com.example.s32ds.agent.bridge_*.jar`.

**2. GitHub Release asset** — the preferred path for end users who didn't clone the repo:

```bash
DEST=$HOME/.cache/s32ds-mcp-bridge
mkdir -p "$DEST"

# Prefer gh CLI (no auth needed for public repos when using --repo flag)
if command -v gh >/dev/null 2>&1; then
  gh release download --repo bigbangten/s32ds-mcp-bridge \
    --pattern 'com.example.s32ds.agent.bridge_*.jar' \
    --dir "$DEST" --clobber
fi

# Curl fallback when gh CLI is not available
if [ -z "$(ls "$DEST"/com.example.s32ds.agent.bridge_*.jar 2>/dev/null)" ]; then
  LATEST_URL=$(curl -s https://api.github.com/repos/bigbangten/s32ds-mcp-bridge/releases/latest \
    | grep '"browser_download_url"' \
    | grep 'com.example.s32ds.agent.bridge_' \
    | head -1 | sed 's/.*"\(https[^"]*\)".*/\1/')
  [ -n "$LATEST_URL" ] && curl -sL -o "$DEST/$(basename "$LATEST_URL")" "$LATEST_URL"
fi

JAR=$(ls "$DEST"/com.example.s32ds.agent.bridge_*.jar 2>/dev/null | sort -V | tail -1)
[ -z "$JAR" ] && echo "ERROR: failed to download JAR from GitHub Release" && exit 1
```

**3. Build from source** — only if `--rebuild` passed or nothing above succeeded. Requires JDK ≥ 17 and Maven; invoke `scripts/build_bridge.sh` and pass `-Djdk.xml.maxGeneralEntitySizeLimit=0` etc. (see that script).

Record resolved JAR path as `$JAR` and its version qualifier (from filename `com.example.s32ds.agent.bridge_<VERSION>.jar`) as `$VER`.

### Step 3 — Close S32DS

Ask the user to close S32DS if it's running, then verify:
```bash
tasklist //FI "IMAGENAME eq s32ds*" | grep -q s32ds.exe && taskkill //IM s32ds.exe //F
# wait 3s, confirm gone
```

Remove the workspace lock if present:
```bash
rm -f "$HOME/workspaceS32DS.3.5/.metadata/.lock"
```

### Step 4 — Install the bundle

```bash
# Remove any previous version (clean slate)
rm -f "$S32DS_ECLIPSE/plugins/com.example.s32ds.agent.bridge_"*.jar
rm -f "$S32DS_ECLIPSE/dropins/com.example.s32ds.agent.bridge_"*.jar

# Copy the JAR into plugins/ AND dropins/ (belt-and-braces)
mkdir -p "$S32DS_ECLIPSE/dropins"
cp "$JAR" "$S32DS_ECLIPSE/plugins/"
cp "$JAR" "$S32DS_ECLIPSE/dropins/"

# Register in bundles.info so OSGi simpleconfigurator starts it
BI="$S32DS_ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
# Strip previous entry
grep -v "com\.example\.s32ds\.agent\.bridge" "$BI" > "$BI.new"
mv "$BI.new" "$BI"
# Append new entry with the exact version from the JAR filename
echo "com.example.s32ds.agent.bridge,${VER},plugins/com.example.s32ds.agent.bridge_${VER}.jar,4,true" >> "$BI"
```

### Step 5 — Start S32DS with `-clean`

`-clean` forces OSGi to rescan bundles (required after installing a new plugin):
```bash
powershell -NoProfile -Command \
  "Start-Process -FilePath '$S32DS_ECLIPSE\s32ds.exe' -ArgumentList '-clean' -WorkingDirectory '$S32DS_ECLIPSE'"
```

### Step 6 — Wait for the bridge to come up

Poll up to 120 seconds for:
```bash
HOME_DIR="${USERPROFILE:-$HOME}"
# Prefer the discovery file the bridge plug-in publishes (v0.1.4+)
DISCOVERY="$HOME_DIR/.s32ds-mcp/bridge.json"
TOKEN_PATH="$HOME_DIR/workspaceS32DS.3.5/.metadata/.plugins/com.example.s32ds.agent.bridge/token"
while [ ! -f "$DISCOVERY" ] && [ ! -f "$TOKEN_PATH" ] && [ "$((SECONDS))" -lt 120 ]; do sleep 2; done
```

### Step 7 — Fix token ACL

The plugin's TokenStore tightens ACL to owner-only but the resulting ACL blocks even the owner's bash from reading. Only needed if you fell back to the workspace-local path (the discovery file has normal ACL):
```bash
# USERPROFILE/USERNAME are Windows-native; $USER can be empty in MinGW bash.
USR="${USERNAME:-${USER:-$(whoami)}}"
icacls "$(cygpath -w "$TOKEN_PATH")" //grant "$USR:R"
```

### Step 8 — Verify

```bash
# Prefer discovery file (no ACL hassle); fall back to workspace token
if [ -f "$DISCOVERY" ]; then
  TOKEN=$(python -c "import json; print(json.load(open(r'$DISCOVERY'))['token'])")
  BASE=$(python -c "import json; print(json.load(open(r'$DISCOVERY'))['url'])")
else
  TOKEN=$(cat "$TOKEN_PATH" | tr -d '\r\n')
  BASE="http://127.0.0.1:39231"
fi
curl -fs -H "Authorization: Bearer $TOKEN" "$BASE/health" | jq .
```
Expect `ok: true` and `data.workbenchRunning: true`.

### Step 9 — Report

Tell the user:
- Install path
- Plugin version installed
- How to call `/s32 status` to verify any time
- Note: S32DS from now on always starts with the bridge. Plain restarts (no `-clean`) are fine after this first install.

## Failure handling

If any step fails, roll back safely:
- **Step 4 failure (file locked)** → S32DS still running; kill and retry.
- **Step 6 timeout** → token file never appeared. Check `$HOME/workspaceS32DS.3.5/.metadata/.log` for "agent" or "bridge". Most common cause: `bundles.info` version mismatch with JAR MANIFEST `Bundle-Version`. Verify both match the qualifier in the filename.
- **Step 8 401 Unauthorized** → token read is picking up a previous token; make sure no stale `TOKEN` env var.
- **Step 8 connection refused** → plugin not started. Re-check Eclipse log for our bundle ID `com.example.s32ds.agent.bridge`.

Print clear diagnostic output at every failure. Do not silently swallow errors.

## Post-install

Tell the user about the companion commands:
- `/s32 status` — check health any time
- `/s32 menu "X"` — find any UI path
- `/s32 view "X"` — open a view
- Skill `s32-menu-lookup` auto-activates when they ask S32DS UI questions
