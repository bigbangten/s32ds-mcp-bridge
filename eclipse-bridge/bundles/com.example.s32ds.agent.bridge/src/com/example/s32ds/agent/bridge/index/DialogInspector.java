package com.example.s32ds.agent.bridge.index;

import com.example.s32ds.agent.bridge.util.UiThread;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.Widget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inspection of currently open SWT Shells (dialogs, wizards, popups).
 *
 * Uses SWT Display.getShells() to enumerate and walk widget trees. Extracts
 * field values, button labels, etc. Each widget receives a stable path for the
 * lifetime of the current widget tree. Mutating actions live in
 * {@link DialogController} and are protected by the danger gate in the router.
 */
public final class DialogInspector {

    /** Lists all currently visible shells (main window + dialogs + popups). */
    public java.util.List<Map<String, Object>> listShells() {
        return UiThread.sync(() -> {
            java.util.List<Map<String, Object>> out = new ArrayList<>();
            Display d = Display.getDefault();
            if (d == null || d.isDisposed()) return out;
            Shell[] shells = d.getShells();
            for (int i = 0; i < shells.length; i++) {
                Shell s = shells[i];
                if (s == null || s.isDisposed()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", i);
                row.put("text", s.getText());
                row.put("visible", s.isVisible());
                row.put("minimized", s.getMinimized());
                row.put("maximized", s.getMaximized());
                row.put("style", styleBits(s));
                row.put("hasParent", s.getParent() != null);
                row.put("bounds", rectString(s.getBounds()));
                row.put("widgetCount", countDescendants(s));
                out.add(row);
            }
            return out;
        });
    }

    /**
     * Returns the widget tree of the shell at the given index (from listShells).
     * Depth-limited; stops at {@code maxDepth} levels.
     */
    public Map<String, Object> shellWidgets(int index, int maxDepth) {
        final int depth = maxDepth <= 0 ? 6 : maxDepth;
        return UiThread.sync(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            Display d = Display.getDefault();
            if (d == null || d.isDisposed()) {
                out.put("error", "no display");
                return out;
            }
            Shell[] shells = d.getShells();
            if (index < 0 || index >= shells.length) {
                out.put("error", "shell index out of range (have " + shells.length + ")");
                return out;
            }
            Shell s = shells[index];
            out.put("index", index);
            out.put("text", s.getText());
            out.put("tree", describeWidget(s, "root", 0, depth));
            return out;
        });
    }

    // ───────────────────── helpers ─────────────────────

    private Map<String, Object> describeWidget(Widget w, String path, int curDepth, int maxDepth) {
        if (w == null || w.isDisposed()) {
            Map<String, Object> dead = new LinkedHashMap<>();
            dead.put("class", "disposed");
            return dead;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("class", w.getClass().getSimpleName());

        // Read-only text/value harvest per widget class
        try {
            if (w instanceof Shell) {
                Shell s = (Shell) w;
                row.put("text", s.getText());
                if (s.getDefaultButton() != null) {
                    row.put("defaultButton", buttonText(s.getDefaultButton()));
                }
            } else if (w instanceof Label) {
                row.put("text", ((Label) w).getText());
            } else if (w instanceof Link) {
                row.put("text", ((Link) w).getText());
            } else if (w instanceof Button) {
                Button b = (Button) w;
                row.put("text", b.getText());
                row.put("enabled", b.isEnabled());
                row.put("selection", b.getSelection());
                row.put("styleKind", buttonKind(b));
            } else if (w instanceof Text) {
                Text t = (Text) w;
                row.put("text", t.getText());
                row.put("enabled", t.isEnabled());
                row.put("editable", t.getEditable());
            } else if (w instanceof Combo) {
                Combo c = (Combo) w;
                row.put("text", c.getText());
                row.put("selectionIndex", c.getSelectionIndex());
                row.put("items", java.util.Arrays.asList(c.getItems()));
                row.put("enabled", c.isEnabled());
            } else if (w instanceof List) {
                List l = (List) w;
                row.put("items", java.util.Arrays.asList(l.getItems()));
                row.put("selectionIndices", java.util.Arrays.stream(l.getSelectionIndices()).boxed().toArray());
            } else if (w instanceof Group) {
                row.put("text", ((Group) w).getText());
            } else if (w instanceof TabFolder) {
                TabFolder tf = (TabFolder) w;
                java.util.List<String> tabs = new ArrayList<>();
                for (TabItem ti : tf.getItems()) tabs.add(ti.getText());
                row.put("tabs", tabs);
                row.put("selectionIndex", tf.getSelectionIndex());
            } else if (w instanceof Tree) {
                // Don't walk tree items — can be huge. Just count.
                row.put("itemCount", ((Tree) w).getItemCount());
            } else if (w instanceof Table) {
                row.put("itemCount", ((Table) w).getItemCount());
                row.put("columnCount", ((Table) w).getColumnCount());
            }
        } catch (Throwable t) {
            row.put("readError", t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        if (curDepth < maxDepth && w instanceof Composite) {
            java.util.List<Map<String, Object>> children = new ArrayList<>();
            Control[] controls = ((Composite) w).getChildren();
            for (int i = 0; i < controls.length; i++) {
                String childPath = "root".equals(path) ? String.valueOf(i) : path + "." + i;
                children.add(describeWidget(controls[i], childPath, curDepth + 1, maxDepth));
            }
            if (!children.isEmpty()) row.put("children", children);
        }
        return row;
    }

    private int countDescendants(Widget w) {
        if (!(w instanceof Composite) || w.isDisposed()) return 0;
        int n = 0;
        for (Control c : ((Composite) w).getChildren()) {
            n++;
            n += countDescendants(c);
        }
        return n;
    }

    private String buttonText(Button b) {
        try { return b.getText(); } catch (Throwable ignored) { return null; }
    }

    private String buttonKind(Button b) {
        int s = b.getStyle();
        if ((s & org.eclipse.swt.SWT.PUSH) != 0) return "push";
        if ((s & org.eclipse.swt.SWT.CHECK) != 0) return "check";
        if ((s & org.eclipse.swt.SWT.RADIO) != 0) return "radio";
        if ((s & org.eclipse.swt.SWT.TOGGLE) != 0) return "toggle";
        return "other";
    }

    private String styleBits(Shell s) {
        java.util.List<String> bits = new ArrayList<>();
        int st = s.getStyle();
        if ((st & org.eclipse.swt.SWT.DIALOG_TRIM) != 0) bits.add("DIALOG_TRIM");
        if ((st & org.eclipse.swt.SWT.APPLICATION_MODAL) != 0) bits.add("APPLICATION_MODAL");
        if ((st & org.eclipse.swt.SWT.MODELESS) != 0) bits.add("MODELESS");
        if ((st & org.eclipse.swt.SWT.PRIMARY_MODAL) != 0) bits.add("PRIMARY_MODAL");
        if ((st & org.eclipse.swt.SWT.SYSTEM_MODAL) != 0) bits.add("SYSTEM_MODAL");
        if ((st & org.eclipse.swt.SWT.RESIZE) != 0) bits.add("RESIZE");
        return String.join(",", bits);
    }

    private String rectString(org.eclipse.swt.graphics.Rectangle r) {
        if (r == null) return null;
        return r.x + "," + r.y + "," + r.width + "x" + r.height;
    }
}
