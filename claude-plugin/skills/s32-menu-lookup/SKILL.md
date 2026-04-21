---
name: s32-menu-lookup
description: Use whenever the user asks about any S32 Design Studio (S32DS / Eclipse / CDT) UI element, build, debug, or workbench state. Triggers on questions like "X 메뉴 어디 있어?", "Y 뷰 어떻게 열어?", "Rebuild Last Target 경로", "이 프로젝트 빌드해줘", "flash 해도 돼?", "지금 Problems 뭐 있어?", "PEMicro 연결 안 된다". Mandatory before answering any S32DS UI-path, build, debug, or environment question — prevents the AI from directing the user to non-existent menus or suggesting a debug/flash when the binary doesn't exist.
---

# S32 Design Studio Live Assistance Protocol

## Why this skill exists

S32DS's UI is **dynamic**: visible menus depend on the installed plugin combination, active perspective, selected project's natures (Managed Build vs Standard Make), current selection, and legacy `actionSet` visibility rules. Debug/flash actions depend on the latest build output and target connectivity. Problems view changes after every build. Static Eclipse documentation describes a canonical IDE, not this user's S32DS. The only reliable source of truth is the live workbench.

A local HTTP bridge runs inside the user's S32DS process at `http://127.0.0.1:39231` with bearer-token auth. It's wrapped as MCP tools exposed by the `s32ds` MCP server (see `mcp__s32ds__*` tools when this plugin's MCP server is registered). Always use those tools — do not answer from memory.

## Decision tree — which tools to call for which question

| User asks | First tool | Follow-ups |
|---|---|---|
| "X 메뉴 어디 있어?" / "Y 명령 어떻게 실행?" | `get_state` | `find_command(keyword)`, `list_registry_menus`, `list_legacy_actions`, `list_visible_menu(locationUri)` |
| "Y 뷰 열어줘" / "닫았는데 다시 켜" | `list_views` | `show_view(viewId)` |
| "Z perspective로 바꿔줘" | `list_perspectives` | `switch_perspective(perspectiveId)` |
| "이 프로젝트 빌드해" | `get_state` (어떤 프로젝트) | `save_all()` → `build_project(name, kind)` → `list_problems(name)` |
| "Problems 보여줘" | `list_problems()` | sort by severity |
| "Flash해도 돼?" / "Debug 시작해도 돼?" | `list_launch_configs` | `analyze_launch_config(name)` — 절대 실행하지 말고 결과만 보고 |
| "뭐 설치돼 있어?" / "어떤 toolchain?" | `s32ds_inventory`, `s32ds_toolchains`, `s32ds_debuggers`, `s32ds_config_tools` | |
| "디버그 중인데 이상해" | `debug_sessions` → `debug_breakpoints` → `debug_stackframes` → `debug_variables(frame)` | |
| "지금 열린 대화상자 뭐야?" | `dialogs_open` | `dialog_widgets(index)` — 필드 값만 읽기, 절대 버튼 클릭 가이드만 |
| "PEMicro 연결 로그" / "Console 내용" | `console_list` | `console_tail(name="PEMicro")` |
| "파일 열어줘" | `open_file(path)` | |

## Mandatory protocol — DO NOT SKIP

### For any UI-path question (menu/view/command):

**Step 1 — Snapshot live context** with `get_state`. Capture: `activePerspective.id`, `activeEditor.input.path`, `workspaceProjects`, `selection`.

**Step 2 — If a project is in scope, infer its natures** from `.project` file:
- `org.eclipse.cdt.managedbuilder.core.managedBuildNature` → Managed Build (no "Make Target" submenu)
- `org.eclipse.cdt.make.core.makeNature` → Standard Make
Nature determines which CDT menus apply.

**Step 3 — Find the command** with `find_command(keyword)`. Prefer modern `org.eclipse.*` IDs over `AUTOGEN:::` legacy entries. Check `enabledNow` — if false, explain required context to enable.

**Step 4 — Locate it in the UI (check ALL three paths):**
- **A — Menu bar:** `list_registry_menus` + `list_visible_menu("menu:project")` / `"menu:window"` / etc.
- **B — Context menus (right-click):** `list_legacy_actions` → scan `popupMenus` whose `objectClass` matches current selection type (e.g. `org.eclipse.core.resources.IContainer` = project/folder).
- **C — Legacy actionSets:** `list_legacy_actions` → `actionSets[].menubarPath` — but watch the `visibleWhen` condition and project nature.

**Step 5 — Answer with source cited.** If found: give the exact path + cite which tool returned it. If not found after A/B/C: say "no visible path in this S32DS install / current perspective" — do NOT guess.

### For build/debug/flash questions:

**Before triggering anything destructive-ish:**
1. `get_state` — confirm project
2. `analyze_launch_config(name)` if about debug/flash — check `.elf` exists, device matches, risk level
3. If user wants a BUILD: `save_all` → `build_project(name, kind)` → `list_problems(name)` and report severity counts
4. Never call `post_*` for destructive actions without the user's explicit ask in THIS turn.

### For dialog/wizard guidance:

1. `dialogs_open` — is a dialog visible?
2. If yes, `dialog_widgets(index)` — read field values, button labels
3. Tell the user: "In the current dialog, [field X] should be [value Y] because [reasoning]. Press [Button Z]."
4. Never pretend you clicked anything. Read + advise only.

## Anti-patterns (real failures that cost trust)

1. **Saying "Project > Make Target > Rebuild Last Target"** for a Managed Build project. The submenu doesn't exist there — Step 2 (natures) would have caught this.

2. **Answering "Window > Show View > Other..."** when the view is already open. `get_state` lists open views — if the target is there, tell the user which existing tab to click.

3. **Quoting keyboard shortcuts from memory.** Bindings can be perspective-sensitive or remapped. Only quote if confirmed via actual live query.

4. **Giving a path for a command that's `enabledNow:false`.** Tell the user what selection/condition is required to enable it.

5. **Suggesting "just hit flash" without `analyze_launch_config` first.** The binary might not exist, the project might be closed, the device might not match. One 50ms tool call prevents real damage.

## Common S32DS IDs (reference — always verify via tools first)

Perspectives (NXP Config Tools):
- Pins → `com.nxp.swtools.mux.perspective`
- Clocks → `com.nxp.swtools.clocks.clocks.perspective`
- Peripherals → `com.nxp.swtools.periphs.gui.perspective`
- DCD → `com.nxp.swtools.dcd.perspective`
- IVT → `com.nxp.swtools.ivt.perspective`
- DDR → `com.nxp.swtools.ddr.perspective`
- eFUSE → `com.nxp.swtools.efuse.gui.perspective`
- GTM → `com.nxp.swtools.gtm.gui.perspective`
- QuadSPI → `com.nxp.swtools.quadspi.perspective`

Standard Eclipse:
- C/C++ → `org.eclipse.cdt.ui.CPerspective`
- Debug → `org.eclipse.debug.ui.DebugPerspective`
- Tasks view → `org.eclipse.ui.views.TaskList`
- Problems view → `org.eclipse.ui.views.ProblemView`
- Console view → `org.eclipse.ui.console.ConsoleView`

S32DS command prefixes:
- `com.freescale.core.ide.commanderView.*` — Commander view actions (build, debug, clean)
- `com.nxp.swtools.*.commands.*` — Config Tool commands
- `org.eclipse.cdt.managedbuilder.ui.*` — Managed Build actions
- `org.eclipse.cdt.make.ui.*` — Standard Make actions (may be context-menu only in managed builds)

## If the bridge isn't available

If MCP tools return connection errors or the `s32ds` MCP server isn't registered:
1. Tell user to run `/s32:status` to diagnose
2. If S32DS isn't running, they need to start it
3. If the plugin isn't installed, direct to `/s32:setup`
4. Do NOT fall back to guessing menu paths — refuse the question and explain why.
