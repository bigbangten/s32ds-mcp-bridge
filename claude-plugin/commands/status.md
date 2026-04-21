---
description: Show S32DS bridge health + current workbench state in one glance.
---

Quick status check for the S32DS MCP bridge. Use when the user asks "is it working?", "connected?", or wants to see the current IDE state at a glance.

## Procedure

```bash
# Resolve bridge URL + token. Priority:
#   1. ~/.s32ds-mcp/bridge.json (discovery file, v0.1.4+)
#   2. $S32DS_WORKSPACE/.metadata/.plugins/.../token (env-specified workspace)
#   3. ~/workspaceS32DS.3.5/.metadata/.plugins/.../token (Windows default)
# We use USERPROFILE on Windows as canonical home — $USER is empty in some MinGW
# bash shells, so don't rely on it.

HOME_DIR="${USERPROFILE:-$HOME}"
DISCOVERY="$HOME_DIR/.s32ds-mcp/bridge.json"
TOKEN=""
BASE=""

if [ -f "$DISCOVERY" ]; then
  BASE=$(python -c "import json,sys; print(json.load(open(r'$DISCOVERY'))['url'])")
  TOKEN=$(python -c "import json,sys; print(json.load(open(r'$DISCOVERY'))['token'])")
else
  # Fallback: workspace-local token file (for pre-v0.1.4 bridges)
  WS="${S32DS_WORKSPACE:-$HOME_DIR/workspaceS32DS.3.5}"
  TOKEN_PATH="$WS/.metadata/.plugins/com.example.s32ds.agent.bridge/token"
  if [ ! -f "$TOKEN_PATH" ]; then
    echo "Bridge not reachable:"
    echo "  - No discovery file at $DISCOVERY"
    echo "  - No workspace token at $TOKEN_PATH"
    echo "Is S32DS running? If yes but still failing, run /s32:setup."
    exit 1
  fi
  # Windows ACL may block even owner; grant read on-demand
  if ! cat "$TOKEN_PATH" >/dev/null 2>&1; then
    USR="${USERNAME:-${USER:-$(whoami)}}"
    icacls "$(cygpath -w "$TOKEN_PATH" 2>/dev/null || echo "$TOKEN_PATH")" //grant "$USR:R" >/dev/null 2>&1
  fi
  TOKEN=$(cat "$TOKEN_PATH" | tr -d '\r\n')
  BASE="http://127.0.0.1:39231"
fi

AUTH="Authorization: Bearer $TOKEN"

# Health
HEALTH=$(curl -fs -H "$AUTH" "$BASE/health" 2>&1) || { echo "Bridge not responding at $BASE: $HEALTH"; exit 1; }
echo "$HEALTH" | jq '.data | {product: .s32dsProduct, eclipseVer: .eclipseVersion, pid: .pid, bridgeVersion, workspace}'

# State summary
curl -fs -H "$AUTH" "$BASE/state" | jq '{
  perspective: .data.activePerspective.name,
  editor: .data.activeEditor.input.path,
  projects: [.data.workspaceProjects[] | select(.accessible) | .name],
  openViews: [.data.openViews[].partName],
  dirty: .data.dirtyEditors
}'

# Problems count by severity
curl -fs -H "$AUTH" "$BASE/markers/problems" | jq '.data.bySeverity'
```

Report:
- Bridge reachable (OK or FAIL) and `bridgeVersion` (lets the user see if their S32DS-side plug-in is behind the latest published)
- S32DS product name + Eclipse version
- Active perspective + editor
- Open projects
- Problems counts by severity

If reachable but state looks empty (no active editor, no projects), note it — could indicate a fresh workspace or the user is between operations.
