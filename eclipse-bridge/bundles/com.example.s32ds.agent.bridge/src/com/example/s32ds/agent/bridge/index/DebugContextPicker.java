package com.example.s32ds.agent.bridge.index;

import com.example.s32ds.agent.bridge.util.UiThread;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.debug.service.IRunControl;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.dsf.ui.viewmodel.datamodel.IDMVMContext;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.ISuspendResume;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.FrameworkUtil;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves the active Eclipse debug context before falling back to launch scans.
 *
 * <p>S32DS/CDT/DSF can show a selected stack frame in the Debug view while the
 * generic target/thread suspended flags are not sufficient for bridge-side
 * run-control selection. Treating the active stack frame as the strongest
 * signal keeps read and run-control paths aligned with what the IDE toolbar
 * would operate on.</p>
 */
final class DebugContextPicker {
    static final class Selection {
        final ILaunch launch;
        final IDebugTarget target;
        final IThread thread;
        final IStackFrame frame;
        final ISuspendResume suspendResume;
        final IDMContext dmContext;
        final String source;

        Selection(ILaunch launch, IDebugTarget target, IThread thread, IStackFrame frame,
                  ISuspendResume suspendResume, IDMContext dmContext, String source) {
            this.launch = launch;
            this.target = target;
            this.thread = thread;
            this.frame = frame;
            this.suspendResume = suspendResume;
            this.dmContext = dmContext;
            this.source = source;
        }
    }

    private static final String DEBUG_VIEW_ID = "org.eclipse.debug.ui.DebugView";

    private DebugContextPicker() {}

    static Selection suspended(int frameIndex) {
        Selection active = active(frameIndex);
        if (isStopped(active)) return active;
        return scan(frameIndex, true);
    }

    static Selection running() {
        Selection active = active(0);
        if (canSuspend(active)) return active;
        return scan(0, false);
    }

    static IStackFrame frame(int frameIndex) {
        Selection s = suspended(frameIndex);
        return s != null ? s.frame : null;
    }

    static Selection activeForDiagnostics(int frameIndex) {
        return active(frameIndex);
    }

    static Boolean dsfSuspendedState(Selection s) {
        return s != null ? dsfSuspended(s.dmContext) : null;
    }

    private static Selection active(int frameIndex) {
        Selection best = null;
        Selection[] candidates = new Selection[] {
                debugUiToolsContext(frameIndex),
                debugViewSelection(frameIndex),
                debugContextService(frameIndex)
        };
        for (Selection s : candidates) {
            if (s == null || isTerminated(s)) continue;
            if (isStopped(s) || canSuspend(s)) return s;
            if (best == null) best = s;
        }
        return best;
    }

    private static Selection debugUiToolsContext(int frameIndex) {
        try {
            return UiThread.sync(() -> fromObject(DebugUITools.getDebugContext(), frameIndex, "debugContext"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Selection debugViewSelection(int frameIndex) {
        try {
            return UiThread.sync(() -> {
                IWorkbenchWindow window = activeWindow();
                IWorkbenchPage page = window != null ? window.getActivePage() : null;
                if (page == null) return null;
                IViewPart view = page.findView(DEBUG_VIEW_ID);
                if (view == null || view.getSite() == null) return null;
                ISelectionProvider provider = view.getSite().getSelectionProvider();
                if (provider == null) return null;
                return fromObject(provider.getSelection(), frameIndex, "debugViewSelection");
            });
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Selection debugContextService(int frameIndex) {
        try {
            return UiThread.sync(() -> {
                IWorkbenchWindow window = activeWindow();
                if (window == null) return null;
                Object manager = DebugUITools.class.getMethod("getDebugContextManager").invoke(null);
                Object service = manager.getClass().getMethod("getContextService", IWorkbenchWindow.class)
                        .invoke(manager, window);
                Object active = service.getClass().getMethod("getActiveContext").invoke(service);
                return fromObject(active, frameIndex, "debugContextService");
            });
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static IWorkbenchWindow activeWindow() {
        if (!PlatformUI.isWorkbenchRunning()) return null;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window != null) return window;
        IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
        return windows != null && windows.length > 0 ? windows[0] : null;
    }

    private static Selection scan(int frameIndex, boolean stopped) {
        org.eclipse.debug.core.ILaunchManager mgr = org.eclipse.debug.core.DebugPlugin.getDefault().getLaunchManager();
        for (ILaunch launch : mgr.getLaunches()) {
            if (launch == null || launch.isTerminated()) continue;
            for (IDebugTarget target : launch.getDebugTargets()) {
                if (target == null || target.isTerminated()) continue;
                Selection s = fromTarget(launch, target, frameIndex, "launchScan");
                if (s == null) continue;
                if (stopped) {
                    if (isStopped(s)) return s;
                } else if (canSuspend(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static Selection fromObject(Object object, int frameIndex, String source) {
        if (object == null) return null;
        if (object instanceof ISelection) return fromSelection((ISelection) object, frameIndex, source);
        IStackFrame frame = adapt(object, IStackFrame.class);
        if (frame != null) return fromFrame(frame, source + ":frame");
        IThread thread = adapt(object, IThread.class);
        if (thread != null) return fromThread(thread, frameIndex, source + ":thread");
        IDebugTarget target = adapt(object, IDebugTarget.class);
        if (target != null) return fromTarget(target.getLaunch(), target, frameIndex, source + ":target");
        IDMContext dmContext = dmContextFrom(object);
        ISuspendResume suspendResume = adapt(object, ISuspendResume.class);
        ILaunch launch = adapt(object, ILaunch.class);
        if (suspendResume != null || launch != null || dmContext != null) {
            return new Selection(launch, null, null, null, suspendResume, dmContext, source + ":suspendResume");
        }
        return null;
    }

    private static Selection fromSelection(ISelection selection, int frameIndex, String source) {
        if (selection == null || selection.isEmpty()) return null;
        if (selection instanceof IStructuredSelection) {
            IStructuredSelection structured = (IStructuredSelection) selection;
            for (Object element : structured.toArray()) {
                Selection s = fromObject(element, frameIndex, source + ":element");
                if (s != null) return s;
            }
        }
        return fromObject((Object) selection, frameIndex, source);
    }

    private static Selection fromFrame(IStackFrame frame, String source) {
        if (frame == null) return null;
        try {
            IThread thread = frame.getThread();
            IDebugTarget target = frame.getDebugTarget();
            ILaunch launch = frame.getLaunch();
            return new Selection(launch, target, thread, frame, null, null, source);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Selection fromThread(IThread thread, int frameIndex, String source) {
        if (thread == null) return null;
        IStackFrame frame = null;
        try {
            IStackFrame[] frames = thread.getStackFrames();
            if (frames != null && frames.length > 0) {
                int idx = Math.max(0, Math.min(frameIndex, frames.length - 1));
                frame = frames[idx];
            }
        } catch (DebugException ignored) {}
        try {
            return new Selection(thread.getLaunch(), thread.getDebugTarget(), thread, frame, null, null, source);
        } catch (Throwable ignored) {
            return new Selection(null, null, thread, frame, null, null, source);
        }
    }

    private static Selection fromTarget(ILaunch launch, IDebugTarget target, int frameIndex, String source) {
        if (target == null) return null;
        try {
            IThread[] threads = target.getThreads();
            if (threads != null) {
                Selection bestWithFrame = null;
                for (IThread thread : threads) {
                    Selection s = fromThread(thread, frameIndex, source + ":thread");
                    if (s == null) continue;
                    if (safeSuspended(thread)) return s;
                    if (bestWithFrame == null && s.frame != null) bestWithFrame = s;
                }
                if (bestWithFrame != null) return bestWithFrame;
            }
        } catch (DebugException ignored) {}
        return new Selection(launch != null ? launch : target.getLaunch(), target, null, null, null, null, source + ":target");
    }

    static boolean isStopped(Selection s) {
        if (s == null || isTerminated(s)) return false;
        Boolean dsfSuspended = dsfSuspended(s.dmContext);
        if (dsfSuspended != null) return dsfSuspended.booleanValue();
        if (s.frame != null || safeSuspended(s.thread) || safeSuspended(s.target)) return true;
        if (s.suspendResume == null) return false;
        if (s.dmContext != null) return false;
        return safeSuspended(s.suspendResume);
    }

    static boolean canSuspend(Selection s) {
        if (s == null || isTerminated(s)) return false;
        try { if (s.thread != null && s.thread.canSuspend()) return true; } catch (Throwable ignored) {}
        try { if (s.target != null && s.target.canSuspend()) return true; } catch (Throwable ignored) {}
        try { return s.suspendResume != null && s.suspendResume.canSuspend(); } catch (Throwable ignored) { return false; }
    }

    private static boolean isTerminated(Selection s) {
        try { if (s.launch != null && s.launch.isTerminated()) return true; } catch (Throwable ignored) {}
        try { if (s.target != null && s.target.isTerminated()) return true; } catch (Throwable ignored) {}
        try { return s.thread != null && s.thread.isTerminated(); } catch (Throwable ignored) { return false; }
    }

    private static boolean safeSuspended(org.eclipse.debug.core.model.ISuspendResume sr) {
        try { return sr != null && sr.isSuspended(); } catch (Throwable ignored) { return false; }
    }

    private static Boolean dsfSuspended(IDMContext dmContext) {
        if (dmContext == null) return null;
        IRunControl.IExecutionDMContext exec =
                DMContexts.getAncestorOfType(dmContext, IRunControl.IExecutionDMContext.class);
        if (exec == null) return null;
        DsfSession session = DsfSession.getSession(exec.getSessionId());
        if (session == null || !session.isActive()) return null;
        org.osgi.framework.Bundle bundle = FrameworkUtil.getBundle(DebugContextPicker.class);
        if (bundle == null || bundle.getBundleContext() == null) return null;
        DsfServicesTracker tracker = new DsfServicesTracker(bundle.getBundleContext(), session.getId());
        try {
            final IRunControl runControl = tracker.getService(IRunControl.class);
            if (runControl == null) return null;
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Boolean> state = new AtomicReference<>();
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
        } finally {
            tracker.dispose();
        }
    }

    private static IDMContext dmContextFrom(Object object) {
        IDMVMContext vmContext = adapt(object, IDMVMContext.class);
        if (vmContext != null) {
            try { return vmContext.getDMContext(); } catch (Throwable ignored) {}
        }
        return adapt(object, IDMContext.class);
    }

    private static <T> T adapt(Object object, Class<T> type) {
        if (object == null) return null;
        if (type.isInstance(object)) return type.cast(object);
        if (object instanceof IAdaptable) {
            Object adapted = ((IAdaptable) object).getAdapter(type);
            if (type.isInstance(adapted)) return type.cast(adapted);
        }
        try {
            Object adapted = Platform.getAdapterManager().getAdapter(object, type);
            return type.isInstance(adapted) ? type.cast(adapted) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
