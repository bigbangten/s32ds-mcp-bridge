---
description: Show S32DS bridge health + current workbench state in one glance.
---

Quick status check for the S32DS MCP bridge. Use when the user asks "is it working?", "connected?", or wants to see the current IDE state at a glance.

## Procedure

```bash
# Resolve token
TOKEN_PATH="$HOME/workspaceS32DS.3.5/.metadata/.plugins/com.example.s32ds.agent.bridge/token"
[ -f "$TOKEN_PATH" ] || TOKEN_PATH="/c/Users/$USER/workspaceS32DS.3.5/.metadata/.plugins/com.example.s32ds.agent.bridge/token"
if ! [ -f "$TOKEN_PATH" ]; then
  echo "Token file not found. S32DS may not be running, or the plugin isn't installed."
  echo "Run /s32:setup to install."
  exit 1
fi
# Apply ACL read grant if bash can't read
cat "$TOKEN_PATH" >/dev/null 2>&1 || icacls "$(cygpath -w "$TOKEN_PATH")" //grant "$USER:R" >/dev/null 2>&1
TOKEN=$(cat "$TOKEN_PATH" | tr -d '\r\n')
AUTH="Authorization: Bearer $TOKEN"
BASE="http://127.0.0.1:39231"

# Health
HEALTH=$(curl -fs -H "$AUTH" "$BASE/health" 2>&1) || { echo "Bridge not responding: $HEALTH"; exit 1; }
echo "$HEALTH" | jq '.data | {product: .s32dsProduct, eclipseVer: .eclipseVersion, pid: .pid, workspace}'

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
- Bridge reachable (OK or FAIL)
- S32DS product name + Eclipse version
- Active perspective + editor
- Open projects
- Problems counts by severity

If reachable but state looks empty (no active editor, no projects), note it — could indicate a fresh workspace or the user is between operations.
