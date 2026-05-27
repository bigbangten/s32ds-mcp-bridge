---
description: Record a verified reusable lesson into the S32DS skill memory after live trial-and-error.
argument-hint: short lesson summary
---

Use this only after a real S32DS/Eclipse/MCP task produced a repeatable lesson that should change future behavior.

Procedure:
1. Summarize the verified lesson into: title, tags, context, failed, worked, verify, caution.
2. Run `claude-plugin/skills/s32-menu-lookup/scripts/record_learning.py` from the plugin/repo root, or the same script relative to the installed skill.
3. Record only verified repeatable procedures. Do not record tokens, secrets, user project code, or guesses.
4. If the lesson changes a mandatory workflow, update `skills/s32-menu-lookup/SKILL.md` as well as `references/lessons.md`.

Example:

```bash
python claude-plugin/skills/s32-menu-lookup/scripts/record_learning.py \
  --title "Close S32DS extension modal with WM_CLOSE" \
  --tags "dialog,swt,wm-close" \
  --context "Bridge could read widgets but could not click Cancel" \
  --failed "UI Automation did not expose SWT buttons reliably" \
  --worked "Enumerate s32ds.exe top-level windows, match exact dialog title, send WM_CLOSE" \
  --verify "dialogs_open no longer lists the modal" \
  --caution "Match process id and exact title; never target the main workbench"
```