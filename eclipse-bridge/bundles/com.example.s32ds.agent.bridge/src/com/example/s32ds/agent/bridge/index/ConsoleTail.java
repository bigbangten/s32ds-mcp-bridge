package com.example.s32ds.agent.bridge.index;

import com.example.s32ds.agent.bridge.util.UiThread;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.TextConsole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to Eclipse Console view content.
 * Lists consoles, returns tail of lines. Never writes.
 */
public final class ConsoleTail {

    public List<Map<String, Object>> listConsoles() {
        return UiThread.sync(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            IConsoleManager mgr = ConsolePlugin.getDefault().getConsoleManager();
            IConsole[] all = mgr.getConsoles();
            for (int i = 0; i < all.length; i++) {
                IConsole c = all[i];
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", i);
                row.put("name", c.getName());
                row.put("type", c.getType());
                row.put("class", c.getClass().getSimpleName());
                if (c instanceof TextConsole) {
                    TextConsole tc = (TextConsole) c;
                    IDocument doc = tc.getDocument();
                    if (doc != null) {
                        row.put("length", doc.getLength());
                        row.put("lineCount", doc.getNumberOfLines());
                    }
                }
                out.add(row);
            }
            return out;
        });
    }

    /**
     * Returns the last {@code lines} lines of the console matched by name or index.
     * One of {@code name} or {@code index} must be provided.
     */
    public Map<String, Object> tail(String name, Integer index, int lines) {
        final int lineCap = lines <= 0 ? 100 : Math.min(lines, 2000);
        return UiThread.sync(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            IConsoleManager mgr = ConsolePlugin.getDefault().getConsoleManager();
            IConsole[] all = mgr.getConsoles();
            IConsole target = null;
            if (index != null && index >= 0 && index < all.length) {
                target = all[index];
            } else if (name != null) {
                for (IConsole c : all) {
                    if (c.getName() != null && c.getName().contains(name)) {
                        target = c;
                        break;
                    }
                }
            }
            if (target == null) {
                result.put("error", "console not found (name=" + name + ", index=" + index + ")");
                result.put("available", listConsoleNames(all));
                return result;
            }
            result.put("name", target.getName());
            result.put("type", target.getType());
            if (!(target instanceof TextConsole)) {
                result.put("error", "console is not a TextConsole (class=" + target.getClass().getName() + ")");
                return result;
            }
            TextConsole tc = (TextConsole) target;
            IDocument doc = tc.getDocument();
            if (doc == null) {
                result.put("error", "no document");
                return result;
            }
            int total = doc.getNumberOfLines();
            int startLine = Math.max(0, total - lineCap);
            List<String> collected = new ArrayList<>();
            try {
                for (int ln = startLine; ln < total; ln++) {
                    int off = doc.getLineOffset(ln);
                    int len = doc.getLineLength(ln);
                    String line = doc.get(off, len);
                    if (line.endsWith("\r\n")) line = line.substring(0, line.length() - 2);
                    else if (line.endsWith("\n") || line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    collected.add(line);
                }
            } catch (Exception e) {
                result.put("readError", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            result.put("totalLines", total);
            result.put("returnedLines", collected.size());
            result.put("startLine", startLine);
            result.put("lines", collected);
            return result;
        });
    }

    private List<String> listConsoleNames(IConsole[] all) {
        List<String> names = new ArrayList<>();
        for (IConsole c : all) names.add(c.getName());
        return names;
    }
}
