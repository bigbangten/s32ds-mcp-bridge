---
description: S32 Design Studio live-bridge assistant. Pass any S32DS question and the skill will answer using real workbench state.
argument-hint: <your question about S32DS>
---

User question: $ARGUMENTS

Invoke the **s32-menu-lookup** skill and follow its 5-step protocol to answer the question above. The question may be about any of:

- **UI paths** ("Rebuild Last Target 어디 있어?") → `find_command` + `list_legacy_actions` + `list_visible_menu`
- **Workbench state** ("지금 뭐 편집 중?", "어떤 프로젝트 열려있어?") → `get_state`
- **Build / problems** ("빌드해줘", "에러 뭐 있어?") → `save_all` → `build_project` → `list_problems`
- **Debug / flash safety** ("Flash해도 돼?", "Debug 시작 전 체크") → `analyze_launch_config` (never launch)
- **Environment discovery** ("어떤 디버거 설치됐어?", "Toolchain 뭐 있어?") → `s32ds_*` tools
- **Dialog/wizard guidance** ("지금 열린 대화상자 뭐야?", "Project name 뭘 입력?") → `dialogs_open` → `dialog_widgets`
- **Console tail** ("PEMicro 로그 보여줘") → `console_list` → `console_tail`

## Must do, in order

1. `mcp__s32ds__health` — sanity-check bridge is reachable.
2. `mcp__s32ds__get_state` — capture perspective, active editor, selected project.
3. Decide which tool(s) above to call based on the question.
4. If the question implies a project's build/debug/flash, ALWAYS call `mcp__s32ds__analyze_launch_config` BEFORE any triggering.
5. Answer with source cited (which tool provided the fact). Never guess from static Eclipse documentation.

## If bridge unreachable

Do NOT fall back to guessing menu paths. Instead:
- Tell the user S32DS must be running with the agent plug-in installed
- Suggest `/s32:status` to diagnose
- Suggest `/s32:setup` to install if missing
