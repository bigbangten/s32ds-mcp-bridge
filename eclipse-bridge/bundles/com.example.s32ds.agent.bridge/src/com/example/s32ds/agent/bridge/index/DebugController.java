package com.example.s32ds.agent.bridge.index;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.cdt.dsf.concurrent.RequestMonitor;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.debug.service.IRunControl;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IRegister;
import org.eclipse.debug.core.model.IRegisterGroup;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IStep;
import org.eclipse.debug.core.model.ISuspendResume;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IMemoryBlockExtension;
import org.eclipse.debug.core.model.IMemoryBlockRetrieval;
import org.eclipse.debug.core.model.IThread;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutating debug operations: step / resume / suspend / terminate / restart,
 * memory write, register write. Always called only after DangerGate check
 * (the Router enforces this — this class itself is gate-agnostic).
 *
 * <p>Each method finds the "active" thread/target on a best-effort basis:
 * if there's exactly one suspended thread it's the target; if multiple,
 * we pick the first. For commands that require a running target (like
 * {@code suspend}) we instead pick the first non-terminated, non-suspended.
 */
public final class DebugController {

    public Map<String, Object> step(String kind) {
        Map<String, Object> out = new LinkedHashMap<>();
        DebugContextPicker.Selection sel = DebugContextPicker.suspended(0);
        String normalized = kind == null || kind.isEmpty() ? "over" : kind;
        if (tryStep(sel != null ? sel.frame : null, normalized, out, "frame")) return out;
        if (tryStep(sel != null ? sel.thread : null, normalized, out, "thread")) return out;

        String commandId = stepCommandId(normalized);
        if (commandId == null) {
            out.put("ok", false);
            out.put("error", "unknown step kind: " + normalized + " (use into|over|return)");
            return out;
        }
        if (executeUiCommand(commandId, out)) {
            out.put("kind", normalized);
            return out;
        }
        if (!out.containsKey("error")) out.put("error", "no step-capable debug context");
        out.put("ok", false);
        if (sel != null) out.put("debugContextSource", sel.source);
        return out;
    }
    public Map<String, Object> resume() {
        Map<String, Object> out = new LinkedHashMap<>();
        DebugContextPicker.Selection sel = DebugContextPicker.suspended(0);
        if (tryResume(sel != null ? sel.frame : null, out, "frame")) return out;
        if (tryResume(sel != null ? sel.thread : null, out, "thread")) return out;
        if (tryResume(sel != null ? sel.target : null, out, "target")) return out;
        if (tryDsfResume(sel, out)) return out;
        if (executeUiCommand("org.eclipse.debug.ui.commands.Resume", out)) return out;
        if (tryResumeUnchecked(sel != null ? sel.suspendResume : null, out, "suspendResume")) return out;
        out.put("ok", false);
        if (!out.containsKey("error")) out.put("error", "no resumable debug context");
        if (sel != null) out.put("debugContextSource", sel.source);
        return out;
    }
    public Map<String, Object> suspend() {
        Map<String, Object> out = new LinkedHashMap<>();
        DebugContextPicker.Selection sel = DebugContextPicker.running();
        if (trySuspend(sel != null ? sel.thread : null, out, "thread")) return out;
        if (trySuspend(sel != null ? sel.target : null, out, "target")) return out;
        if (trySuspend(sel != null ? sel.suspendResume : null, out, "suspendResume")) return out;
        if (executeUiCommand("org.eclipse.debug.ui.commands.Suspend", out)) return out;
        out.put("ok", false);
        if (!out.containsKey("error")) out.put("error", "no suspendable debug context");
        if (sel != null) out.put("debugContextSource", sel.source);
        return out;
    }
    public Map<String, Object> terminate() {
        Map<String, Object> out = new LinkedHashMap<>();
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        int killed = 0;
        for (ILaunch launch : mgr.getLaunches()) {
            if (launch.isTerminated()) continue;
            try {
                if (launch.canTerminate()) { launch.terminate(); killed++; }
            } catch (DebugException ignored) {}
        }
        out.put("ok", killed > 0);
        out.put("terminatedLaunches", killed);
        if (killed == 0) out.put("error", "no live launch to terminate");
        return out;
    }

    public Map<String, Object> restart() {
        Map<String, Object> out = new LinkedHashMap<>();
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        ILaunch toRestart = null;
        for (ILaunch launch : mgr.getLaunches()) {
            if (!launch.isTerminated()) { toRestart = launch; break; }
        }
        if (toRestart == null) { out.put("ok", false); out.put("error", "no live launch to restart"); return out; }
        try {
            String mode = toRestart.getLaunchMode();
            org.eclipse.debug.core.ILaunchConfiguration config = toRestart.getLaunchConfiguration();
            if (config == null) { out.put("ok", false); out.put("error", "launch has no configuration"); return out; }
            try {
                if (toRestart.canTerminate()) toRestart.terminate();
            } catch (DebugException ignored) {}
            ILaunch fresh = config.launch(mode, new org.eclipse.core.runtime.NullProgressMonitor());
            out.put("ok", true);
            out.put("configName", config.getName());
            out.put("mode", fresh.getLaunchMode());
            return out;
        } catch (Exception e) {
            out.put("ok", false); out.put("error", e.getMessage()); return out;
        }
    }

    /** Write {@code hex} bytes (no 0x prefix, even length) starting at {@code addrStr}. */
    public Map<String, Object> writeMemory(String addrStr, String hex) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (addrStr == null || hex == null) {
            out.put("ok", false); out.put("error", "addr and hex required"); return out;
        }
        BigInteger addr;
        try {
            String s = addrStr.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) addr = new BigInteger(s.substring(2), 16);
            else if (s.matches("^[0-9a-fA-F]+$") && s.length() >= 3) addr = new BigInteger(s, 16);
            else addr = new BigInteger(s);
        } catch (Exception e) {
            out.put("ok", false); out.put("error", "invalid addr: " + addrStr); return out;
        }
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        h = h.replaceAll("[\\s_]", "");
        if (h.length() % 2 != 0) {
            out.put("ok", false); out.put("error", "hex payload must have even length"); return out;
        }
        if (h.length() > 8192) { // 4096 bytes
            out.put("ok", false); out.put("error", "payload exceeds 4096 byte cap"); return out;
        }
        byte[] payload = new byte[h.length() / 2];
        for (int i = 0; i < payload.length; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) { out.put("ok", false); out.put("error", "invalid hex char at " + (i * 2)); return out; }
            payload[i] = (byte) ((hi << 4) | lo);
        }

        IDebugTarget t = firstSuspendedTarget();
        if (t == null) { out.put("ok", false); out.put("error", "no suspended target"); return out; }
        try {
            IMemoryBlockRetrieval ret = t.getAdapter(IMemoryBlockRetrieval.class);
            if (ret == null) { out.put("ok", false); out.put("error", "target lacks IMemoryBlockRetrieval"); return out; }
            IMemoryBlock mb;
            if (ret instanceof org.eclipse.debug.core.model.IMemoryBlockRetrievalExtension) {
                mb = ((org.eclipse.debug.core.model.IMemoryBlockRetrievalExtension) ret)
                        .getExtendedMemoryBlock("0x" + addr.toString(16), t);
            } else {
                mb = ret.getMemoryBlock(addr.longValueExact(), payload.length);
            }
            if (mb == null) { out.put("ok", false); out.put("error", "could not obtain memory block"); return out; }
            if (mb instanceof IMemoryBlockExtension) {
                ((IMemoryBlockExtension) mb).setValue(BigInteger.ZERO, payload);
            } else {
                mb.setValue(0L, payload);
            }
            out.put("ok", true);
            out.put("addr", "0x" + addr.toString(16));
            out.put("byteCount", payload.length);
            return out;
        } catch (Throwable ex) {
            out.put("ok", false);
            out.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return out;
        }
    }

    /** Set a register on the first suspended thread's top frame. */
    public Map<String, Object> writeRegister(String groupName, String regName, String value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (regName == null || regName.isEmpty() || value == null) {
            out.put("ok", false); out.put("error", "regName and value required"); return out;
        }
        IThread th = firstSuspendedThread();
        if (th == null) { out.put("ok", false); out.put("error", "no suspended thread"); return out; }
        try {
            IStackFrame[] fs = th.getStackFrames();
            if (fs.length == 0) { out.put("ok", false); out.put("error", "no stack frames"); return out; }
            IStackFrame top = fs[0];
            if (!top.hasRegisterGroups()) {
                out.put("ok", false); out.put("error", "frame has no register groups"); return out;
            }
            for (IRegisterGroup g : top.getRegisterGroups()) {
                if (groupName != null && !groupName.isEmpty()
                        && !groupName.equalsIgnoreCase(g.getName())) continue;
                for (IRegister r : g.getRegisters()) {
                    if (regName.equalsIgnoreCase(r.getName())) {
                        if (!r.supportsValueModification()) {
                            out.put("ok", false);
                            out.put("error", "register does not support modification");
                            return out;
                        }
                        if (!r.verifyValue(value)) {
                            out.put("ok", false);
                            out.put("error", "value not accepted by register: " + value);
                            return out;
                        }
                        r.setValue(value);
                        out.put("ok", true);
                        out.put("group", g.getName());
                        out.put("register", r.getName());
                        out.put("newValue", value);
                        return out;
                    }
                }
            }
            out.put("ok", false);
            out.put("error", "register not found: " + (groupName == null ? "" : groupName + "/") + regName);
            return out;
        } catch (DebugException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    /**
     * Resume execution and stop at the given source line. Implemented via
     * {@code org.eclipse.cdt.debug.core.model.IRunToLine} adapter on the top
     * stack frame. Reflection so we don't compile-time depend on CDT.
     */
    public Map<String, Object> runToLine(String fileSpec, int line, boolean skipBreakpoints) {
        return adapterCall(fileSpec, line,
                "org.eclipse.cdt.debug.core.model.IRunToLine",
                "runToLine",
                new Class<?>[]{ String.class, int.class, boolean.class },
                skipBreakpoints);
    }

    /**
     * Move execution to a given source line without running there (PC jump).
     * CDT exposes this as {@code IJumpToLine}.
     */
    public Map<String, Object> jumpToLine(String fileSpec, int line) {
        return adapterCall(fileSpec, line,
                "org.eclipse.cdt.debug.core.model.IJumpToLine",
                "jumpToLine",
                new Class<?>[]{ String.class, int.class },
                null);
    }

    private Map<String, Object> adapterCall(String fileSpec, int line, String adapterClassName,
                                            String method, Class<?>[] paramTypes, Object extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (fileSpec == null || fileSpec.isEmpty() || line <= 0) {
            out.put("ok", false);
            out.put("error", "file and positive line required");
            return out;
        }
        IThread th = firstSuspendedThread();
        if (th == null) {
            out.put("ok", false);
            out.put("error", "no suspended thread");
            return out;
        }
        Class<?> adapterClass;
        try {
            Bundle cdt = Platform.getBundle("org.eclipse.cdt.debug.core");
            if (cdt == null) {
                out.put("ok", false);
                out.put("error", "CDT bundle not present");
                return out;
            }
            adapterClass = cdt.loadClass(adapterClassName);
        } catch (ClassNotFoundException e) {
            out.put("ok", false);
            out.put("error", "CDT adapter class missing: " + adapterClassName);
            return out;
        }
        try {
            IStackFrame[] fs = th.getStackFrames();
            if (fs.length == 0) { out.put("ok", false); out.put("error", "no frames"); return out; }
            IStackFrame top = fs[0];
            Object adapter = top.getAdapter(adapterClass);
            if (adapter == null) {
                // Try the thread itself; some CDT models adapt at thread level.
                adapter = th.getAdapter(adapterClass);
            }
            if (adapter == null) {
                out.put("ok", false);
                out.put("error", "frame/thread does not adapt to " + adapterClass.getSimpleName());
                return out;
            }
            // Resolve a workspace IFile for the file to use as source handle.
            IResource res = resolveResource(fileSpec);
            String sourceHandle;
            if (res != null && res.getLocation() != null) sourceHandle = res.getLocation().toOSString();
            else sourceHandle = fileSpec;

            Method m = adapterClass.getMethod(method, paramTypes);
            Object[] args;
            if (paramTypes.length == 3) args = new Object[]{ sourceHandle, line, extra };
            else args = new Object[]{ sourceHandle, line };
            m.invoke(adapter, args);
            out.put("ok", true);
            out.put("sourceHandle", sourceHandle);
            out.put("line", line);
            return out;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            out.put("ok", false);
            out.put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return out;
        }
    }

    private IResource resolveResource(String fileSpec) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        if (fileSpec.startsWith("/")) {
            IFile file = root.getFile(new Path(fileSpec));
            if (file != null && file.exists()) return file;
        }
        try {
            java.io.File fs = new java.io.File(fileSpec);
            if (fs.exists()) {
                IFile[] files = root.findFilesForLocationURI(fs.toURI());
                if (files.length > 0) return files[0];
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ───────────────── helpers ─────────────────

    private IThread firstSuspendedThread() {
        DebugContextPicker.Selection s = DebugContextPicker.suspended(0);
        return s != null ? s.thread : null;
    }

    private IDebugTarget firstSuspendedTarget() {
        DebugContextPicker.Selection s = DebugContextPicker.suspended(0);
        return s != null ? s.target : null;
    }

    private IDebugTarget firstRunningTarget() {
        DebugContextPicker.Selection s = DebugContextPicker.running();
        return s != null ? s.target : null;
    }

    private boolean tryResume(ISuspendResume element, Map<String, Object> out, String source) {
        if (element == null) return false;
        try {
            if (!element.canResume()) return false;
            element.resume();
            out.put("ok", true);
            out.put("source", source);
            return true;
        } catch (DebugException e) {
            out.put("directError", e.getMessage());
            return false;
        } catch (Throwable t) {
            out.put("directError", t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private boolean tryResumeUnchecked(ISuspendResume element, Map<String, Object> out, String source) {
        if (element == null) return false;
        try {
            boolean suspended = false;
            boolean canResume = false;
            try { suspended = element.isSuspended(); } catch (Throwable ignored) {}
            try { canResume = element.canResume(); } catch (Throwable ignored) {}
            if (!suspended && !canResume) return false;
            element.resume();
            out.put("ok", true);
            out.put("source", source);
            out.put("reportedCanResume", canResume);
            return true;
        } catch (DebugException e) {
            out.put("directUncheckedError", e.getMessage());
            return false;
        } catch (Throwable t) {
            out.put("directUncheckedError", t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private boolean tryDsfResume(DebugContextPicker.Selection sel, Map<String, Object> out) {
        if (sel == null || sel.dmContext == null) return false;
        IRunControl.IExecutionDMContext exec =
                DMContexts.getAncestorOfType(sel.dmContext, IRunControl.IExecutionDMContext.class);
        if (exec == null) return false;
        DsfSession session = DsfSession.getSession(exec.getSessionId());
        if (session == null || !session.isActive()) {
            out.put("dsfResumeError", "DSF session is not active: " + exec.getSessionId());
            return false;
        }
        org.osgi.framework.Bundle bundle = FrameworkUtil.getBundle(DebugController.class);
        if (bundle == null || bundle.getBundleContext() == null) {
            out.put("dsfResumeError", "bridge bundle context unavailable");
            return false;
        }
        DsfServicesTracker tracker = new DsfServicesTracker(bundle.getBundleContext(), session.getId());
        try {
            final IRunControl runControl = tracker.getService(IRunControl.class);
            if (runControl == null) {
                out.put("dsfResumeError", "IRunControl service unavailable");
                return false;
            }
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<IStatus> status = new AtomicReference<>();
            session.getExecutor().execute(new Runnable() {
                @Override public void run() {
                    runControl.resume(exec, new RequestMonitor(session.getExecutor(), null) {
                        @Override protected void handleCompleted() {
                            status.set(getStatus());
                            latch.countDown();
                        }
                    });
                }
            });
            if (!latch.await(5, TimeUnit.SECONDS)) {
                out.put("dsfResumeError", "IRunControl.resume timed out");
                return false;
            }
            IStatus st = status.get();
            if (st != null && !st.isOK()) {
                out.put("dsfResumeError", st.getMessage());
                return false;
            }
            Boolean stillSuspended = dsfSuspended(session, runControl, exec);
            if (Boolean.TRUE.equals(stillSuspended)) {
                out.put("dsfResumePostState", "stillSuspended");
                return false;
            }
            out.put("ok", true);
            out.put("source", "dsfRunControl");
            out.put("sessionId", session.getId());
            if (stillSuspended != null) out.put("dsfSuspendedAfterResume", stillSuspended);
            return true;
        } catch (Throwable t) {
            out.put("dsfResumeError", t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        } finally {
            tracker.dispose();
        }
    }

    private Boolean dsfSuspended(DsfSession session, IRunControl runControl,
                                 IRunControl.IExecutionDMContext exec) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> state = new AtomicReference<>();
        try {
            session.getExecutor().execute(new Runnable() {
                @Override public void run() {
                    try { state.set(Boolean.valueOf(runControl.isSuspended(exec))); }
                    finally { latch.countDown(); }
                }
            });
            if (!latch.await(2, TimeUnit.SECONDS)) return null;
            return state.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean trySuspend(ISuspendResume element, Map<String, Object> out, String source) {
        if (element == null) return false;
        try {
            if (!element.canSuspend()) return false;
            element.suspend();
            out.put("ok", true);
            out.put("source", source);
            return true;
        } catch (DebugException e) {
            out.put("directError", e.getMessage());
            return false;
        } catch (Throwable t) {
            out.put("directError", t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private boolean tryStep(IStep stepper, String kind, Map<String, Object> out, String source) {
        if (stepper == null) return false;
        try {
            if ("into".equalsIgnoreCase(kind)) {
                if (!stepper.canStepInto()) return false;
                stepper.stepInto();
            } else if ("over".equalsIgnoreCase(kind)) {
                if (!stepper.canStepOver()) return false;
                stepper.stepOver();
            } else if ("return".equalsIgnoreCase(kind) || "out".equalsIgnoreCase(kind)) {
                if (!stepper.canStepReturn()) return false;
                stepper.stepReturn();
            } else {
                return false;
            }
            out.put("ok", true);
            out.put("kind", kind);
            out.put("source", source);
            return true;
        } catch (DebugException e) {
            out.put("directError", e.getMessage());
            return false;
        } catch (Throwable t) {
            out.put("directError", t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private String stepCommandId(String kind) {
        if ("into".equalsIgnoreCase(kind)) return "org.eclipse.debug.ui.commands.StepInto";
        if ("over".equalsIgnoreCase(kind)) return "org.eclipse.debug.ui.commands.StepOver";
        if ("return".equalsIgnoreCase(kind) || "out".equalsIgnoreCase(kind)) return "org.eclipse.debug.ui.commands.StepReturn";
        return null;
    }

    private boolean executeUiCommand(String commandId, Map<String, Object> out) {
        try {
            if (!PlatformUI.isWorkbenchRunning()) {
                out.put("uiCommandError", "workbench is not running");
                return false;
            }
            final Display display = PlatformUI.getWorkbench().getDisplay();
            if (display == null || display.isDisposed()) {
                out.put("uiCommandError", "display is not available");
                return false;
            }
            final AtomicReference<Throwable> error = new AtomicReference<>();
            Runnable run = new Runnable() {
                @Override public void run() {
                    try {
                        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                        if (window == null && PlatformUI.getWorkbench().getWorkbenchWindows().length > 0) {
                            window = PlatformUI.getWorkbench().getWorkbenchWindows()[0];
                        }
                        IWorkbenchPage page = window != null ? window.getActivePage() : null;
                        if (page != null) {
                            try {
                                IViewPart debugView = page.findView("org.eclipse.debug.ui.DebugView");
                                if (debugView == null) debugView = page.showView("org.eclipse.debug.ui.DebugView");
                                if (debugView != null) {
                                    page.activate(debugView);
                                    out.put("activatedPart", debugView.getSite() != null ? debugView.getSite().getId() : null);
                                }
                            } catch (Throwable t) {
                                out.put("activateDebugViewError", t.getClass().getSimpleName() + ": " + t.getMessage());
                            }
                        }
                        IHandlerService service = window != null
                                ? window.getService(IHandlerService.class)
                                : PlatformUI.getWorkbench().getService(IHandlerService.class);
                        if (service == null) throw new IllegalStateException("IHandlerService unavailable");
                        service.executeCommand(commandId, null);
                    } catch (Throwable t) {
                        error.set(t);
                    }
                }
            };
            if (Display.getCurrent() == display) run.run();
            else display.syncExec(run);
            if (error.get() != null) {
                Throwable t = error.get();
                out.put("uiCommandError", t.getClass().getSimpleName() + ": " + t.getMessage());
                return false;
            }
            out.put("ok", true);
            out.put("source", "uiCommand");
            out.put("commandId", commandId);
            return true;
        } catch (Throwable t) {
            out.put("uiCommandError", t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }
}
