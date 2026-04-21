package com.example.s32ds.agent.bridge.exec;

import com.example.s32ds.agent.bridge.util.UiThread;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkbenchController {

    public Map<String, Object> showView(String viewId) {
        return UiThread.sync(() -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                return fail("No active workbench window");
            }
            IWorkbenchPage page = window.getActivePage();
            if (page == null) {
                return fail("No active workbench page");
            }
            try {
                page.showView(viewId);
            } catch (PartInitException e) {
                return fail("showView failed: " + e.getMessage());
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("viewId", viewId);
            r.put("status", "shown");
            return r;
        });
    }

    public Map<String, Object> switchPerspective(String perspectiveId) {
        return UiThread.sync(() -> {
            IWorkbench workbench = PlatformUI.getWorkbench();
            IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
            if (window == null) {
                return fail("No active workbench window");
            }
            IWorkbenchPage page = window.getActivePage();
            if (page == null) {
                return fail("No active workbench page");
            }
            IPerspectiveDescriptor desc = workbench.getPerspectiveRegistry()
                    .findPerspectiveWithId(perspectiveId);
            if (desc == null) {
                return fail("Unknown perspective: " + perspectiveId);
            }
            page.setPerspective(desc);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("perspectiveId", perspectiveId);
            r.put("status", "switched");
            return r;
        });
    }

    private Map<String, Object> fail(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "failed");
        r.put("error", msg);
        return r;
    }
}
