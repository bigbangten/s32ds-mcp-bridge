package com.example.s32ds.agent.bridge.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;

public final class WizardIndexer {
    public Map<String, Object> listWizards(String type) {
        String normalized = normalizeType(type);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", normalized);

        List<Map<String, Object>> items = new ArrayList<>();
        if ("all".equals(normalized) || "new".equals(normalized)) {
            items.addAll(readWizards("new", "org.eclipse.ui.newWizards"));
        }
        if ("all".equals(normalized) || "import".equals(normalized)) {
            items.addAll(readWizards("import", "org.eclipse.ui.importWizards"));
        }
        if ("all".equals(normalized) || "export".equals(normalized)) {
            items.addAll(readWizards("export", "org.eclipse.ui.exportWizards"));
        }
        data.put("items", items);
        data.put("count", Integer.valueOf(items.size()));
        return data;
    }

    public boolean isSupportedType(String type) {
        String normalized = normalizeType(type);
        return "all".equals(normalized)
                || "new".equals(normalized)
                || "import".equals(normalized)
                || "export".equals(normalized);
    }

    private List<Map<String, Object>> readWizards(String wizardType, String extensionPointId) {
        IConfigurationElement[] elements = Platform.getExtensionRegistry()
                .getConfigurationElementsFor(extensionPointId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IConfigurationElement element : elements) {
            if (!"wizard".equals(element.getName())) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wizardType", wizardType);
            row.put("id", element.getAttribute("id"));
            row.put("name", element.getAttribute("name"));
            row.put("class", element.getAttribute("class"));
            row.put("icon", element.getAttribute("icon"));
            row.put("category", element.getAttribute("category"));
            row.put("project", element.getAttribute("project"));
            row.put("finalPerspective", element.getAttribute("finalPerspective"));
            row.put("preferredPerspectives", element.getAttribute("preferredPerspectives"));
            row.put("contributor", element.getDeclaringExtension().getContributor().getName());
            out.add(row);
        }
        return out;
    }

    private String normalizeType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return "all";
        }
        return type.trim().toLowerCase();
    }
}
