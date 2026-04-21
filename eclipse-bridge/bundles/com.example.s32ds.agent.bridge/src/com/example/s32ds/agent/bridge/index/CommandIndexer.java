package com.example.s32ds.agent.bridge.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.IParameter;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import com.example.s32ds.agent.bridge.util.UiThread;

public final class CommandIndexer {
    public List<Map<String, Object>> listCommands() {
        Map<String, String> contributors = loadContributors();
        return UiThread.sync(() -> {
            ICommandService service = PlatformUI.getWorkbench().getService(ICommandService.class);
            List<Map<String, Object>> out = new ArrayList<>();
            if (service == null) {
                return out;
            }

            for (Command command : service.getDefinedCommands()) {
                out.add(toMap(command, contributors));
            }
            out.sort(Comparator.comparing(row -> String.valueOf(row.get("id"))));
            return out;
        });
    }

    public Map<String, Object> search(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Map<String, Object> row : listCommands()) {
            if (normalized.isEmpty() || containsText(row, normalized)) {
                matches.add(row);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("count", Integer.valueOf(matches.size()));
        result.put("items", matches);
        return result;
    }

    private Map<String, Object> toMap(Command command, Map<String, String> contributors) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", command.getId());
        row.put("defined", Boolean.valueOf(command.isDefined()));
        row.put("enabledNow", Boolean.valueOf(safeEnabled(command)));
        row.put("handledNow", Boolean.valueOf(safeHandled(command)));
        row.put("contributor", contributors.get(command.getId()));

        try {
            row.put("name", command.getName());
            row.put("description", command.getDescription());
            row.put("categoryId", command.getCategory() != null ? command.getCategory().getId() : null);
            row.put("categoryName", command.getCategory() != null ? command.getCategory().getName() : null);
            row.put("parameters", listParameters(command));
        } catch (NotDefinedException e) {
            row.put("name", null);
            row.put("description", null);
            row.put("categoryId", null);
            row.put("categoryName", null);
            row.put("parameters", Collections.emptyList());
        }
        return row;
    }

    private List<Map<String, Object>> listParameters(Command command) {
        try {
            IParameter[] parameters = command.getParameters();
            if (parameters == null || parameters.length == 0) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (IParameter parameter : parameters) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", parameter.getId());
                try {
                    row.put("name", parameter.getName());
                } catch (Throwable e) {
                    row.put("name", null);
                }
                try {
                    row.put("optional", Boolean.valueOf(parameter.isOptional()));
                } catch (Throwable ignored) {
                    row.put("optional", null);
                }
                row.put("values", describeCommandValues(command));
                out.add(row);
            }
            return out;
        } catch (NotDefinedException e) {
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> describeCommandValues(Command command) {
        try {
            ParameterizedCommand generated = ParameterizedCommand.generateCommand(command, null);
            if (generated == null || generated.getParameterMap() == null) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> values = new ArrayList<>();
            @SuppressWarnings("rawtypes")
            java.util.Map rawMap = generated.getParameterMap();
            @SuppressWarnings("rawtypes")
            java.util.Set rawEntries = rawMap.entrySet();
            for (Object obj : rawEntries) {
                if (!(obj instanceof Map.Entry)) {
                    continue;
                }
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) obj;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", String.valueOf(entry.getKey()));
                row.put("value", String.valueOf(entry.getValue()));
                values.add(row);
            }
            return values;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private boolean safeEnabled(Command command) {
        try {
            return command.isEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean safeHandled(Command command) {
        try {
            return command.isHandled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Map<String, String> loadContributors() {
        Map<String, String> contributors = new LinkedHashMap<>();
        IConfigurationElement[] elements = Platform.getExtensionRegistry()
                .getConfigurationElementsFor("org.eclipse.ui.commands");
        Arrays.stream(elements)
                .filter(element -> "command".equals(element.getName()))
                .forEach(element -> contributors.put(element.getAttribute("id"),
                        element.getDeclaringExtension().getContributor().getName()));
        return contributors;
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
