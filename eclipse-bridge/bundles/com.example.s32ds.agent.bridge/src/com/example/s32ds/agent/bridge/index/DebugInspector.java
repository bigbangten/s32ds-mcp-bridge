package com.example.s32ds.agent.bridge.index;

import org.eclipse.core.resources.IMarker;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only inspection of active debug sessions, stack, variables, breakpoints.
 * Does not step, resume, suspend, or set breakpoints.
 */
public final class DebugInspector {

    /** Returns overview of all active debug launches. */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> out = new ArrayList<>();
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        for (ILaunch launch : mgr.getLaunches()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mode", launch.getLaunchMode());
            row.put("terminated", launch.isTerminated());
            try {
                row.put("configName", launch.getLaunchConfiguration() != null
                        ? launch.getLaunchConfiguration().getName() : null);
            } catch (Throwable ignored) {
                row.put("configName", null);
            }
            List<Map<String, Object>> targets = new ArrayList<>();
            for (IDebugTarget t : launch.getDebugTargets()) {
                Map<String, Object> tr = new LinkedHashMap<>();
                try { tr.put("name", t.getName()); } catch (DebugException e) { tr.put("name", null); }
                tr.put("terminated", t.isTerminated());
                tr.put("suspended", t.isSuspended());
                tr.put("disconnected", t.isDisconnected());
                // threads
                List<Map<String, Object>> threads = new ArrayList<>();
                try {
                    for (IThread th : t.getThreads()) {
                        threads.add(summarizeThread(th));
                    }
                } catch (DebugException e) {
                    tr.put("threadsError", e.getMessage());
                }
                tr.put("threads", threads);
                targets.add(tr);
            }
            row.put("targets", targets);
            out.add(row);
        }
        return out;
    }

    /** Stack frames for the currently suspended thread of the first suspended target. */
    public Map<String, Object> stackFrames() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> frames = new ArrayList<>();
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        outer:
        for (ILaunch launch : mgr.getLaunches()) {
            if (launch.isTerminated()) continue;
            for (IDebugTarget t : launch.getDebugTargets()) {
                if (!t.isSuspended()) continue;
                try {
                    for (IThread th : t.getThreads()) {
                        if (!th.isSuspended()) continue;
                        result.put("threadName", safeName(th));
                        for (IStackFrame f : th.getStackFrames()) {
                            frames.add(summarizeFrame(f));
                        }
                        break outer;
                    }
                } catch (DebugException ignored) {}
            }
        }
        result.put("frames", frames);
        result.put("frameCount", frames.size());
        return result;
    }

    /** Variables for the first suspended thread's topmost stack frame. */
    public Map<String, Object> variables(int frameIndex) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> vars = new ArrayList<>();
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        outer:
        for (ILaunch launch : mgr.getLaunches()) {
            if (launch.isTerminated()) continue;
            for (IDebugTarget t : launch.getDebugTargets()) {
                if (!t.isSuspended()) continue;
                try {
                    for (IThread th : t.getThreads()) {
                        if (!th.isSuspended()) continue;
                        IStackFrame[] fs = th.getStackFrames();
                        if (fs.length == 0) continue;
                        int idx = Math.max(0, Math.min(frameIndex, fs.length - 1));
                        result.put("frameIndex", idx);
                        result.put("frameName", safeName(fs[idx]));
                        for (IVariable v : fs[idx].getVariables()) {
                            vars.add(summarizeVariable(v, 1));
                        }
                        break outer;
                    }
                } catch (DebugException ignored) {}
            }
        }
        result.put("variables", vars);
        return result;
    }

    /** All breakpoints (any type) registered in the workspace. */
    public List<Map<String, Object>> breakpoints() {
        List<Map<String, Object>> out = new ArrayList<>();
        IBreakpoint[] bps = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints();
        for (IBreakpoint bp : bps) {
            Map<String, Object> row = new LinkedHashMap<>();
            IMarker m = bp.getMarker();
            if (m != null) {
                row.put("resource", m.getResource() != null ? m.getResource().getFullPath().toString() : null);
                row.put("line", m.getAttribute(IMarker.LINE_NUMBER, -1));
                try { row.put("type", m.getType()); } catch (Throwable t) { row.put("type", null); }
            }
            try { row.put("enabled", bp.isEnabled()); } catch (Throwable t) { row.put("enabled", null); }
            try { row.put("registered", bp.isRegistered()); } catch (Throwable t) { row.put("registered", null); }
            try { row.put("persisted", bp.isPersisted()); } catch (Throwable t) { row.put("persisted", null); }
            row.put("modelIdentifier", bp.getModelIdentifier());
            out.add(row);
        }
        return out;
    }

    // ───────────────────── helpers ─────────────────────

    private Map<String, Object> summarizeThread(IThread th) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", safeName(th));
        row.put("suspended", th.isSuspended());
        row.put("terminated", th.isTerminated());
        try { row.put("priority", th.getPriority()); } catch (Throwable ignored) {}
        try { row.put("breakpointCount", th.getBreakpoints() != null ? th.getBreakpoints().length : 0); }
        catch (Throwable ignored) {}
        return row;
    }

    private Map<String, Object> summarizeFrame(IStackFrame f) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", safeName(f));
        try { row.put("lineNumber", f.getLineNumber()); } catch (DebugException ignored) {}
        try { row.put("charStart", f.getCharStart()); } catch (DebugException ignored) {}
        return row;
    }

    private Map<String, Object> summarizeVariable(IVariable v, int depthRemaining) {
        Map<String, Object> row = new LinkedHashMap<>();
        try { row.put("name", v.getName()); } catch (DebugException ignored) {}
        try { row.put("refType", v.getReferenceTypeName()); } catch (DebugException ignored) {}
        try {
            IValue val = v.getValue();
            if (val != null) {
                row.put("valueType", val.getReferenceTypeName());
                row.put("valueString", val.getValueString());
                if (depthRemaining > 0 && val.hasVariables()) {
                    List<Map<String, Object>> children = new ArrayList<>();
                    for (IVariable child : val.getVariables()) {
                        children.add(summarizeVariable(child, depthRemaining - 1));
                    }
                    row.put("children", children);
                }
            }
        } catch (DebugException e) {
            row.put("valueError", e.getMessage());
        }
        return row;
    }

    private String safeName(Object o) {
        try {
            if (o instanceof IThread) return ((IThread) o).getName();
            if (o instanceof IStackFrame) return ((IStackFrame) o).getName();
        } catch (DebugException ignored) {}
        return null;
    }
}
