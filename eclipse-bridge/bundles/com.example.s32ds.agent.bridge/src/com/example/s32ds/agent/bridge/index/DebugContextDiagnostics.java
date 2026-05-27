package com.example.s32ds.agent.bridge.index;

import com.example.s32ds.agent.bridge.util.UiThread;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IStep;
import org.eclipse.debug.core.model.ISuspendResume;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only diagnostics for Eclipse/S32DS debug context resolution. */
public final class DebugContextDiagnostics {
    private static final String DEBUG_VIEW_ID = "org.eclipse.debug.ui.DebugView";

    public Map<String, Object> inspect() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("picker", summarizePicker());
        out.put("launches", summarizeLaunches());
        out.put("workbench", UiThread.sync(this::summarizeWorkbench));
        return out;
    }

    private Map<String, Object> summarizePicker() {
        Map<String, Object> out = new LinkedHashMap<>();
        DebugContextPicker.Selection sel = DebugContextPicker.activeForDiagnostics(0);
        out.put("found", sel != null);
        if (sel != null) {
            out.put("source", sel.source);
            out.put("launch", brief(sel.launch));
            out.put("target", brief(sel.target));
            out.put("thread", brief(sel.thread));
            out.put("frame", brief(sel.frame));
        }
        return out;
    }

    private List<Map<String, Object>> summarizeLaunches() {
        List<Map<String, Object>> rows = new ArrayList<>();
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        for (ILaunch launch : mgr.getLaunches()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("class", launch.getClass().getName());
            row.put("mode", launch.getLaunchMode());
            row.put("terminated", launch.isTerminated());
            try { row.put("configName", launch.getLaunchConfiguration() != null ? launch.getLaunchConfiguration().getName() : null); }
            catch (Throwable ignored) { row.put("configName", null); }
            IDebugTarget[] targets = launch.getDebugTargets();
            row.put("debugTargetCount", targets != null ? targets.length : 0);
            List<Map<String, Object>> targetRows = new ArrayList<>();
            if (targets != null) {
                for (IDebugTarget target : targets) targetRows.add(brief(target));
            }
            row.put("targets", targetRows);
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> summarizeWorkbench() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!PlatformUI.isWorkbenchRunning()) {
            out.put("running", false);
            return out;
        }
        out.put("running", true);
        IWorkbenchWindow window = activeWindow();
        out.put("window", briefWindow(window));
        out.put("debugUiToolsContext", describeObject(safeDebugUiToolsContext(), 2));
        out.put("debugContextService", describeDebugContextService(window));
        out.put("debugView", describeDebugView(window));
        return out;
    }

    private Object safeDebugUiToolsContext() {
        try { return DebugUITools.getDebugContext(); }
        catch (Throwable t) { return errorMap(t); }
    }

    private Map<String, Object> describeDebugContextService(IWorkbenchWindow window) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (window == null) {
            out.put("available", false);
            out.put("error", "no active workbench window");
            return out;
        }
        try {
            Object manager = DebugUITools.class.getMethod("getDebugContextManager").invoke(null);
            Object service = manager.getClass().getMethod("getContextService", IWorkbenchWindow.class)
                    .invoke(manager, window);
            Object active = service.getClass().getMethod("getActiveContext").invoke(service);
            out.put("available", true);
            out.put("managerClass", manager != null ? manager.getClass().getName() : null);
            out.put("serviceClass", service != null ? service.getClass().getName() : null);
            out.put("activeContext", describeObject(active, 2));
        } catch (Throwable t) {
            out.put("available", false);
            out.put("error", throwableMessage(t));
        }
        return out;
    }

    private Map<String, Object> describeDebugView(IWorkbenchWindow window) {
        Map<String, Object> out = new LinkedHashMap<>();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;
        if (page == null) {
            out.put("found", false);
            out.put("error", "no active page");
            return out;
        }
        try {
            IWorkbenchPart activePart = page.getActivePart();
            out.put("activePartId", activePart != null && activePart.getSite() != null ? activePart.getSite().getId() : null);
            out.put("activePartTitle", activePart != null ? activePart.getTitle() : null);
        } catch (Throwable ignored) {}
        IViewPart view = page.findView(DEBUG_VIEW_ID);
        out.put("found", view != null);
        if (view == null) return out;
        out.put("class", view.getClass().getName());
        try { out.put("title", view.getTitle()); } catch (Throwable ignored) {}
        try { out.put("siteId", view.getSite() != null ? view.getSite().getId() : null); } catch (Throwable ignored) {}
        ISelectionProvider provider = view.getSite() != null ? view.getSite().getSelectionProvider() : null;
        out.put("selectionProviderClass", provider != null ? provider.getClass().getName() : null);
        ISelection selection = provider != null ? provider.getSelection() : null;
        out.put("selection", describeObject(selection, 3));
        return out;
    }

    private IWorkbenchWindow activeWindow() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window != null) return window;
        IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
        return windows != null && windows.length > 0 ? windows[0] : null;
    }

    private Map<String, Object> briefWindow(IWorkbenchWindow window) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("present", window != null);
        if (window == null) return row;
        try { row.put("class", window.getClass().getName()); } catch (Throwable ignored) {}
        try { row.put("shellText", window.getShell() != null ? window.getShell().getText() : null); } catch (Throwable ignored) {}
        return row;
    }

    private Map<String, Object> describeObject(Object object, int depth) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (object == null) {
            row.put("present", false);
            return row;
        }
        if (object instanceof Map<?, ?> && ((Map<?, ?>) object).containsKey("error")) {
            row.put("present", false);
            row.put("error", ((Map<?, ?>) object).get("error"));
            return row;
        }
        row.put("present", true);
        row.put("class", object.getClass().getName());
        row.put("string", safeString(object));
        row.put("interfaces", interfacesOf(object));
        row.put("instanceOfIAdaptable", object instanceof IAdaptable);
        row.put("adapters", adapterSummary(object));
        Object dmContext = reflectNoArg(object, "getDMContext");
        if (dmContext != null) row.put("dmContext", describeDmContext(dmContext));
        if (object instanceof IStructuredSelection && depth > 0) {
            IStructuredSelection selection = (IStructuredSelection) object;
            row.put("structuredSize", selection.size());
            List<Map<String, Object>> elements = new ArrayList<>();
            for (Object element : selection.toArray()) elements.add(describeObject(element, depth - 1));
            row.put("elements", elements);
        } else if (object instanceof ISelection) {
            try { row.put("selectionEmpty", ((ISelection) object).isEmpty()); } catch (Throwable ignored) {}
        }
        return row;
    }

    private List<String> interfacesOf(Object object) {
        List<String> names = new ArrayList<>();
        Class<?> type = object.getClass();
        while (type != null && names.size() < 16) {
            for (Class<?> itf : type.getInterfaces()) {
                if (names.size() >= 16) break;
                names.add(itf.getName());
            }
            type = type.getSuperclass();
        }
        return names;
    }

    private Map<String, Object> adapterSummary(Object object) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("IStackFrame", brief(adapt(object, IStackFrame.class)));
        out.put("IThread", brief(adapt(object, IThread.class)));
        out.put("IDebugTarget", brief(adapt(object, IDebugTarget.class)));
        out.put("ILaunch", brief(adapt(object, ILaunch.class)));
        out.put("ISuspendResume", brief(adapt(object, ISuspendResume.class)));
        out.put("IStep", brief(adapt(object, IStep.class)));
        return out;
    }

    private Map<String, Object> brief(Object object) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("present", object != null);
        if (object == null) return row;
        row.put("class", object.getClass().getName());
        row.put("string", safeString(object));
        try {
            if (object instanceof ILaunch) {
                ILaunch launch = (ILaunch) object;
                row.put("mode", launch.getLaunchMode());
                row.put("terminated", launch.isTerminated());
                row.put("debugTargetCount", launch.getDebugTargets() != null ? launch.getDebugTargets().length : 0);
                row.put("configName", launch.getLaunchConfiguration() != null ? launch.getLaunchConfiguration().getName() : null);
            }
            if (object instanceof IDebugTarget) {
                IDebugTarget target = (IDebugTarget) object;
                row.put("name", target.getName());
                row.put("terminated", target.isTerminated());
                row.put("suspended", target.isSuspended());
                row.put("canResume", target.canResume());
                row.put("canSuspend", target.canSuspend());
                row.put("threadCount", target.getThreads() != null ? target.getThreads().length : 0);
            }
            if (object instanceof IThread) {
                IThread thread = (IThread) object;
                row.put("name", thread.getName());
                row.put("terminated", thread.isTerminated());
                row.put("suspended", thread.isSuspended());
                row.put("canResume", thread.canResume());
                row.put("canSuspend", thread.canSuspend());
                row.put("frameCount", thread.getStackFrames() != null ? thread.getStackFrames().length : 0);
            }
            if (object instanceof IStackFrame) {
                IStackFrame frame = (IStackFrame) object;
                row.put("name", frame.getName());
                row.put("lineNumber", frame.getLineNumber());
                row.put("charStart", frame.getCharStart());
                row.put("charEnd", frame.getCharEnd());
            }
            if (object instanceof ISuspendResume) {
                ISuspendResume sr = (ISuspendResume) object;
                row.put("isSuspended", sr.isSuspended());
                row.put("canResume", sr.canResume());
                row.put("canSuspend", sr.canSuspend());
            }
        } catch (DebugException e) {
            row.put("debugError", e.getMessage());
        } catch (Throwable t) {
            row.put("error", throwableMessage(t));
        }
        if (object instanceof IDebugElement) {
            try { row.put("modelIdentifier", ((IDebugElement) object).getModelIdentifier()); } catch (Throwable ignored) {}
        }
        Object dmContext = reflectNoArg(object, "getDMContext");
        if (dmContext != null) row.put("dmContext", describeDmContext(dmContext));
        return row;
    }

    private Map<String, Object> describeDmContext(Object dmContext) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("class", dmContext.getClass().getName());
        row.put("string", safeString(dmContext));
        Object sessionId = reflectNoArg(dmContext, "getSessionId");
        if (sessionId != null) row.put("sessionId", safeString(sessionId));
        Object parents = reflectNoArg(dmContext, "getParents");
        if (parents != null && parents.getClass().isArray()) {
            List<Map<String, Object>> parentRows = new ArrayList<>();
            int len = java.lang.reflect.Array.getLength(parents);
            for (int i = 0; i < len && i < 8; i++) {
                Object parent = java.lang.reflect.Array.get(parents, i);
                Map<String, Object> prow = new LinkedHashMap<>();
                prow.put("class", parent != null ? parent.getClass().getName() : null);
                prow.put("string", safeString(parent));
                Object sid = reflectNoArg(parent, "getSessionId");
                if (sid != null) prow.put("sessionId", safeString(sid));
                parentRows.add(prow);
            }
            row.put("parents", parentRows);
        }
        return row;
    }

    private Object reflectNoArg(Object object, String methodName) {
        if (object == null) return null;
        try {
            Method m = object.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private <T> T adapt(Object object, Class<T> type) {
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

    private Map<String, Object> errorMap(Throwable t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("error", throwableMessage(t));
        return row;
    }

    private String safeString(Object object) {
        if (object == null) return null;
        try { return String.valueOf(object); }
        catch (Throwable t) { return "<toString failed: " + throwableMessage(t) + ">"; }
    }

    private String throwableMessage(Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}