---
description: Toggle the S32DS bridge danger gate. Required before any mutating debug/launch operation (step, resume, terminate, breakpoint set/clear, memory/register write, launch run).
argument-hint: on [<minutes>] | off | status
---

Control the S32DS bridge **danger gate**. While the gate is OFF, the bridge refuses every mutating operation with HTTP 403 + a hint pointing here. The gate has a TTL (default 30 minutes, max 240) and resets automatically on bridge restart — so leaving it open is short-lived by design.

## Procedure

Parse `$ARGUMENTS`:
- `on` or `on <minutes>` — open the gate. minutes optional, default 30, capped to 240.
- `off` — close immediately.
- `status` (or no args) — print current state.

Resolve bridge URL + token from the discovery file (`~/.s32ds-mcp/bridge.json`) or the workspace token fallback, exactly like `/s32:status`.

```bash
HOME_DIR="${USERPROFILE:-$HOME}"
DISCOVERY="$HOME_DIR/.s32ds-mcp/bridge.json"

if [ -f "$DISCOVERY" ]; then
  BASE=$(python -c "import json; print(json.load(open(r'$DISCOVERY'))['url'])")
  TOKEN=$(python -c "import json; print(json.load(open(r'$DISCOVERY'))['token'])")
else
  WS="${S32DS_WORKSPACE:-$HOME_DIR/workspaceS32DS.3.5}"
  TOKEN_PATH="$WS/.metadata/.plugins/com.example.s32ds.agent.bridge/token"
  [ -f "$TOKEN_PATH" ] || { echo "Bridge not reachable. Run /s32:setup first."; exit 1; }
  TOKEN=$(cat "$TOKEN_PATH" | tr -d '\r\n')
  BASE="http://127.0.0.1:39231"
fi
AUTH="Authorization: Bearer $TOKEN"
```

### `on [<minutes>]`

```bash
MINUTES="${1:-30}"
TTL_MS=$(( MINUTES * 60 * 1000 ))
RESPONSE=$(curl -fs -X POST -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"ttlMs\": $TTL_MS}" "$BASE/danger/enable")
echo "$RESPONSE" | jq '.data | {enabled, remainingMinutes: (.remainingMs / 60000 | floor), expiresAtEpochMs}'
```

Print a clear "DANGER MODE ON for N minutes" line. Warn the user that step/resume/breakpoint/memory-write/register-write/launch-run are now permitted.

### `off`

```bash
curl -fs -X POST -H "$AUTH" "$BASE/danger/disable" | jq '.data'
echo "DANGER MODE OFF."
```

### `status` (default)

```bash
curl -fs -H "$AUTH" "$BASE/danger/state" | jq '.data | {
  enabled,
  remainingMinutes: (.remainingMs / 60000 | floor),
  expiresAtEpochMs,
  defaultTtlMinutes: (.defaultTtlMs / 60000 | floor),
  maxTtlHours: (.maxTtlMs / 3600000 | floor)
}'
```

## Reporting

- After `on`: list which operations the user has just enabled (step/resume/suspend/terminate/restart, breakpoint set/clear, memory write, register write, launch run). Mention the auto-expiry time so they know it'll close itself.
- After `off`: confirm closure.
- For `status` while OFF: show that "no mutating ops are allowed; run `/s32:danger on` to permit them".

## Notes

- The gate is **in-memory only**. Bridge restart = OFF. This is intentional — write privileges shouldn't survive across IDE restarts silently.
- Read endpoints (`/state`, `/debug/status`, `/debug/registers`, `/debug/memory`, etc.) work regardless of gate state. Only mutators are gated.
- If a mutator returns `DANGER_OFF`, the model can re-prompt the user "danger mode is off — should I run `/s32:danger on`?" rather than calling the tool directly.
