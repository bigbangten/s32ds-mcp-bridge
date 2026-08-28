package com.example.s32ds.agent.bridge.index;

import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.datamodel.DMContexts;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.service.IProcesses;
import org.eclipse.cdt.dsf.debug.service.IRunControl;
import org.eclipse.cdt.dsf.debug.service.command.ICommandControlService;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves one live debug launch without depending on the active Debug view.
 *
 * <p>The original bridge selected the first active/suspended element exposed by
 * the Eclipse UI. That is ambiguous with two probes and can force the Debug
 * view to become active. This selector maps a launch configuration or DSF
 * session id to its launch and obtains an execution context directly from DSF.
 * All methods are UI-thread independent.</p>
 */
final class DebugSessionSelector {
    static final class Selector {
        final String configName;
        final String sessionId;
        final String launchId;

        Selector(String configName, String sessionId, String launchId) {
            this.configName = trim(configName);
            this.sessionId = trim(sessionId);
            this.launchId = trim(launchId);
        }

        boolean isEmpty() {
            return configName == null && sessionId == null && launchId == null;
        }

        private static String trim(String value) {
            if (value == null) return null;
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    private DebugSessionSelector() {}

    static DebugContextPicker.Selection select(Selector selector, int frameIndex,
                                                boolean requireSuspended) {
        Selector request = selector != null ? selector : new Selector(null, null, null);
        List<ILaunch> matches = matchingLaunches(request);
        if (matches.size() != 1) return null;

        ILaunch launch = matches.get(0);
        DsfSession session = sessionForLaunch(launch);
        if (session != null && session.isActive()) {
            IRunControl.IExecutionDMContext execution = executionContext(session,
                    Boolean.valueOf(requireSuspended));
            if (execution != null) {
                return new DebugContextPicker.Selection(launch, null, null, null,
                        null, execution, "sessionSelector:dsf");
            }
        }

        DebugContextPicker.Selection generic = genericSelection(launch, frameIndex,
                requireSuspended);
        if (generic != null) return generic;
        return null;
    }

    static ILaunch findLaunch(Selector selector) {
        List<ILaunch> matches = matchingLaunches(selector != null
                ? selector : new Selector(null, null, null));
        return matches.size() == 1 ? matches.get(0) : null;
    }

    static String selectionProblem(Selector selector) {
        List<ILaunch> matches = matchingLaunches(selector != null
                ? selector : new Selector(null, null, null));
        if (matches.isEmpty()) return "no matching live debug launch";
        if (matches.size() > 1) {
            return "selector matched " + matches.size()
                    + " live debug launches; pass launchId or sessionId from "
                    + "debug_status/debug_sessions";
        }
        return null;
    }

    static List<ILaunch> matchingLaunches(Selector selector) {
        List<ILaunch> matches = new ArrayList<>();
        ILaunch[] launches = DebugPlugin.getDefault().getLaunchManager().getLaunches();
        for (ILaunch launch : launches) {
            if (launch == null || launch.isTerminated()) continue;
            if (!ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode())) continue;
            if (selector != null && selector.configName != null
                    && !selector.configName.equals(configName(launch))) continue;
            if (selector != null && selector.launchId != null
                    && !selector.launchId.equals(launchId(launch))) continue;
            if (selector != null && selector.sessionId != null
                    && !selector.sessionId.equals(sessionIdForLaunch(launch))) continue;
            matches.add(launch);
        }
        return matches;
    }

    static String launchId(ILaunch launch) {
        return launch == null ? null
                : Integer.toHexString(System.identityHashCode(launch));
    }

    static String configName(ILaunch launch) {
        try {
            return launch != null && launch.getLaunchConfiguration() != null
                    ? launch.getLaunchConfiguration().getName() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String sessionIdForLaunch(ILaunch launch) {
        DsfSession session = sessionForLaunch(launch);
        return session != null ? session.getId() : null;
    }

    static DsfSession sessionForLaunch(ILaunch launch) {
        if (launch == null) return null;

        try {
            Object adapted = launch.getAdapter(DsfSession.class);
            if (adapted instanceof DsfSession) return (DsfSession) adapted;
        } catch (Throwable ignored) {}

        try {
            Method method = launch.getClass().getMethod("getSession");
            Object value = method.invoke(launch);
            if (value instanceof DsfSession) return (DsfSession) value;
        } catch (Throwable ignored) {}

        try {
            for (DsfSession session : DsfSession.getActiveSessions()) {
                if (session == null || !session.isActive()) continue;
                Object adapter = session.getModelAdapter(ILaunch.class);
                if (adapter == launch) return session;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static Boolean suspendedState(ILaunch launch) {
        DsfSession session = sessionForLaunch(launch);
        if (session == null || !session.isActive()) return null;
        IRunControl.IExecutionDMContext execution = executionContext(session, null);
        if (execution == null) return null;
        Bundle bundle = FrameworkUtil.getBundle(DebugSessionSelector.class);
        if (bundle == null || bundle.getBundleContext() == null) return null;
        DsfServicesTracker tracker = new DsfServicesTracker(bundle.getBundleContext(), session.getId());
        try {
            IRunControl runControl = tracker.getService(IRunControl.class);
            if (runControl == null) return null;
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Boolean> result = new AtomicReference<>();
            session.getExecutor().execute(new Runnable() {
                @Override public void run() {
                    try {
                        result.set(Boolean.valueOf(runControl.isSuspended(execution)));
                    } finally {
                        latch.countDown();
                    }
                }
            });
            return latch.await(2, TimeUnit.SECONDS) ? result.get() : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            tracker.dispose();
        }
    }

    static IRunControl.IExecutionDMContext executionContext(DsfSession session,
                                                             Boolean requireSuspended) {
        if (session == null || !session.isActive()) return null;
        Bundle bundle = FrameworkUtil.getBundle(DebugSessionSelector.class);
        if (bundle == null || bundle.getBundleContext() == null) return null;
        DsfServicesTracker tracker = new DsfServicesTracker(bundle.getBundleContext(), session.getId());
        try {
            final IRunControl runControl = tracker.getService(IRunControl.class);
            final IProcesses processes = tracker.getService(IProcesses.class);
            final ICommandControlService commandControl =
                    tracker.getService(ICommandControlService.class);
            if (runControl == null) return null;

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<List<IRunControl.IExecutionDMContext>> contexts =
                    new AtomicReference<>(Collections.emptyList());
            session.getExecutor().execute(new Runnable() {
                @Override public void run() {
                    Object modelAdapter = session.getModelAdapter(
                            IRunControl.IExecutionDMContext.class);
                    if (modelAdapter instanceof IRunControl.IExecutionDMContext
                            && stateMatches(runControl,
                                    (IRunControl.IExecutionDMContext) modelAdapter,
                                    requireSuspended)) {
                        contexts.set(Collections.singletonList(
                                (IRunControl.IExecutionDMContext) modelAdapter));
                        latch.countDown();
                        return;
                    }
                    IDMContext root = commandControl != null ? commandControl.getContext() : null;
                    if (root == null) {
                        latch.countDown();
                        return;
                    }
                    IRunControl.IExecutionDMContext direct = executionFrom(root);
                    if (direct != null) {
                        contexts.set(Collections.singletonList(direct));
                        latch.countDown();
                        return;
                    }
                    if (processes == null) {
                        collectContainerChildren(runControl, root, contexts, latch, session);
                        return;
                    }
                    processes.getProcessesBeingDebugged(root,
                            new DataRequestMonitor<IDMContext[]>(session.getExecutor(), null) {
                        @Override protected void handleCompleted() {
                            if (isSuccess() && getData() != null) {
                                List<IRunControl.IExecutionDMContext> found =
                                        executionsFrom(getData());
                                if (!found.isEmpty()) {
                                    contexts.set(found);
                                    latch.countDown();
                                    return;
                                }
                                IDMContext[] values = getData();
                                IDMContext first = values.length > 0 ? values[0] : root;
                                collectContainerChildren(runControl, first, contexts, latch,
                                        session);
                                return;
                            }
                            collectContainerChildren(runControl, root, contexts, latch, session);
                        }
                    });
                }
            });
            if (!latch.await(3, TimeUnit.SECONDS)) return null;
            List<IRunControl.IExecutionDMContext> values = contexts.get();
            return selectByState(session, runControl, values, requireSuspended);
        } catch (Throwable ignored) {
            return null;
        } finally {
            tracker.dispose();
        }
    }

    static void annotate(Map<String, Object> out, DebugContextPicker.Selection selection) {
        if (out == null || selection == null) return;
        if (selection.launch != null) {
            out.put("launchId", launchId(selection.launch));
            out.put("configName", configName(selection.launch));
            String sessionId = sessionIdForLaunch(selection.launch);
            if (sessionId != null) out.put("sessionId", sessionId);
        } else if (selection.dmContext != null) {
            out.put("sessionId", selection.dmContext.getSessionId());
        }
        out.put("debugContextSource", selection.source);
    }

    private static DebugContextPicker.Selection genericSelection(ILaunch launch,
                                                                 int frameIndex,
                                                                 boolean requireSuspended) {
        for (IDebugTarget target : launch.getDebugTargets()) {
            if (target == null || target.isTerminated()) continue;
            DebugContextPicker.Selection selection = fromTarget(launch, target, frameIndex);
            if (selection == null) continue;
            if (requireSuspended && DebugContextPicker.isStopped(selection)) return selection;
            if (!requireSuspended && DebugContextPicker.canSuspend(selection)) return selection;
        }
        return null;
    }

    private static DebugContextPicker.Selection fromTarget(ILaunch launch, IDebugTarget target,
                                                            int frameIndex) {
        try {
            IThread[] threads = target.getThreads();
            if (threads != null) {
                DebugContextPicker.Selection fallback = null;
                for (IThread thread : threads) {
                    IStackFrame frame = null;
                    try {
                        IStackFrame[] frames = thread.getStackFrames();
                        if (frames != null && frames.length > 0) {
                            int index = Math.max(0, Math.min(frameIndex, frames.length - 1));
                            frame = frames[index];
                        }
                    } catch (DebugException ignored) {}
                    DebugContextPicker.Selection selection = new DebugContextPicker.Selection(
                            launch, target, thread, frame, null, null,
                            "sessionSelector:target");
                    if (thread.isSuspended()) return selection;
                    if (fallback == null) fallback = selection;
                }
                if (fallback != null) return fallback;
            }
        } catch (DebugException ignored) {}
        return new DebugContextPicker.Selection(launch, target, null, null,
                null, null, "sessionSelector:target");
    }

    private static void collectContainerChildren(IRunControl runControl, IDMContext context,
                                                 AtomicReference<List<IRunControl.IExecutionDMContext>> result,
                                                 CountDownLatch latch, DsfSession session) {
        IRunControl.IContainerDMContext container =
                DMContexts.getAncestorOfType(context, IRunControl.IContainerDMContext.class);
        if (container == null) {
            result.set(Collections.emptyList());
            latch.countDown();
            return;
        }
        runControl.getExecutionContexts(container,
                new DataRequestMonitor<IRunControl.IExecutionDMContext[]>(
                        session.getExecutor(), null) {
            @Override protected void handleCompleted() {
                try {
                    List<IRunControl.IExecutionDMContext> values = new ArrayList<>();
                    if (isSuccess() && getData() != null) {
                        Collections.addAll(values, getData());
                    }
                    result.set(values);
                } finally {
                    latch.countDown();
                }
            }
        });
    }

    private static List<IRunControl.IExecutionDMContext> executionsFrom(IDMContext[] contexts) {
        List<IRunControl.IExecutionDMContext> out = new ArrayList<>();
        if (contexts == null) return out;
        for (IDMContext context : contexts) {
            IRunControl.IExecutionDMContext execution = executionFrom(context);
            if (execution != null && !out.contains(execution)) out.add(execution);
        }
        return out;
    }

    private static IRunControl.IExecutionDMContext executionFrom(IDMContext context) {
        if (context instanceof IRunControl.IExecutionDMContext) {
            return (IRunControl.IExecutionDMContext) context;
        }
        return context != null
                ? DMContexts.getAncestorOfType(context,
                        IRunControl.IExecutionDMContext.class)
                : null;
    }

    private static boolean stateMatches(IRunControl runControl,
                                        IRunControl.IExecutionDMContext execution,
                                        Boolean requireSuspended) {
        if (requireSuspended == null) return true;
        try {
            return runControl.isSuspended(execution) == requireSuspended.booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static IRunControl.IExecutionDMContext selectByState(
            DsfSession session, IRunControl runControl,
            List<IRunControl.IExecutionDMContext> values,
            Boolean requireSuspended) throws InterruptedException {
        if (values == null || values.isEmpty()) return null;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<IRunControl.IExecutionDMContext> selected = new AtomicReference<>();
        session.getExecutor().execute(new Runnable() {
            @Override public void run() {
                try {
                    for (IRunControl.IExecutionDMContext value : values) {
                        if (stateMatches(runControl, value, requireSuspended)) {
                            selected.set(value);
                            return;
                        }
                    }
                    if (requireSuspended == null) selected.set(values.get(0));
                } finally {
                    latch.countDown();
                }
            }
        });
        return latch.await(2, TimeUnit.SECONDS) ? selected.get() : null;
    }
}
