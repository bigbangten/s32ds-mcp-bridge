# Security Model

## Boundaries

- The bridge binds only to `127.0.0.1`.
- Every endpoint requires `Authorization: Bearer <token>`.
- The token is generated inside the S32DS workspace and mirrored into `%USERPROFILE%\.s32ds-mcp\bridge.json` for local MCP discovery.
- No endpoint exposes arbitrary shell execution.

## Token Storage

Primary token path:

```text
<workspace>/.metadata/.plugins/com.example.s32ds.agent.bridge/token
```

Discovery path:

```text
%USERPROFILE%/.s32ds-mcp/bridge.json
```

The token is generated with `SecureRandom` and encoded as URL-safe Base64. On Windows, the bridge attempts to tighten ACLs to the current user. If ACL tightening fails, startup continues and logs the problem.

## Mutating Operations

Read-only inspection tools are always available with a valid token.

`debug_snapshot` is also read-only and does not require the danger gate. Its parser accepts only identifiers, numeric array indexes, and field access. Calls, assignments, arithmetic, casts, and pointer dereferences are rejected before the debugger evaluates anything.

Operations that can change the target, debugger state, launch state, memory, registers, breakpoints, or watch expressions require the time-limited danger gate. Examples:

- launch run / run with overrides
- resume, step, suspend, terminate, restart
- breakpoint set/clear
- memory/register writes
- expression evaluation with side effects
- variable writes
- run-to-line and jump-to-line

The MCP skill and command docs instruct agents to enable danger mode only after an explicit user request in the current turn.

When multiple live debug launches exist, selected run-control operations require an unambiguous launch/session selector. Background suspend/resume keeps UI-command fallback disabled by default so an NXP SVD view cannot be activated as a side effect.

## Network Scope

Allowed:

```text
http://127.0.0.1:<port>
```

Forbidden by design:

```text
0.0.0.0
LAN/WAN interfaces
Unauthenticated public HTTP
```

## Filesystem Scope

The bridge reads S32DS workspace metadata and Eclipse workbench state. It does not provide arbitrary file write APIs. File opening and editor saves go through Eclipse workbench APIs.
