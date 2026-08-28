# S32DS Learned Recipes

Compact memory for verified S32DS/Eclipse/SWT/MCP procedures learned from real trial-and-error. Search this file before inventing another workaround.

Record only repeatable behavior that changes future execution. Do not record bearer tokens, secrets, one-off user project code, or unverified guesses.

## 2026-05-25 - Close S32DS Extensions and Updates modal without a bridge click endpoint
Tags: dialog, swt, ui-automation, wm-close
Context: S32DS 3.5 bridge `/dialogs/open` listed a visible modal titled `S32DS Extensions and Updates`; `/dialogs/3/widgets` showed an enabled `Cancel` button, but the bridge only reads widgets and has no click endpoint.
Failed: A first Windows UI Automation attempt used `$pid`, which conflicts with PowerShell's read-only `$PID`. After fixing that, SWT push buttons still were not exposed as `ControlType.Button` elements, so invoking `Cancel` through UIA was unreliable.
Worked: Enumerate Win32 top-level windows for the `s32ds.exe` process, match the exact title `S32DS Extensions and Updates`, and send `WM_CLOSE` (`0x0010`) to that handle with `user32.dll SendMessage`.
Verify: Re-query `/dialogs/open`; the modal title should be gone while the main `workspaceS32DS.3.5 - S32 Design Studio for S32 Platform` shell remains visible.
Caution: Match both process id and exact window title. Never broadcast `WM_CLOSE` and never target the main workbench shell.

## 2026-05-25 - Close S32DS new-module notification popup
Tags: dialog,swt,notification,wm-close
Context: After S32DS restart, `/dialogs/open` may list a visible child shell with empty title, bounds about `271x116`, and `/dialogs/{i}/widgets` contains the label `New module available notification dialog`.
Failed: Title matching alone cannot target it because the shell text is empty, and it is not the same as the hidden `PartRenderingEngine's limbo` shell.
Worked: Enumerate visible top-level/owned windows for the `s32ds.exe` process, match empty title plus the small notification-sized rectangle, and send `WM_CLOSE` only to that handle.
Verify: `/dialogs/open` should then show only the main visible workbench shell plus hidden/non-visible limbo shells.
Caution: Also verify the widget label before using the bounds heuristic in a new S32DS version; do not close large empty-title shells.

## 2026-05-25 - Use active debug context when run-control cannot find a suspended thread
Tags: debug,run-control,cdt,dsf,debug-context
Context: S32DS UI showed a stopped stack frame such as main() at main.c:172, but bridge run-control selected targets only through launch target/thread isSuspended flags and returned no suspended thread/target.
Failed: Relying only on IDebugTarget.isSuspended() and IThread.isSuspended() can miss the IDE-selected stack frame in S32DS/CDT/DSF, and sending F8 through Windows foreground focus is unreliable from Codex.
Worked: Resolve DebugUITools.getDebugContext() first, derive frame/thread/target from the active IStackFrame, then fall back to launch scans; if direct ISuspendResume/IStep calls are not available, execute Eclipse debug UI commands such as org.eclipse.debug.ui.commands.Resume on the workbench UI thread.
Verify: Build the Tycho plugin, install the JAR, restart S32DS with -clean, then confirm DebugContextPicker classes are in the installed JAR and /health plus /debug/status respond.
Caution: Keep danger gate enforcement in Router; only use UI command fallback for explicit user-requested mutating debug actions, and do not depend on OS foreground keyboard focus.

## 2026-05-25 - Keep Eclipse bundles.info UTF-8 no BOM and aligned with installed bridge JAR
Tags: install,eclipse,bundles-info,encoding,bridge
Context: After manually editing S32DS simpleconfigurator bundles.info to disable a failing IVT bundle and installing a new bridge JAR, S32DS showed a fatal dialog pointing to configuration log.
Failed: PowerShell Set-Content -Encoding UTF8 wrote a BOM, so simpleconfigurator parsed the first line as corrupted text instead of #encoding=UTF-8; bundles.info also still pointed at the older bridge JAR version, so the bridge did not start.
Worked: Restore or rewrite bundles.info with [System.Text.UTF8Encoding]::new($false), keep first bytes as 23 65 6E..., and update the com.example.s32ds.agent.bridge line to the exact installed plugins/com.example.s32ds.agent.bridge_<Bundle-Version>.jar.
Verify: Start S32DS with -clean, confirm no configuration fatal dialog appears, bridge.json pid matches the live s32ds.exe, and /health responds.
Caution: Always back up bundles.info first. Do not use Windows PowerShell Set-Content -Encoding UTF8 for this file.

## 2026-05-25 - PEmicro S32DS debug context may be DSF DMVMContext with no IDebugTarget
Tags: debug,dsf,pemicro,run-control,status,resume
Context: PEmicro launch showed a selected frame in S32DS Debug view, but /debug/sessions had targets: [] and standard IThread/IDebugTarget lookup failed.
Failed: Treating ILaunch.getDebugTargets() as authoritative hid the real CDT/DSF context. The active context adapted to ILaunch and DSF ISuspendResume, not IStackFrame/IThread/IDebugTarget.
Worked: Inspect DebugUITools.getDebugContext(), Debug View selection, and DebugContextService on the SWT UI thread. Treat ISuspendResume.isSuspended() from the DSF DMVMContext as a halted signal, and use Eclipse UI command fallback for Resume when direct generic debug objects are absent.
Verify: During a live PEmicro session, /debug/context should show org.eclipse.cdt.dsf.ui.viewmodel.datamodel.AbstractDMVMNode$DMVMContext; /debug/status should report halted from the active DSF suspendResume context even when debugTargetCount is 0.
Caution: A terminated launch with PEmicro flash programming errors is not a run-control failure; check the ProcessConsole for PE-ERROR before debugging bridge resume logic.

## 2026-05-25 - Run cached S32DS launch configs with temporary attribute overrides
Tags: launch,pemicro,cache,debug-config
Context: Editing or copying .launch files on disk did not reliably affect S32DS launch-manager state during the same IDE session.
Failed: Creating a new .launch file under the workspace and immediately calling the normal launch endpoint left S32DS using cached or undiscovered launch configs.
Worked: Use bridge endpoint POST /launch/run-with-overrides to clone an existing ILaunchConfigurationWorkingCopy, set temporary attributes in memory, save the clone, and launch it in debug mode.
Verify: /launch-configs should list the generated copy name, and the PEmicro console title should include that copy name.
Caution: Prefer temporary copy names with a timestamp. Clean old *_codex_* launch configs later if the UI gets cluttered.

## 2026-05-25 - SJA1110 PEmicro flash needs ISSI NOSFDP plus slower JTAG on this setup
Tags: pemicro,flash,sja1110,algorithm,jtag,reset
Context: The default SJA1110 flash launch connected to the PEmicro probe and read JTAG DAP IDCODE 0x0BA02477, but failed while loading the flash programming algorithm.
Failed: Default ISSI SFDP, ISSI NOSFDP at 5 MHz, MXIC SFDP, and MXIC NOSFDP at 5 MHz all terminated during flash programming or before a usable debug session.
Worked: Launch switch_config_s32g_vnp_rdb_debug_flash_pemicro with overrides: com.pemicro.debug.gdbjtag.pne.useAlternativeAlgorithm=true, alternativeAlgorithmPath=ISSI_IS25LQ040D_NOSFDP_1x32x128k_SJA1110.arp, ml.INTERFACE_PORT_STRING=USB1, ml.SHIFT_FREQ=1000, ml.DO_RESET_DELAY=true, ml.RESET_DELAY=1000, and the corrected FLASH build config id.
Verify: /debug/status should show a live launch after programming instead of terminatedLaunches=1; the console should be named after the generated *_issi_nosfdp_1000_reset_* launch copy.
Caution: If the target still fails with "Error enabling module just selected", first lower JTAG speed and add reset delay before changing source code or assuming MCP run-control is broken.

## 2026-05-25 - Read PEmicro halt location through DSF IStack
Tags: debug,dsf,pemicro,status,location,breakpoints
Context: PEmicro DSF sessions can have debugTargetCount=0 and no generic Eclipse IStackFrame/IThread/IDebugTarget, while Debug View still selects a DMVMContext frame such as `(gdb[0]...).frame[0]`.
Failed: Treating `/debug/location` as false when generic IStackFrame was absent made a real breakpoint halt look like a stale suspended UI context. `IRunControl.resume()` could return OK, then the target immediately re-halted at an enabled C breakpoint, while generic stack/register APIs still returned no frame.
Worked: Use DSF `IStack.getFrameData(IFrameDMContext)` from the active DMVMContext to report function/file/line/address. Keep `/debug/status` DSF-aware and use `/debug/location` to identify which breakpoint actually caught execution.
Verify: With the SJA1110 flash launch active, `/debug/status` may report `source=debugContext:suspendResume` and `dsfSuspended=true`; `/debug/location` should return `source=dsfStack` plus the function and line, e.g. `enetif_low_level_output` at `enetif.c:421`.
Caution: A halted DSF state after Resume is not necessarily a failed resume; it may be a newly hit breakpoint. Check `/debug/location` before changing run-control code again.

## 2026-05-26 - Launch S32DS with install root working directory so JavaFX config tools load
Tags: launch,javafx,s32ds,configuration-tools,peripherals,working-directory
Context: Opening Peripherals/Dashboard after S32DS was started externally produced Problem Occurred dialogs for Dashboard UI refresh and Updating status of the generated files, with NXP Configuration Tools throwing NoClassDefFoundError for javafx/beans/property/SimpleBooleanProperty.
Failed: Starting eclipse\s32ds.exe from an arbitrary PowerShell/Codex working directory without -WorkingDirectory made the relative s32ds.ini property -Defxclipse.java-modules.dir=jre/javafx-sdk-11.0.2/lib resolve incorrectly; bridge Save All could trigger the broken generated-files refresh, making the stack mention EditorController even though the root classpath problem was NXP JavaFX loading.
Worked: Start C:\NXP\S32DS.3.5\eclipse\s32ds.exe with -WorkingDirectory C:\NXP\S32DS.3.5 and pass -vm C:\NXP\S32DS.3.5\jre\bin\javaw.exe plus -clean -data %USERPROFILE%\workspaceS32DS.3.5.
Verify: After restart, query /health and /dialogs/open, then inspect the new .metadata\.log session; it should show the new command line and no fresh NoClassDefFoundError: javafx/beans/property/SimpleBooleanProperty entries after the session header.
Caution: Do not classify these Peripherals/Dashboard NPE dialogs as a bridge bundle install bug just because bridge saveAll appears in one stack; first check the root cause and launch working directory. Avoid editing s32ds.ini unless the correct launch command still reproduces the JavaFX error.

## 2026-08-06 - Read PEmicro semihost output through the telnet Process Console
Tags: pemicro,semihosting,console,dsf,sja1110
Context: A PEmicro CDT/DSF session was running and halted locations were visible, but generic debug_evaluate and debug_memory could not find a suspended Eclipse thread or target.
Failed: Sending semihost output to the GDB Client Console placed it in the Debugger Console view, which console_list and console_tail did not expose.
Worked: Enable PEmicro semihosting, set enableSemihostingIoclientGdbClient=false, enableSemihostingIoclientTelnet=true, and doGdbServerAllocateSemihostingConsole=true. Provide a guarded ARM semihosting _write backend using BKPT 0xAB. After resume, console_list exposes two same-named Process Consoles; inspect both and use console_tail on the one containing application output.
Verify: debug_status reports the target running and console_tail returns the target application diagnostic lines repeatedly.
Caution: Compile the _write backend only behind an explicit validation define; an unguarded backend can execute BKPT when ordinary firmware prints. PEmicro may also reject a second simultaneous services launch, so avoid assuming two live sessions are supported.

## 2026-08-18 - Avoid NXP SVD view deadlock during background PEmicro suspend
Tags: s32ds,svd,dsf,suspend,background,pemicro,perspective
Context: With NXP Peripheral Registers and Arm System Registers views open in the Debug perspective, a PEmicro suspend caused the S32DS SWT main thread to wait inside SvdRegistersViewBase.getThreadId via DSF Query.get, freezing the workbench and forcing foreground UI recovery attempts.
Failed: Keeping the Debug perspective and trying to close SVD tabs through external screen automation required foreground focus and interfered with the user desktop; bridge 0.4.2 exposed show_view but no hide_view.
Worked: With no live debug launch, switch to org.eclipse.cdt.ui.CPerspective through the bridge, verify no com.nxp.s32ds.cdt.svd views are open, then launch and suspend through MCP. Bridge 0.4.3 adds idempotent hide_view plus background DSF suspend/resume so this safeguard no longer needs OS foreground automation.
Verify: In the original recovery run, debug_suspend returned ok, debug_status reported a DSF-suspended target, debug_evaluate read live counters, and get_state remained in C/C++ with zero open SVD views. After installing 0.4.3, a separate MCP session verified bounded health, idempotent hide_view, UI-independent debug_status, and safe-path rejection in debug_snapshot; direct DSF suspend/resume still needs the next live-target run.
Caution: Do not bring S32DS to the foreground or use repeated SetWindowPos. Keep allow_ui_fallback=false; if an SVD view cannot be hidden through MCP, stop before suspend and report the bridge/UI health state.

## 2026-08-18 - Prefer the plugin-bundled MCP package over stale user-site installs
Tags: mcp,bootstrap,plugin,python,version,cache
Context: After installing S32DS MCP plugin 0.4.3, an older editable or user-site s32ds_mcp_server could still satisfy the bootstrap import and hide newly bundled tools.
Failed: Importing s32ds_mcp_server before locating the plugin's mcp-server directory returned early whenever any older package was importable, so updating the plugin files alone did not guarantee the running MCP schema.
Worked: Resolve the plugin-local mcp-server directory first, prepend its src directory to sys.path, and only fall back to a manually installed package when bundled source is unavailable or incomplete.
Verify: Start a separate stdio MCP client from the installed 0.4.3 bootstrap, initialize it, list tools, and confirm health, hide_view, debug_status, and debug_snapshot are present and callable against bridge 0.4.3.
Caution: A Codex thread keeps its original MCP tool schema; verify the new package in a fresh thread or a separate stdio client rather than assuming an in-place cache update hot-reloads tools.
