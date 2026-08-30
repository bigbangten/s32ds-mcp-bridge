package com.example.s32ds.agent.bridge.index;

import com.example.s32ds.agent.bridge.util.UiThread;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fail-closed control of widgets in an already open SWT dialog.
 *
 * Callers must supply the shell index, its exact current title, and a widget
 * path obtained from DialogInspector. The router additionally requires danger
 * mode so stale observations cannot silently act on a different dialog.
 */
public final class DialogController {

    public Map<String, Object> clickButton(int shellIndex, String expectedTitle,
                                           String widgetPath, String expectedText) {
        requireText(expectedTitle, "expectedTitle");
        requireText(widgetPath, "widgetPath");
        requireText(expectedText, "expectedText");

        return UiThread.sync(() -> {
            Shell shell = resolveShell(shellIndex, expectedTitle);
            Widget widget = resolveWidget(shell, widgetPath);
            if (!(widget instanceof Button)) {
                throw new IllegalArgumentException("Widget at path '" + widgetPath
                        + "' is " + widget.getClass().getSimpleName() + ", not Button");
            }
            Button button = (Button) widget;
            if (!button.isEnabled() || !button.isVisible()) {
                throw new IllegalArgumentException("Button at path '" + widgetPath
                        + "' is not enabled and visible");
            }
            String actualText = button.getText();
            if (!normalizeLabel(expectedText).equals(normalizeLabel(actualText))) {
                throw new IllegalArgumentException("Button text mismatch at path '" + widgetPath
                        + "': expected '" + expectedText + "', found '" + actualText + "'");
            }

            String title = shell.getText();
            Event event = new Event();
            event.display = shell.getDisplay();
            event.widget = button;
            event.type = SWT.Selection;
            button.notifyListeners(SWT.Selection, event);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("clicked", Boolean.TRUE);
            out.put("shellIndex", shellIndex);
            out.put("shellTitle", title);
            out.put("widgetPath", widgetPath);
            out.put("widgetClass", "Button");
            out.put("text", actualText);
            out.put("shellStillOpen", !shell.isDisposed());
            return out;
        });
    }

    public Map<String, Object> setValue(int shellIndex, String expectedTitle,
                                        String widgetPath, String value) {
        requireText(expectedTitle, "expectedTitle");
        requireText(widgetPath, "widgetPath");
        if (value == null) {
            throw new IllegalArgumentException("Field 'value' is required");
        }

        return UiThread.sync(() -> {
            Shell shell = resolveShell(shellIndex, expectedTitle);
            Widget widget = resolveWidget(shell, widgetPath);
            if (!(widget instanceof Control)) {
                throw new IllegalArgumentException("Widget at path '" + widgetPath + "' is not a Control");
            }
            Control control = (Control) widget;
            if (!control.isEnabled() || !control.isVisible()) {
                throw new IllegalArgumentException("Widget at path '" + widgetPath
                        + "' is not enabled and visible");
            }

            String shellTitle = shell.getText();
            String widgetClass = widget.getClass().getSimpleName();
            String previous;
            String current;
            if (widget instanceof Combo) {
                Combo combo = (Combo) widget;
                previous = combo.getText();
                int index = exactItemIndex(combo.getItems(), value);
                if (index < 0) {
                    throw new IllegalArgumentException("Combo value '" + value
                            + "' is not one of its current items");
                }
                combo.select(index);
                current = combo.getText();
                notifySelection(combo);
            } else if (widget instanceof Text) {
                Text text = (Text) widget;
                if (!text.getEditable()) {
                    throw new IllegalArgumentException("Text at path '" + widgetPath + "' is read-only");
                }
                previous = text.getText();
                text.setText(value);
                current = value;
            } else if (widget instanceof Button) {
                Button button = (Button) widget;
                int style = button.getStyle();
                if ((style & (SWT.CHECK | SWT.RADIO | SWT.TOGGLE)) == 0) {
                    throw new IllegalArgumentException("Push buttons must use dialog_click_button");
                }
                boolean selected = parseBoolean(value);
                previous = String.valueOf(button.getSelection());
                if ((style & SWT.RADIO) != 0 && selected && button.getParent() != null) {
                    for (Control sibling : button.getParent().getChildren()) {
                        if (sibling instanceof Button && sibling != button
                                && ((((Button) sibling).getStyle() & SWT.RADIO) != 0)) {
                            ((Button) sibling).setSelection(false);
                        }
                    }
                }
                button.setSelection(selected);
                current = String.valueOf(button.getSelection());
                notifySelection(button);
            } else if (widget instanceof org.eclipse.swt.widgets.List) {
                org.eclipse.swt.widgets.List list = (org.eclipse.swt.widgets.List) widget;
                previous = String.join(",", list.getSelection());
                int index = exactItemIndex(list.getItems(), value);
                if (index < 0) {
                    throw new IllegalArgumentException("List value '" + value
                            + "' is not one of its current items");
                }
                list.setSelection(index);
                current = String.join(",", list.getSelection());
                notifySelection(list);
            } else if (widget instanceof TabFolder) {
                TabFolder folder = (TabFolder) widget;
                previous = selectedTabText(folder);
                int index = exactTabIndex(folder.getItems(), value);
                if (index < 0) {
                    throw new IllegalArgumentException("Tab '" + value
                            + "' is not one of the current tabs");
                }
                folder.setSelection(index);
                current = selectedTabText(folder);
                notifySelection(folder);
            } else {
                throw new IllegalArgumentException("Unsupported value widget at path '" + widgetPath
                        + "': " + widget.getClass().getSimpleName());
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("changed", Boolean.TRUE);
            out.put("shellIndex", shellIndex);
            out.put("shellTitle", shellTitle);
            out.put("widgetPath", widgetPath);
            out.put("widgetClass", widgetClass);
            out.put("previousValue", previous);
            out.put("value", current);
            return out;
        });
    }

    private Shell resolveShell(int shellIndex, String expectedTitle) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            throw new IllegalArgumentException("No SWT display is available");
        }
        Shell[] shells = display.getShells();
        if (shellIndex < 0 || shellIndex >= shells.length) {
            throw new IllegalArgumentException("Shell index out of range (have " + shells.length + ")");
        }
        Shell shell = shells[shellIndex];
        if (shell == null || shell.isDisposed() || !shell.isVisible()) {
            throw new IllegalArgumentException("Shell at index " + shellIndex + " is not open and visible");
        }
        if (!expectedTitle.equals(shell.getText())) {
            throw new IllegalArgumentException("Shell title mismatch at index " + shellIndex
                    + ": expected '" + expectedTitle + "', found '" + shell.getText() + "'");
        }
        return shell;
    }

    private Widget resolveWidget(Shell shell, String widgetPath) {
        String value = widgetPath.trim();
        if ("root".equals(value)) return shell;
        if (value.startsWith("root.")) value = value.substring("root.".length());
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Invalid empty widgetPath");
        }

        Widget current = shell;
        for (String part : value.split("\\.")) {
            if (!(current instanceof Composite)) {
                throw new IllegalArgumentException("Widget path enters non-composite at segment '" + part + "'");
            }
            int index;
            try {
                index = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Widget path segments must be non-negative integers");
            }
            Control[] children = ((Composite) current).getChildren();
            if (index < 0 || index >= children.length) {
                throw new IllegalArgumentException("Widget path segment " + index
                        + " is out of range (have " + children.length + ")");
            }
            current = children[index];
            if (current.isDisposed()) {
                throw new IllegalArgumentException("Widget at path '" + widgetPath + "' is disposed");
            }
        }
        return current;
    }

    private void notifySelection(Widget widget) {
        Event event = new Event();
        event.display = Display.getDefault();
        event.widget = widget;
        event.type = SWT.Selection;
        widget.notifyListeners(SWT.Selection, event);
    }

    private int exactItemIndex(String[] items, String expected) {
        String normalized = normalizeLabel(expected);
        for (int i = 0; i < items.length; i++) {
            if (normalized.equals(normalizeLabel(items[i]))) return i;
        }
        return -1;
    }

    private int exactTabIndex(TabItem[] items, String expected) {
        String normalized = normalizeLabel(expected);
        for (int i = 0; i < items.length; i++) {
            if (normalized.equals(normalizeLabel(items[i].getText()))) return i;
        }
        return -1;
    }

    private String selectedTabText(TabFolder folder) {
        TabItem[] selected = folder.getSelection();
        return selected.length == 0 ? "" : selected[0].getText();
    }

    private boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value.trim())) return true;
        if ("false".equalsIgnoreCase(value.trim())) return false;
        throw new IllegalArgumentException("Boolean widget value must be 'true' or 'false'");
    }

    private String normalizeLabel(String value) {
        return value == null ? "" : value.replace("&&", "\u0000")
                .replace("&", "").replace("\u0000", "&").trim();
    }

    private void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
    }
}
