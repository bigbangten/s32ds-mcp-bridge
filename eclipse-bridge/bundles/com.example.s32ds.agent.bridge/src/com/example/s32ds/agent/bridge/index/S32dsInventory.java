package com.example.s32ds.agent.bridge.index;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Phase 3: S32DS-specific classification layered on top of OSGi + extension registry.
 *
 * "Which NXP packages are installed?" "Which config tools?" "Which toolchains?"
 * These are environment-specific questions where the canonical Eclipse view is
 * useless — NXP bundles live under com.nxp / com.freescale prefixes and need
 * dedicated filters.
 */
public final class S32dsInventory {

    private static final Pattern NXP_BUNDLE = Pattern.compile(
            "^(com\\.nxp|com\\.freescale|com\\.pemicro)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIG_TOOLS_PERSPECTIVE = Pattern.compile(
            "^com\\.nxp\\.swtools\\.[^.]+\\.(?:.*)?perspective$", Pattern.CASE_INSENSITIVE);

    /** All installed NXP bundles (name, version, state, location). */
    public Map<String, Object> listInventory() {
        BundleContext ctx = getContext();
        List<Map<String, Object>> nxp = new ArrayList<>();
        List<Map<String, Object>> other = new ArrayList<>();
        if (ctx != null) {
            for (Bundle b : ctx.getBundles()) {
                Map<String, Object> row = bundleRow(b);
                String sym = b.getSymbolicName() == null ? "" : b.getSymbolicName();
                if (NXP_BUNDLE.matcher(sym).matches()) {
                    nxp.add(row);
                } else {
                    other.add(row);
                }
            }
            nxp.sort(Comparator.comparing(r -> String.valueOf(r.get("symbolicName"))));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nxpBundles", nxp);
        out.put("nxpCount", nxp.size());
        out.put("otherCount", other.size());
        out.put("totalCount", nxp.size() + other.size());
        return out;
    }

    /** NXP S32 Configuration Tools (Pins/Clocks/etc.) — perspective + views + commands. */
    public Map<String, Object> listConfigTools() {
        IExtensionRegistry reg = Platform.getExtensionRegistry();
        List<Map<String, Object>> tools = new ArrayList<>();

        for (IConfigurationElement el : reg.getConfigurationElementsFor("org.eclipse.ui.perspectives")) {
            String id = el.getAttribute("id");
            if (id == null || !CONFIG_TOOLS_PERSPECTIVE.matcher(id).matches()) continue;
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("perspectiveId", id);
            tool.put("name", el.getAttribute("name"));
            tool.put("contributor", el.getContributor().getName());

            // Associated views (heuristic: views whose id is in the same namespace)
            String prefix = id.substring(0, id.indexOf(".perspective"));
            List<Map<String, Object>> views = new ArrayList<>();
            for (IConfigurationElement v : reg.getConfigurationElementsFor("org.eclipse.ui.views")) {
                String vid = v.getAttribute("id");
                if (vid != null && vid.startsWith(prefix)) {
                    Map<String, Object> vr = new LinkedHashMap<>();
                    vr.put("id", vid);
                    vr.put("name", v.getAttribute("name"));
                    views.add(vr);
                }
            }
            tool.put("views", views);

            // Associated commands
            List<Map<String, Object>> commands = new ArrayList<>();
            for (IConfigurationElement c : reg.getConfigurationElementsFor("org.eclipse.ui.commands")) {
                String cid = c.getAttribute("id");
                if (cid != null && cid.startsWith(prefix)) {
                    Map<String, Object> cr = new LinkedHashMap<>();
                    cr.put("id", cid);
                    cr.put("name", c.getAttribute("name"));
                    commands.add(cr);
                }
            }
            tool.put("commands", commands);

            tools.add(tool);
        }
        tools.sort(Comparator.comparing(t -> String.valueOf(t.get("perspectiveId"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configTools", tools);
        out.put("count", tools.size());
        return out;
    }

    /** Debugger integrations via Eclipse launch configuration types. */
    public Map<String, Object> listDebuggers() {
        IExtensionRegistry reg = Platform.getExtensionRegistry();
        List<Map<String, Object>> debuggers = new ArrayList<>();
        for (IConfigurationElement el : reg.getConfigurationElementsFor(
                "org.eclipse.debug.core.launchConfigurationTypes")) {
            String id = el.getAttribute("id");
            String contributor = el.getContributor().getName();
            if (id == null) continue;
            boolean relevant =
                    id.toLowerCase().contains("s32")
                            || id.toLowerCase().contains("pemicro")
                            || id.toLowerCase().contains("jtag")
                            || id.toLowerCase().contains("gdb")
                            || id.toLowerCase().contains("lauterbach")
                            || id.toLowerCase().contains("segger")
                            || NXP_BUNDLE.matcher(contributor).matches();
            if (!relevant) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", el.getAttribute("name"));
            row.put("contributor", contributor);
            row.put("modes", modesForConfigType(reg, id));
            row.put("delegate", el.getAttribute("delegate"));
            debuggers.add(row);
        }
        debuggers.sort(Comparator.comparing(r -> String.valueOf(r.get("id"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("debuggers", debuggers);
        out.put("count", debuggers.size());
        return out;
    }

    /** CDT toolchains + build definitions, filtered to arm/NXP relevance. */
    public Map<String, Object> listToolchains() {
        IExtensionRegistry reg = Platform.getExtensionRegistry();
        List<Map<String, Object>> toolchains = new ArrayList<>();

        // Recursive scan of buildDefinitions for <toolChain> elements
        for (IConfigurationElement el : reg.getConfigurationElementsFor(
                "org.eclipse.cdt.managedbuilder.core.buildDefinitions")) {
            scanForToolchains(el, el.getContributor().getName(), toolchains);
        }
        toolchains.sort(Comparator.comparing(r -> String.valueOf(r.get("id"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolchains", toolchains);
        out.put("count", toolchains.size());
        return out;
    }

    // ───────────────────────── helpers ─────────────────────────

    private void scanForToolchains(IConfigurationElement el, String contributor,
                                   List<Map<String, Object>> out) {
        if ("toolChain".equals(el.getName())) {
            String id = el.getAttribute("id");
            String name = el.getAttribute("name");
            if (id != null) {
                boolean relevant = id.toLowerCase().contains("arm")
                        || id.toLowerCase().contains("s32")
                        || NXP_BUNDLE.matcher(contributor).matches();
                if (relevant) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", id);
                    row.put("name", name);
                    row.put("contributor", contributor);
                    row.put("osList", el.getAttribute("osList"));
                    row.put("archList", el.getAttribute("archList"));
                    row.put("targetTool", el.getAttribute("targetTool"));
                    row.put("supportsManagedBuild", "true".equals(el.getAttribute("supportsManagedBuild")));
                    row.put("isAbstract", "true".equals(el.getAttribute("isAbstract")));
                    out.add(row);
                }
            }
        }
        for (IConfigurationElement child : el.getChildren()) {
            scanForToolchains(child, contributor, out);
        }
    }

    private List<String> modesForConfigType(IExtensionRegistry reg, String typeId) {
        List<String> modes = new ArrayList<>();
        for (IConfigurationElement el : reg.getConfigurationElementsFor(
                "org.eclipse.debug.core.launchModes")) {
            // launchModes don't filter per-type; we surface them as "available modes"
            String mid = el.getAttribute("mode");
            if (mid != null && !modes.contains(mid)) modes.add(mid);
        }
        return modes;
    }

    private BundleContext getContext() {
        try {
            Bundle own = FrameworkUtil.getBundle(S32dsInventory.class);
            return own != null ? own.getBundleContext() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Map<String, Object> bundleRow(Bundle b) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("symbolicName", b.getSymbolicName());
        r.put("version", b.getVersion() != null ? b.getVersion().toString() : null);
        r.put("state", stateName(b.getState()));
        r.put("id", b.getBundleId());
        return r;
    }

    private static String stateName(int s) {
        switch (s) {
            case Bundle.UNINSTALLED: return "UNINSTALLED";
            case Bundle.INSTALLED:   return "INSTALLED";
            case Bundle.RESOLVED:    return "RESOLVED";
            case Bundle.STARTING:    return "STARTING";
            case Bundle.STOPPING:    return "STOPPING";
            case Bundle.ACTIVE:      return "ACTIVE";
            default: return "UNKNOWN(" + s + ")";
        }
    }
}
