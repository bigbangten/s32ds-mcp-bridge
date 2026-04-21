package com.example.s32ds.agent.bridge.index;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-only inspection and sanity-check of Eclipse launch configurations.
 * Never launches anything — just analyzes the configs + environment.
 */
public final class LaunchConfigAnalyzer {

    private static final Set<String> RISKY_KEYWORDS = Set.of(
            "flash", "erase", "program", "fuse", "efuse", "burn", "attach");

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        try {
            for (ILaunchConfiguration cfg : mgr.getLaunchConfigurations()) {
                out.add(summarize(cfg));
            }
        } catch (CoreException e) {
            // Return whatever we got
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return out;
    }

    /**
     * Full dump of a single launch configuration — every attribute, not just the
     * curated list. Use when the user asks "what's inside the Debug Configurations
     * entry for X?" — equivalent to opening the dialog and reading every field.
     */
    public Map<String, Object> fullDetails(String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfiguration cfg;
        try {
            cfg = findByName(mgr, name);
        } catch (CoreException e) {
            result.put("error", "lookup failed: " + e.getMessage());
            return result;
        }
        if (cfg == null) {
            result.put("error", "launch configuration not found");
            return result;
        }
        try {
            ILaunchConfigurationType type = cfg.getType();
            result.put("typeId", type.getIdentifier());
            result.put("typeName", type.getName());
            result.put("modes", new ArrayList<>(type.getSupportedModes()));
            result.put("category", type.getCategory());
            result.put("pluginIdentifier", type.getPluginIdentifier());
        } catch (CoreException ignored) {}

        // EVERY attribute — raw keys + values, sorted for readability
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) cfg.getAttributes();
            Map<String, Object> sorted = new TreeMap<>();
            if (attrs != null) {
                for (Map.Entry<String, Object> e : attrs.entrySet()) {
                    sorted.put(e.getKey(), stringifyValue(e.getValue()));
                }
            }
            result.put("attributes", sorted);
            result.put("attributeCount", sorted.size());
        } catch (CoreException e) {
            result.put("attributesError", e.getMessage());
        }

        result.put("risk", classifyRisk(cfg));
        result.put("isReadOnly", cfg.isReadOnly());
        try { result.put("isLocal", cfg.isLocal()); } catch (Throwable ignored) {}
        return result;
    }

    /** Convert attribute value (could be List, Map, String, Integer, Boolean) to JSON-friendly form. */
    private Object stringifyValue(Object v) {
        if (v == null) return null;
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof List<?>) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) v) out.add(stringifyValue(o));
            return out;
        }
        if (v instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                out.put(String.valueOf(e.getKey()), stringifyValue(e.getValue()));
            }
            return out;
        }
        return String.valueOf(v);
    }

    public Map<String, Object> analyze(String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfiguration cfg;
        try {
            cfg = findByName(mgr, name);
        } catch (CoreException e) {
            result.put("error", "lookup failed: " + e.getMessage());
            return result;
        }
        if (cfg == null) {
            result.put("error", "launch configuration not found");
            return result;
        }
        result.putAll(summarize(cfg));

        // ───── safety / sanity checks ─────
        List<Map<String, Object>> checks = new ArrayList<>();

        // 1. PROGRAM_NAME (.elf) existence
        String programAttr = readAttr(cfg, "org.eclipse.cdt.launch.PROGRAM_NAME", null);
        String projectAttr = readAttr(cfg, "org.eclipse.cdt.launch.PROJECT_ATTR", null);
        if (programAttr != null) {
            IPath programPath = new Path(programAttr);
            File file = resolveProjectRelative(projectAttr, programPath);
            boolean exists = file != null && file.exists();
            Map<String, Object> c = check("binary",
                    exists ? "pass" : "fail",
                    exists ? "found: " + file.getAbsolutePath()
                           : "missing: " + (file != null ? file.getAbsolutePath() : programAttr));
            if (exists) c.put("sizeBytes", file.length());
            if (exists) c.put("modifiedMs", file.lastModified());
            checks.add(c);
        } else {
            checks.add(check("binary", "warn", "PROGRAM_NAME not set"));
        }

        // 2. Project accessibility
        if (projectAttr != null) {
            IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectAttr);
            boolean open = p.exists() && p.isAccessible();
            checks.add(check("project",
                    open ? "pass" : "fail",
                    open ? "open: " + projectAttr : "not open or missing: " + projectAttr));
        }

        // 3. Risk classification from type + attributes
        String typeId = readAttr(cfg, null, null); // placeholder; real risk from name/type
        String risk = classifyRisk(cfg);
        checks.add(check("risk_classification", "info", risk));

        // 4. PEmicro-specific sanity
        String device = readAttr(cfg, "com.pemicro.debug.gdbjtag.pne.PE.DEVICE_NAME", null);
        if (device != null) {
            checks.add(check("pemicro_device", "info", "device: " + device));
            String ifaceIdx = readAttr(cfg, "com.pemicro.debug.gdbjtag.pne.PE.HARDWARE_INTERFACE", null);
            boolean external = "true".equals(readAttr(cfg, "com.pemicro.debug.gdbjtag.pne.PE.USE_EXTERNAL_SERVER", null));
            if (external) {
                checks.add(check("pemicro_external_server", "warn",
                        "external GDB server mode — make sure pe_gdb_server.exe is running first"));
            }
        }

        // 5. S32 Debugger specific
        String s32Device = readAttr(cfg, "com.nxp.s32ds.debug.ide.s32debugger.core.MCU_NAME", null);
        if (s32Device != null) {
            checks.add(check("s32_debugger_device", "info", "mcu: " + s32Device));
        }

        result.put("checks", checks);
        result.put("checkPassed", checks.stream().filter(c -> "pass".equals(c.get("status"))).count());
        result.put("checkFailed", checks.stream().filter(c -> "fail".equals(c.get("status"))).count());
        result.put("checkWarnings", checks.stream().filter(c -> "warn".equals(c.get("status"))).count());
        return result;
    }

    // ───────────────────── helpers ─────────────────────

    private Map<String, Object> summarize(ILaunchConfiguration cfg) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", cfg.getName());
        try {
            ILaunchConfigurationType type = cfg.getType();
            row.put("typeId", type.getIdentifier());
            row.put("typeName", type.getName());
            row.put("modes", new ArrayList<>(type.getSupportedModes()));
        } catch (CoreException e) {
            row.put("typeId", null);
        }
        // A compact view of key attributes, not the full attribute map (can be huge)
        Map<String, Object> attrs = new TreeMap<>();
        for (String key : Arrays.asList(
                "org.eclipse.cdt.launch.PROGRAM_NAME",
                "org.eclipse.cdt.launch.PROJECT_ATTR",
                "org.eclipse.debug.core.MAPPED_RESOURCE_PATHS",
                "com.pemicro.debug.gdbjtag.pne.PE.DEVICE_NAME",
                "com.pemicro.debug.gdbjtag.pne.PE.HARDWARE_INTERFACE",
                "com.pemicro.debug.gdbjtag.pne.PE.USE_EXTERNAL_SERVER",
                "com.nxp.s32ds.debug.ide.s32debugger.core.MCU_NAME",
                "com.nxp.s32ds.debug.ide.s32debugger.core.CONNECTION_TYPE")) {
            String v = readAttr(cfg, key, null);
            if (v != null) attrs.put(key, v);
        }
        row.put("attributes", attrs);
        row.put("risk", classifyRisk(cfg));
        return row;
    }

    private ILaunchConfiguration findByName(ILaunchManager mgr, String name) throws CoreException {
        for (ILaunchConfiguration cfg : mgr.getLaunchConfigurations()) {
            if (cfg.getName().equals(name)) return cfg;
        }
        return null;
    }

    private String readAttr(ILaunchConfiguration cfg, String key, String def) {
        if (key == null) return def;
        try {
            return cfg.getAttribute(key, def);
        } catch (CoreException e) {
            return def;
        }
    }

    private File resolveProjectRelative(String projectName, IPath relOrAbs) {
        if (relOrAbs == null) return null;
        // Absolute file path
        File abs = relOrAbs.toFile();
        if (abs.isAbsolute() && abs.exists()) return abs;
        // Relative to project workspace location
        if (projectName != null) {
            IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (p.exists()) {
                IPath loc = p.getLocation();
                if (loc != null) {
                    File f = loc.append(relOrAbs).toFile();
                    return f;
                }
            }
        }
        return abs;
    }

    private String classifyRisk(ILaunchConfiguration cfg) {
        String typeId = "";
        try { typeId = cfg.getType().getIdentifier() != null ? cfg.getType().getIdentifier() : ""; }
        catch (Exception ignored) {}
        String combined = (cfg.getName() + " " + typeId).toLowerCase();
        for (String kw : RISKY_KEYWORDS) {
            if (combined.contains(kw)) return "hardware_mutation";
        }
        if (combined.contains("debug")) return "debug_target";
        if (combined.contains("run")) return "run";
        return "unknown";
    }

    private Map<String, Object> check(String id, String status, String message) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("status", status); // pass | fail | warn | info
        c.put("message", message);
        return c;
    }
}
