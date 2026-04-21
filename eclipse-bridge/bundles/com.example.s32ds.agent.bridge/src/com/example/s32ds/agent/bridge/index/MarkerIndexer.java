package com.example.s32ds.agent.bridge.index;

import com.example.s32ds.agent.bridge.util.UiThread;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MarkerIndexer {

    /** Returns Problems view equivalent markers, optionally filtered by project name. */
    public Map<String, Object> listProblems(String projectName) {
        return UiThread.sync(() -> {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IResource scope;
            if (projectName != null && !projectName.isEmpty()) {
                IProject p = root.getProject(projectName);
                if (p == null || !p.exists() || !p.isAccessible()) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("project", projectName);
                    out.put("count", 0);
                    out.put("markers", new ArrayList<>());
                    out.put("warnings", List.of("project not found or closed"));
                    return out;
                }
                scope = p;
            } else {
                scope = root;
            }

            IMarker[] markers;
            try {
                markers = scope.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            } catch (CoreException e) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("error", e.getMessage());
                out.put("markers", new ArrayList<>());
                return out;
            }

            List<Map<String, Object>> rows = new ArrayList<>(markers.length);
            for (IMarker m : markers) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("severity", severityName(m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)));
                row.put("message", m.getAttribute(IMarker.MESSAGE, ""));
                IResource r = m.getResource();
                row.put("resource", r != null ? r.getFullPath().toString() : null);
                row.put("project", r != null && r.getProject() != null ? r.getProject().getName() : null);
                row.put("line", m.getAttribute(IMarker.LINE_NUMBER, -1));
                try {
                    row.put("type", m.getType());
                } catch (CoreException ignored) {
                    row.put("type", null);
                }
                row.put("id", m.getId());
                row.put("sourceId", m.getAttribute(IMarker.SOURCE_ID, null));
                rows.add(row);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("project", projectName);
            out.put("count", rows.size());
            out.put("markers", rows);
            // severity 집계
            Map<String, Integer> bySeverity = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String sv = String.valueOf(row.get("severity"));
                bySeverity.merge(sv, 1, Integer::sum);
            }
            out.put("bySeverity", bySeverity);
            return out;
        });
    }

    private String severityName(int s) {
        switch (s) {
            case IMarker.SEVERITY_ERROR:   return "ERROR";
            case IMarker.SEVERITY_WARNING: return "WARNING";
            case IMarker.SEVERITY_INFO:    return "INFO";
            default:                       return "UNKNOWN";
        }
    }
}
