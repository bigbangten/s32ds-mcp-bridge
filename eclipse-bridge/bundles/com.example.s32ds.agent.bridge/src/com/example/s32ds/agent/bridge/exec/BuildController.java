package com.example.s32ds.agent.bridge.exec;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BuildController {

    /**
     * Triggers an Eclipse project build.
     *
     * @param projectName   target project (required)
     * @param kindString    "incremental", "full", "clean", or null (default incremental)
     * @param timeoutMillis join timeout; -1 means wait indefinitely
     */
    public Map<String, Object> buildProject(String projectName, String kindString, long timeoutMillis) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectName", projectName);
        result.put("requestedKind", kindString == null ? "incremental" : kindString);

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists()) {
            result.put("status", "failed");
            result.put("error", "project not found: " + projectName);
            return result;
        }
        if (!project.isOpen() || !project.isAccessible()) {
            result.put("status", "failed");
            result.put("error", "project not open or not accessible: " + projectName);
            return result;
        }

        final int kind = resolveKind(kindString);

        long startedAt = System.currentTimeMillis();
        WorkspaceJob job = new WorkspaceJob("MCP Build: " + projectName) {
            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
                IProgressMonitor m = monitor != null ? monitor : new NullProgressMonitor();
                if (kind == IncrementalProjectBuilder.CLEAN_BUILD) {
                    project.build(IncrementalProjectBuilder.CLEAN_BUILD, m);
                    // After a clean, a full build restores outputs
                    project.build(IncrementalProjectBuilder.FULL_BUILD, m);
                } else {
                    project.build(kind, m);
                }
                return Status.OK_STATUS;
            }
        };
        // Don't set a custom rule — project.build() internally uses the workspace rule
        // and narrower rules cause IllegalArgumentException.
        job.schedule();

        boolean completed = true;
        Throwable joinError = null;
        try {
            if (timeoutMillis > 0) {
                long deadline = System.currentTimeMillis() + timeoutMillis;
                while (job.getState() != Job.NONE) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        completed = false;
                        break;
                    }
                    job.join(Math.min(remaining, 500), null);
                }
            } else {
                job.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            joinError = e;
        } catch (OperationCanceledException e) {
            joinError = e;
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        result.put("elapsedMs", elapsed);
        IStatus status = job.getResult();
        if (!completed) {
            result.put("status", "timeout");
            result.put("note", "build still running in background");
        } else if (joinError != null) {
            result.put("status", "interrupted");
            result.put("error", joinError.getClass().getSimpleName() + ": " + joinError.getMessage());
        } else if (status == null) {
            result.put("status", "unknown");
        } else if (status.isOK()) {
            result.put("status", "ok");
        } else if (status.getSeverity() == IStatus.CANCEL) {
            result.put("status", "cancelled");
        } else {
            result.put("status", "error");
            result.put("severity", severityName(status.getSeverity()));
            result.put("message", status.getMessage());
            if (status.getException() != null) {
                result.put("exception", status.getException().getClass().getName() + ": "
                        + status.getException().getMessage());
            }
        }
        return result;
    }

    private int resolveKind(String kindString) {
        if (kindString == null) return IncrementalProjectBuilder.INCREMENTAL_BUILD;
        switch (kindString.toLowerCase()) {
            case "full":
            case "rebuild":
                return IncrementalProjectBuilder.FULL_BUILD;
            case "clean":
                return IncrementalProjectBuilder.CLEAN_BUILD;
            case "auto":
                return IncrementalProjectBuilder.AUTO_BUILD;
            case "incremental":
            default:
                return IncrementalProjectBuilder.INCREMENTAL_BUILD;
        }
    }

    private String severityName(int s) {
        switch (s) {
            case IStatus.ERROR:   return "ERROR";
            case IStatus.WARNING: return "WARNING";
            case IStatus.INFO:    return "INFO";
            case IStatus.CANCEL:  return "CANCEL";
            case IStatus.OK:      return "OK";
            default:              return "UNKNOWN";
        }
    }
}
