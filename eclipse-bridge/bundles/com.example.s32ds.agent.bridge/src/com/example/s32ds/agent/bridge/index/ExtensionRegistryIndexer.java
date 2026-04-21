package com.example.s32ds.agent.bridge.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;

public final class ExtensionRegistryIndexer {
    public List<Map<String, Object>> listMenus(String query) {
        return listExtensionPoint("org.eclipse.ui.menus", query);
    }

    public Map<String, Object> listLegacyActions(String query) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionSets", listExtensionPoint("org.eclipse.ui.actionSets", query));
        data.put("popupMenus", listExtensionPoint("org.eclipse.ui.popupMenus", query));
        return data;
    }

    public List<Map<String, Object>> listExtensionPoint(String extensionPointId, String query) {
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        IConfigurationElement[] elements = registry.getConfigurationElementsFor(extensionPointId);
        List<Map<String, Object>> out = new ArrayList<>();
        String normalized = normalize(query);
        for (IConfigurationElement element : elements) {
            Map<String, Object> row = toMap(extensionPointId, element);
            if (normalized == null || containsText(row, normalized)) {
                out.add(row);
            }
        }
        return out;
    }

    private Map<String, Object> toMap(String extensionPointId, IConfigurationElement element) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("extensionPoint", extensionPointId);
        row.put("elementName", element.getName());
        row.put("contributor", element.getDeclaringExtension().getContributor().getName());
        row.put("extensionId", element.getDeclaringExtension().getUniqueIdentifier());
        row.put("namespaceIdentifier", element.getDeclaringExtension().getNamespaceIdentifier());

        Map<String, Object> attributes = new LinkedHashMap<>();
        for (String name : element.getAttributeNames()) {
            attributes.put(name, element.getAttribute(name));
        }
        row.put("attributes", attributes);

        row.put("id", firstNonBlank(element.getAttribute("id"), element.getAttribute("commandId")));
        row.put("label", firstNonBlank(element.getAttribute("label"), element.getAttribute("name")));
        row.put("commandId", element.getAttribute("commandId"));
        row.put("locationURI", firstNonBlank(element.getAttribute("locationURI"), element.getAttribute("locationUri")));
        row.put("style", element.getAttribute("style"));
        row.put("visibleWhen", readExpression(element, "visibleWhen"));
        row.put("enabledWhen", readExpression(element, "enabledWhen"));

        List<Map<String, Object>> children = new ArrayList<>();
        for (IConfigurationElement child : element.getChildren()) {
            children.add(toMap(extensionPointId, child));
        }
        row.put("children", children);
        return row;
    }

    private Object readExpression(IConfigurationElement element, String name) {
        for (IConfigurationElement child : element.getChildren(name)) {
            return toMap(element.getDeclaringExtension().getExtensionPointUniqueIdentifier(), child);
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private String normalize(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean containsText(Object value, String needle) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?>) {
            for (Object entry : ((Map<?, ?>) value).values()) {
                if (containsText(entry, needle)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                if (containsText(item, needle)) {
                    return true;
                }
            }
            return false;
        }
        return String.valueOf(value).toLowerCase().contains(needle);
    }
}
