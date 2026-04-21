---
description: Find the exact UI path of an S32 Design Studio command/view/menu by live workbench introspection. Verifies against the running IDE instead of guessing.
argument-hint: <keyword>
---

Look up where `$ARGUMENTS` lives in the running S32 Design Studio. Always follow the `s32-menu-lookup` skill's 5-step protocol — do NOT answer from Eclipse documentation or memory.

**Required steps before answering:**

1. `mcp__s32ds__get_state` — capture active perspective + project (for nature inference).
2. Read the active project's `.project` file to determine natures (Managed Build vs Standard Make).
3. `mcp__s32ds__find_command("$ARGUMENTS")` — find the command id and its `enabledNow` state.
4. For each promising hit, verify location in ALL three places:
   - `mcp__s32ds__list_registry_menus` → main menu contributions
   - `mcp__s32ds__list_visible_menu` with relevant `menu:*` URIs → currently visible
   - `mcp__s32ds__list_legacy_actions` → popup menus (right-click) and legacy action sets
5. Report the exact UI path with the source tool cited. If nothing matches in any of A/B/C, say "not found in this S32DS configuration / current perspective" — never guess.

**Do not:**
- Guess from generic Eclipse docs.
- Pretend a menu exists if no tool confirmed it for the current project type.
- Suggest keyboard shortcuts from memory unless confirmed via live query.

If the bridge is unreachable, tell the user to run `/s32:status` or `/s32:setup`.
