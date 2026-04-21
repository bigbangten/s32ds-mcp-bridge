package com.example.s32ds.agent.bridge.exec;

import com.example.s32ds.agent.bridge.util.UiThread;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EditorController {

    /** Opens a file in an editor. workspacePath is /project/path/to/file. */
    public Map<String, Object> openFile(String workspacePath) {
        return UiThread.sync(() -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("workspacePath", workspacePath);
            if (workspacePath == null || workspacePath.isEmpty()) {
                r.put("status", "failed");
                r.put("error", "workspacePath required");
                return r;
            }
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) { r.put("status","failed"); r.put("error","no active window"); return r; }
            IWorkbenchPage page = window.getActivePage();
            if (page == null) { r.put("status","failed"); r.put("error","no active page"); return r; }

            IPath p = new Path(workspacePath);
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            IFile file = root.getFile(p);
            if (!file.exists()) {
                r.put("status","failed");
                r.put("error","file not found in workspace");
                return r;
            }
            try {
                IDE.openEditor(page, file);
                r.put("status","opened");
                r.put("editorId", IDE.getEditorDescriptor(file, true, true).getId());
            } catch (PartInitException e) {
                r.put("status","failed");
                r.put("error", e.getMessage());
            } catch (Throwable t) {
                r.put("status","failed");
                r.put("error", t.getClass().getName() + ": " + t.getMessage());
            }
            return r;
        });
    }

    /** Saves all dirty editors without prompting. */
    public Map<String, Object> saveAll() {
        return UiThread.sync(() -> {
            Map<String, Object> r = new LinkedHashMap<>();
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) { r.put("status","failed"); r.put("error","no active window"); return r; }
            IWorkbenchPage page = window.getActivePage();
            if (page == null) { r.put("status","failed"); r.put("error","no active page"); return r; }

            int before = page.getDirtyEditors().length;
            boolean ok = page.saveAllEditors(false);
            int after = page.getDirtyEditors().length;
            r.put("status", ok ? "saved" : "not-saved");
            r.put("dirtyBefore", before);
            r.put("dirtyAfter", after);
            r.put("savedCount", before - after);
            return r;
        });
    }

    /** Lists currently open editors with dirty status. */
    public List<Map<String, Object>> listOpenEditors() {
        return UiThread.sync(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) return out;
            IWorkbenchPage page = window.getActivePage();
            if (page == null) return out;
            for (org.eclipse.ui.IEditorReference ref : page.getEditorReferences()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", ref.getId());
                row.put("title", ref.getTitle());
                row.put("dirty", ref.isDirty());
                row.put("pinned", ref.isPinned());
                out.add(row);
            }
            return out;
        });
    }
}
