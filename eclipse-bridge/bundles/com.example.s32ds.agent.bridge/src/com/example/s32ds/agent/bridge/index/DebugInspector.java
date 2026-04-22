package com.example.s32ds.agent.bridge.index;

import org.eclipse.core.resources.IMarker;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IMemoryBlockExtension;
import org.eclipse.debug.core.model.IMemoryBlockRetrieval;
import org.eclipse.debug.core.model.IRegister;
import org.eclipse.debug.core.model.IRegisterGroup;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

import java.math.BigInteger;
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

    /** Lightweight status: is anything being debugged? anything halted? */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        ILaunch[] launches = mgr.getLaunches();
        int live = 0, halted = 0, running = 0, terminated = 0;
        List<Map<String, Object>> summary = new ArrayList<>();
        for (ILaunch launch : launches) {
            boolean term = launch.isTerminated();
            if (term) { terminated++; continue; }
            live++;
            Map<String, Object> row = new LinkedHashMap<>();
            try {
                row.put("configName", launch.getLaunchConfiguration() != null
                        ? launch.getLaunchConfiguration().getName() : null);
            } catch (Throwable ignored) { row.put("configName", null); }
            row.put("mode", launch.getLaunchMode());
            boolean anyHalted = false;
            for (IDebugTarget t : launch.getDebugTargets()) {
                try { if (t.isSuspended()) { anyHalted = true; break; } } catch (Throwable ignored) {}
            }
            row.put("halted", anyHalted);
            if (anyHalted) halted++; else running++;
            summary.add(row);
        }
        out.put("anyLive", live > 0);
        out.put("anyHalted", halted > 0);
        out.put("liveLaunches", live);
        out.put("haltedLaunches", halted);
        out.put("runningLaunches", running);
        out.put("terminatedLaunches", terminated);
        out.put("launches", summary);
        return out;
    }

    /**
     * Current location for the first suspended thread: PC, source file, line, function.
     * Returns {halted:false} when nothing is suspended.
     */
    public Map<String, Object> location() {
        Map<String, Object> out = new LinkedHashMap<>();
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        for (ILaunch launch : mgr.getLaunches()) {
            if (launch.isTerminated()) continue;
            for (IDebugTarget t : launch.getDebugTargets()) {
                try {
                    if (!t.isSuspended()) continue;
                    for (IThread th : t.getThreads()) {
                        if (!th.isSuspended()) continue;
                        IStackFrame[] fs = th.getStackFrames();
                        if (fs.length == 0) continue;
                        IStackFrame top = fs[0];
                        out.put("halted", true);
                        try { out.put("configName", launch.getLaunchConfiguration() != null
                                ? launch.getLaunchConfiguration().getName() : null); }
                        catch (Throwable ignored) { out.put("configName", null); }
                        try { out.put("targetName", t.getName()); } catch (Throwable ignored) {}
                        try { out.put("threadName", th.getName()); } catch (Throwable ignored) {}
                        out.put("frameCount", fs.length);
                        out.put("function", safeName(top));
                        try { out.put("lineNumber", top.getLineNumber()); } catch (DebugException ignored) {}
                        try { out.put("charStart", top.getCharStart()); } catch (DebugException ignored) {}
                        try { out.put("charEnd", top.getCharEnd()); } catch (DebugException ignored) {}
                        // Best-effort source path — lookup through the launch's source locator
                        try {
                            Object el = launch.getSourceLocator() != null
                                    ? launch.getSourceLocator().getSourceElement(top) : null;
                            if (el != null) {
                                out.put("sourceElementClass", el.getClass().getName());
                                out.put("sourceElement", String.valueOf(el));
                            }
                        } catch (Throwable tex) { out.put("sourceLookupError", tex.getMessage()); }
                        return out;
                    }
                } catch (DebugException ignored) {}
            }
        }
        out.put("halted", false);
        return out;
    }

    /**
     * Register groups + register values for the first suspended thread's top frame.
     * Generic Debug API (works for CDT standard; some DSF-only registers may be async-only).
     */
    public Map<String, Object> registers() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        outer:
        for (ILaunch launch : mgr.getLaunches()) {
            if (launch.isTerminated()) continue;
            for (IDebugTarget t : launch.getDebugTargets()) {
                try {
                    if (!t.isSuspended()) continue;
                    for (IThread th : t.getThreads()) {
                        if (!th.isSuspended()) continue;
                        IStackFrame[] fs = th.getStackFrames();
                        if (fs.length == 0) continue;
                        IStackFrame top = fs[0];
                        out.put("halted", true);
                        out.put("frameName", safeName(top));
                        if (!top.hasRegisterGroups()) {
                            out.put("hasRegisterGroups", false);
                            out.put("groups", groups);
                            return out;
                        }
                        out.put("hasRegisterGroups", true);
                        for (IRegisterGroup g : top.getRegisterGroups()) {
                            Map<String, Object> grow = new LinkedHashMap<>();
                            try { grow.put("name", g.getName()); } catch (DebugException ignored) {}
                            List<Map<String, Object>> regs = new ArrayList<>();
                            try {
                                for (IRegister r : g.getRegisters()) {
                                    Map<String, Object> rr = new LinkedHashMap<>();
                                    try { rr.put("name", r.getName()); } catch (DebugException ignored) {}
                                    try { rr.put("type", r.getReferenceTypeName()); } catch (DebugException ignored) {}
                                    try {
                                        IValue v = r.getValue();
                                        if (v != null) rr.put("value", v.getValueString());
                                    } catch (DebugException e) {
                                        rr.put("valueError", e.getMessage());
                                    }
                                    regs.add(rr);
                                }
                            } catch (DebugException e) {
                                grow.put("regsError", e.getMessage());
                            }
                            grow.put("registers", regs);
                            groups.add(grow);
                        }
                        break outer;
                    }
                } catch (DebugException ignored) {}
            }
        }
        if (!out.containsKey("halted")) out.put("halted", false);
        out.put("groups", groups);
        return out;
    }

    /**
     * Reads a block of target memory starting at {@code addr} (hex string or decimal) for {@code length} bytes.
     * Requires a suspended debug target that adapts to IMemoryBlockRetrieval.
     * Never writes. Length capped to 4096 bytes to avoid stalling the target.
     */
    public Map<String, Object> readMemory(String addrStr, int length) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (addrStr == null || addrStr.isEmpty()) {
            out.put("error", "addr is required");
            return out;
        }
        int cap = Math.max(1, Math.min(length <= 0 ? 64 : length, 4096));
        BigInteger addr;
        try {
            String s = addrStr.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) addr = new BigInteger(s.substring(2), 16);
            else if (s.matches("^[0-9a-fA-F]+$") && s.length() >= 3) addr = new BigInteger(s, 16);
            else addr = new BigInteger(s);
        } catch (Exception e) {
            out.put("error", "invalid addr: " + addrStr);
            return out;
        }
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        for (ILaunch launch : mgr.getLaunches()) {
            if (launch.isTerminated()) continue;
            for (IDebugTarget t : launch.getDebugTargets()) {
                try {
                    if (!t.isSuspended()) continue;
                    IMemoryBlockRetrieval ret = t.getAdapter(IMemoryBlockRetrieval.class);
                    if (ret == null) {
                        Object o = t.getAdapter(IMemoryBlockRetrieval.class);
                        if (o instanceof IMemoryBlockRetrieval) ret = (IMemoryBlockRetrieval) o;
                    }
                    if (ret == null) continue;
                    try { out.put("targetName", t.getName()); } catch (DebugException ignored) {}
                    out.put("addr", "0x" + addr.toString(16));
                    out.put("requestedLength", cap);
                    byte[] data = null;
                    if (ret instanceof org.eclipse.debug.core.model.IMemoryBlockRetrievalExtension) {
                        IMemoryBlockExtension mb = ((org.eclipse.debug.core.model.IMemoryBlockRetrievalExtension) ret)
                                .getExtendedMemoryBlock("0x" + addr.toString(16), t);
                        if (mb != null) {
                            org.eclipse.debug.core.model.MemoryByte[] mbytes = mb.getBytesFromAddress(addr, cap);
                            data = new byte[mbytes.length];
                            for (int i = 0; i < mbytes.length; i++) data[i] = mbytes[i].getValue();
                        }
                    }
                    if (data == null) {
                        IMemoryBlock mb = ret.getMemoryBlock(addr.longValueExact(), cap);
                        if (mb != null) data = mb.getBytes();
                    }
                    if (data == null) {
                        out.put("error", "target returned no memory bytes");
                        return out;
                    }
                    StringBuilder hex = new StringBuilder(data.length * 2);
                    for (byte b : data) hex.append(String.format("%02x", b & 0xff));
                    out.put("byteCount", data.length);
                    out.put("hex", hex.toString());
                    return out;
                } catch (Throwable ex) {
                    out.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    return out;
                }
            }
        }
        out.put("error", "no suspended debug target available");
        return out;
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
