package com.example.s32ds.agent.bridge.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;

public final class ViewIndexer {
    public List<Map<String, Object>> listViews() {
        IConfigurationElement[] elements = Platform.getExtensionRegistry()
                .getConfigurationElementsFor("org.eclipse.ui.views");
        List<Map<String, Object>> out = new ArrayList<>();
        for (IConfigurationElement element : elements) {
            if (!"view".equals(element.getName())) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", element.getAttribute("id"));
            row.put("name", element.getAttribute("name"));
            row.put("category", element.getAttribute("category"));
            row.put("icon", element.getAttribute("icon"));
            row.put("class", element.getAttribute("class"));
            row.put("allowMultiple", element.getAttribute("allowMultiple"));
            row.put("restorable", element.getAttribute("restorable"));
            row.put("contributor", element.getDeclaringExtension().getContributor().getName());
            out.add(row);
        }
        return out;
    }
}
