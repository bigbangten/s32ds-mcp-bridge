package com.example.s32ds.agent.bridge.index;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs a stored launch configuration by name (or full id). Handles run, debug,
 * and S32DS/NXP/PEmicro flash/debug launch types — they're all just launch configs
 * with different mode strings, so a single entry point covers them.
 *
 * <p>Mutating (it actually starts a process), so the Router puts this behind
 * the danger gate.
 */
public final class LaunchRunner {

    public Map<String, Object> run(String configName, String mode) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (configName == null || configName.isEmpty()) {
            out.put("ok", false); out.put("error", "configName required"); return out;
        }
        String runMode = (mode == null || mode.isEmpty()) ? ILaunchManager.RUN_MODE : mode;

        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        try {
            ILaunchConfiguration target = null;
            for (ILaunchConfiguration c : mgr.getLaunchConfigurations()) {
                if (configName.equals(c.getName())) { target = c; break; }
            }
            if (target == null) {
                out.put("ok", false);
                out.put("error", "launch configuration not found: " + configName);
                return out;
            }
            if (!target.supportsMode(runMode)) {
                out.put("ok", false);
                out.put("error", "config does not support mode: " + runMode);
                out.put("supportedModes", target.getModes());
                return out;
            }
            ILaunch launch = target.launch(runMode, new NullProgressMonitor());
            out.put("ok", true);
            out.put("configName", target.getName());
            out.put("type", target.getType().getIdentifier());
            out.put("mode", launch.getLaunchMode());
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return out;
        }
    }

    public Map<String, Object> runWithOverrides(String configName, String mode,
            String copyName, Map<String, Object> overrides) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (configName == null || configName.isEmpty()) {
            out.put("ok", false); out.put("error", "configName required"); return out;
        }
        String runMode = (mode == null || mode.isEmpty()) ? ILaunchManager.RUN_MODE : mode;

        ILaunchManager mgr = DebugPlugin.getDefault().getLaunchManager();
        try {
            ILaunchConfiguration source = null;
            for (ILaunchConfiguration c : mgr.getLaunchConfigurations()) {
                if (configName.equals(c.getName())) { source = c; break; }
            }
            if (source == null) {
                out.put("ok", false);
                out.put("error", "launch configuration not found: " + configName);
                return out;
            }
            if (!source.supportsMode(runMode)) {
                out.put("ok", false);
                out.put("error", "config does not support mode: " + runMode);
                out.put("supportedModes", source.getModes());
                return out;
            }

            String derivedName = (copyName == null || copyName.isEmpty())
                    ? source.getName() + "_codex_" + System.currentTimeMillis()
                    : copyName;
            ILaunchConfigurationWorkingCopy wc = source.copy(derivedName);
            int changed = applyOverrides(wc, overrides);
            ILaunchConfiguration saved = wc.doSave();
            ILaunch launch = saved.launch(runMode, new NullProgressMonitor());
            out.put("ok", true);
            out.put("sourceConfigName", source.getName());
            out.put("configName", saved.getName());
            out.put("overrideCount", changed);
            out.put("type", saved.getType().getIdentifier());
            out.put("mode", launch.getLaunchMode());
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return out;
        }
    }

    private int applyOverrides(ILaunchConfigurationWorkingCopy wc, Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            String key = e.getKey();
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            Object value = e.getValue();
            if (value == null) {
                wc.removeAttribute(key);
            } else if (value instanceof Boolean) {
                wc.setAttribute(key, ((Boolean) value).booleanValue());
            } else if (value instanceof Number) {
                wc.setAttribute(key, ((Number) value).intValue());
            } else if (value instanceof List<?>) {
                List<String> strings = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    strings.add(String.valueOf(item));
                }
                wc.setAttribute(key, strings);
            } else {
                wc.setAttribute(key, String.valueOf(value));
            }
            changed++;
        }
        return changed;
    }
}
