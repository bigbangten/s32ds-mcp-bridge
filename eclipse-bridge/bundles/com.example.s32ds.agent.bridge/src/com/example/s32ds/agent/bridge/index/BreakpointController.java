package com.example.s32ds.agent.bridge.index;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.core.resources.IMarker;
import org.osgi.framework.Bundle;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Breakpoint set/clear. Set is C/C++ line breakpoints via reflection on
 * {@code org.eclipse.cdt.debug.core.CDIDebugModel}. We use reflection so the
 * bundle still resolves on a plain Eclipse without CDT installed — in that
 * case set() returns 501-style "CDT not available", but everything else
 * keeps working.
 */
public final class BreakpointController {

    /**
     * Add a C/C++ line breakpoint.
     *
     * @param fileSpec      either a workspace-relative path (e.g. "/myproj/src/main.c") or
     *                      an absolute filesystem path. Required.
     * @param line          1-based line number. Required.
     * @param condition     optional GDB conditional expression (e.g. "i == 5"). May be null/empty.
     */
    public Map<String, Object> setLineBreakpoint(String fileSpec, int line, String condition) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (fileSpec == null || fileSpec.isEmpty() || line <= 0) {
            out.put("ok", false);
            out.put("error", "fileSpec and positive line are required");
            return out;
        }
        // OSGi-friendly cross-bundle class load. Class.forName uses our own bundle's
        // classloader and won't see CDT's classes. We have to ask the CDT bundle directly.
        Class<?> cdiDebugModel;
        try {
            Bundle cdtBundle = Platform.getBundle("org.eclipse.cdt.debug.core");
            if (cdtBundle == null) {
                out.put("ok", false);
                out.put("error", "CDT bundle org.eclipse.cdt.debug.core not present in this Eclipse install");
                return out;
            }
            cdiDebugModel = cdtBundle.loadClass("org.eclipse.cdt.debug.core.CDIDebugModel");
        } catch (ClassNotFoundException e) {
            out.put("ok", false);
            out.put("error", "CDT installed but CDIDebugModel class not found: " + e.getMessage());
            return out;
        }

        IResource resource = resolveResource(fileSpec);
        if (resource == null) {
            out.put("ok", false);
            out.put("error", "could not resolve file in workspace: " + fileSpec);
            return out;
        }
        // sourceHandle: CDT convention is the absolute filesystem path string.
        String sourceHandle;
        IPath loc = resource.getLocation();
        sourceHandle = loc != null ? loc.toOSString() : fileSpec;

        try {
            // Try the most common signature first:
            //   createLineBreakpoint(String sourceHandle, IResource resource, int type,
            //                        int lineNumber, boolean enabled, int ignoreCount,
            //                        String condition, boolean register)
            // type=0 means "regular" line breakpoint in CDT.
            Method m = findCreateLineBreakpoint(cdiDebugModel);
            if (m == null) {
                out.put("ok", false);
                out.put("error", "no compatible CDIDebugModel.createLineBreakpoint signature found");
                return out;
            }
            Object[] args = buildArgs(m, sourceHandle, resource, line, condition);
            Object bp = m.invoke(null, args);
            out.put("ok", true);
            out.put("sourceHandle", sourceHandle);
            out.put("line", line);
            if (condition != null && !condition.isEmpty()) out.put("condition", condition);
            out.put("breakpointClass", bp == null ? null : bp.getClass().getName());
            return out;
        } catch (Throwable t) {
            // Unwrap reflection wrapper
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            out.put("ok", false);
            out.put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return out;
        }
    }

    /** Remove a breakpoint by its workspace-marker line + resource path, or by index. */
    public Map<String, Object> clearLineBreakpoint(String fileSpec, int line) {
        Map<String, Object> out = new LinkedHashMap<>();
        IBreakpointManager mgr = DebugPlugin.getDefault().getBreakpointManager();
        IResource resource = fileSpec == null ? null : resolveResource(fileSpec);
        for (IBreakpoint bp : mgr.getBreakpoints()) {
            IMarker mk = bp.getMarker();
            if (mk == null) continue;
            int bLine = mk.getAttribute(IMarker.LINE_NUMBER, -1);
            IResource bRes = mk.getResource();
            boolean lineMatch = bLine == line;
            boolean resMatch = resource == null
                    || (bRes != null && bRes.equals(resource))
                    || (bRes != null && fileSpec != null
                        && (bRes.getFullPath().toString().equals(fileSpec)
                            || (bRes.getLocation() != null
                                && bRes.getLocation().toOSString().equals(fileSpec))));
            if (lineMatch && resMatch) {
                try {
                    mgr.removeBreakpoint(bp, true);
                    out.put("ok", true);
                    out.put("line", line);
                    out.put("resource", bRes == null ? null : bRes.getFullPath().toString());
                    return out;
                } catch (org.eclipse.core.runtime.CoreException e) {
                    out.put("ok", false);
                    out.put("error", e.getMessage());
                    return out;
                }
            }
        }
        out.put("ok", false);
        out.put("error", "no matching breakpoint at " + (fileSpec == null ? "*" : fileSpec) + ":" + line);
        return out;
    }

    /** Clear ALL breakpoints (handy emergency reset; danger-gated like everything else). */
    public Map<String, Object> clearAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        IBreakpointManager mgr = DebugPlugin.getDefault().getBreakpointManager();
        IBreakpoint[] all = mgr.getBreakpoints();
        try {
            mgr.removeBreakpoints(all, true);
            out.put("ok", true);
            out.put("removed", all.length);
            return out;
        } catch (org.eclipse.core.runtime.CoreException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    // ────────── helpers ──────────

    private IResource resolveResource(String fileSpec) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        // Workspace-relative? Starts with "/" and the segment is a project.
        if (fileSpec.startsWith("/")) {
            IFile file = root.getFile(new Path(fileSpec));
            if (file != null && file.exists()) return file;
        }
        // Absolute filesystem path? Try to map back via root.findFilesForLocationURI.
        try {
            java.io.File fs = new java.io.File(fileSpec);
            if (fs.exists()) {
                IFile[] files = root.findFilesForLocationURI(fs.toURI());
                if (files.length > 0) return files[0];
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Method findCreateLineBreakpoint(Class<?> cdiDebugModel) {
        // CDT has historically had several overloads. We try them by parameter count
        // and accept any whose name is createLineBreakpoint.
        for (Method m : cdiDebugModel.getMethods()) {
            if (!"createLineBreakpoint".equals(m.getName())) continue;
            // We need at least String + IResource + int(line). Skip stranger overloads.
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 4 && ps[0] == String.class && IResource.class.isAssignableFrom(ps[1])) {
                return m;
            }
        }
        return null;
    }

    private Object[] buildArgs(Method m, String sourceHandle, IResource resource, int line, String condition) {
        Class<?>[] ps = m.getParameterTypes();
        Object[] args = new Object[ps.length];
        // We fill positional parameters using best-known CDT layouts.
        // Layout A (8 args, modern CDT 11+):
        //   String sourceHandle, IResource resource, int type, int lineNumber,
        //   boolean enabled, int ignoreCount, String condition, boolean register
        // Layout B (7 args, older):
        //   String sourceHandle, IResource resource, int lineNumber, boolean enabled,
        //   int ignoreCount, String condition, boolean register
        // Layout C (6 args, oldest):
        //   String sourceHandle, IResource resource, int lineNumber, boolean enabled,
        //   int ignoreCount, String condition
        boolean hasType = ps.length >= 8;
        int idx = 0;
        args[idx++] = sourceHandle;        // String
        args[idx++] = resource;            // IResource
        if (hasType) args[idx++] = Integer.valueOf(0); // type=REGULAR
        args[idx++] = Integer.valueOf(line);
        args[idx++] = Boolean.TRUE;        // enabled
        args[idx++] = Integer.valueOf(0);  // ignoreCount
        if (idx < ps.length) args[idx++] = condition == null ? "" : condition;
        if (idx < ps.length) args[idx++] = Boolean.TRUE; // register
        // Pad anything remaining with nulls / sensible defaults
        while (idx < ps.length) {
            Class<?> t = ps[idx];
            if (t == boolean.class) args[idx] = Boolean.FALSE;
            else if (t == int.class) args[idx] = Integer.valueOf(0);
            else args[idx] = null;
            idx++;
        }
        return args;
    }
}
