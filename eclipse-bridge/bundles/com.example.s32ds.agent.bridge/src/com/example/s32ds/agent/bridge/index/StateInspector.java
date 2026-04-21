package com.example.s32ds.agent.bridge.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.example.s32ds.agent.bridge.util.UiThread;

public final class StateInspector {
    public Map<String, Object> inspectState() {
        return UiThread.sync(this::inspectStateInUi);
    }

    public Map<String, Object> contextSnapshot() {
        return UiThread.sync(() -> {
            Map<String, Object> context = new LinkedHashMap<>();
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window != null ? window.getActivePage() : null;
            IPerspectiveDescriptor perspective = page != null ? page.getPerspective() : null;
            context.put("activePerspective", perspective != null ? describePerspective(perspective) : null);
            context.put("selection", summarizeSelection(window != null ? window.getSelectionService().getSelection() : null));
            return context;
        });
    }

    private Map<String, Object> inspectStateInUi() {
        Map<String, Object> state = new LinkedHashMap<>();
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window != null ? window.getActivePage() : null;

        state.put("activeWindow", Boolean.valueOf(window != null));
        state.put("activeShellTitle", shellTitle(window));
        state.put("activePerspective", page != null && page.getPerspective() != null
                ? describePerspective(page.getPerspective())
                : null);
        state.put("activeEditor", page != null ? describeEditor(page.getActiveEditor()) : null);
        state.put("selection", summarizeSelection(window != null ? window.getSelectionService().getSelection() : null));
        state.put("openEditors", page != null ? describeEditors(page.getEditorReferences()) : new ArrayList<>());
        state.put("openViews", page != null ? describeViews(page.getViewReferences()) : new ArrayList<>());
        state.put("workspaceProjects", describeProjects());
        state.put("dirtyEditors", page != null ? listDirtyEditors(page.getEditorReferences()) : new ArrayList<>());
        return state;
    }

    private String shellTitle(IWorkbenchWindow window) {
        if (window == null) {
            return null;
        }
        Shell shell = window.getShell();
        return shell != null ? shell.getText() : null;
    }

    private Map<String, Object> describePerspective(IPerspectiveDescriptor perspective) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", perspective.getId());
        row.put("name", perspective.getLabel());
        row.put("description", perspective.getDescription());
        return row;
    }

    private Map<String, Object> describeEditor(IEditorPart editorPart) {
        if (editorPart == null) {
            return null;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", editorPart.getSite() != null ? editorPart.getSite().getId() : null);
        row.put("title", editorPart.getTitle());
        row.put("input", describeEditorInput(editorPart.getEditorInput()));
        row.put("dirty", Boolean.valueOf(editorPart.isDirty()));
        return row;
    }

    private Map<String, Object> describeEditorInput(IEditorInput input) {
        if (input == null) {
            return null;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", input.getName());
        if (input instanceof IPathEditorInput) {
            data.put("path", ((IPathEditorInput) input).getPath().toOSString());
        } else {
            data.put("path", null);
        }
        return data;
    }

    private List<Map<String, Object>> describeEditors(IEditorReference[] references) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IEditorReference reference : references) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", reference.getId());
            row.put("name", reference.getName());
            row.put("title", reference.getTitle());
            row.put("dirty", Boolean.valueOf(reference.isDirty()));
            row.put("pinned", Boolean.valueOf(reference.isPinned()));
            try {
                row.put("input", describeEditorInput(reference.getEditorInput()));
            } catch (Exception e) {
                row.put("input", null);
            }
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> describeViews(IViewReference[] references) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IViewReference reference : references) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", reference.getId());
            row.put("title", reference.getTitle());
            row.put("partName", reference.getPartName());
            row.put("secondaryId", reference.getSecondaryId());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> describeProjects() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", project.getName());
            row.put("path", project.getLocation() != null ? project.getLocation().toOSString() : null);
            row.put("open", Boolean.valueOf(project.isOpen()));
            row.put("accessible", Boolean.valueOf(project.isAccessible()));
            out.add(row);
        }
        return out;
    }

    private List<String> listDirtyEditors(IEditorReference[] references) {
        List<String> dirty = new ArrayList<>();
        for (IEditorReference reference : references) {
            if (reference.isDirty()) {
                dirty.add(reference.getId());
            }
        }
        return dirty;
    }

    private Map<String, Object> summarizeSelection(ISelection selection) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (selection == null) {
            row.put("type", null);
            row.put("empty", Boolean.TRUE);
            row.put("size", Integer.valueOf(0));
            row.put("items", new ArrayList<>());
            return row;
        }

        row.put("type", selection.getClass().getName());
        row.put("empty", Boolean.valueOf(selection.isEmpty()));

        List<Map<String, Object>> items = new ArrayList<>();
        if (selection instanceof IStructuredSelection) {
            IStructuredSelection structured = (IStructuredSelection) selection;
            for (Object element : structured.toList()) {
                items.add(describeSelectedObject(element));
            }
            row.put("size", Integer.valueOf(structured.size()));
        } else {
            items.add(describeSelectedObject(selection));
            row.put("size", Integer.valueOf(items.size()));
        }
        row.put("items", items);
        return row;
    }

    private Map<String, Object> describeSelectedObject(Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("class", value != null ? value.getClass().getName() : null);
        row.put("label", String.valueOf(value));

        IResource resource = adapt(value, IResource.class);
        row.put("resourcePath", resource != null && resource.getFullPath() != null ? resource.getFullPath().toString() : null);
        return row;
    }

    private <T> T adapt(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        if (value instanceof IAdaptable) {
            return type.cast(((IAdaptable) value).getAdapter(type));
        }
        return null;
    }
}
