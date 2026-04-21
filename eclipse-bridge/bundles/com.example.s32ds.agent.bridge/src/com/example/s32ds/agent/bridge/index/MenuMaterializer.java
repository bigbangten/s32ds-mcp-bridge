package com.example.s32ds.agent.bridge.index;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.IMenuService;

import com.example.s32ds.agent.bridge.util.UiThread;

public final class MenuMaterializer {
    private final StateInspector stateInspector = new StateInspector();

    public Map<String, Object> materialize(String locationUri) {
        return UiThread.sync(() -> {
            IMenuService menuService = PlatformUI.getWorkbench().getService(IMenuService.class);
            if (menuService == null) {
                throw new IllegalStateException("IMenuService is not available");
            }

            MenuManager manager = new MenuManager();
            try {
                menuService.populateContributionManager(manager, locationUri);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("locationUri", locationUri);
                result.put("items", readContributionItems(manager.getItems()));
                result.put("context", stateInspector.contextSnapshot());
                return result;
            } finally {
                menuService.releaseContributions(manager);
                manager.dispose();
            }
        });
    }

    private List<Map<String, Object>> readContributionItems(IContributionItem[] items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IContributionItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("text", null);
            row.put("visible", Boolean.valueOf(item.isVisible()));
            row.put("enabled", Boolean.valueOf(item.isEnabled()));
            row.put("class", item.getClass().getName());
            row.put("commandId", null);
            row.put("children", new ArrayList<>());

            if (item instanceof ActionContributionItem) {
                IAction action = ((ActionContributionItem) item).getAction();
                row.put("text", action.getText());
                row.put("commandId", action.getActionDefinitionId());
                row.put("actionId", action.getId());
            } else if (item instanceof CommandContributionItem) {
                CommandContributionItem commandItem = (CommandContributionItem) item;
                row.put("commandId", commandItem.getCommand() != null ? commandItem.getCommand().getId() : null);
                row.put("text", readField(commandItem.getData(), "label"));
                row.put("tooltip", readField(commandItem.getData(), "tooltip"));
            } else if (item instanceof MenuManager) {
                MenuManager menu = (MenuManager) item;
                row.put("text", menu.getMenuText());
                row.put("children", readContributionItems(menu.getItems()));
            }

            out.add(row);
        }
        return out;
    }

    private Object readField(Object instance, String fieldName) {
        if (instance == null) {
            return null;
        }
        try {
            Field field = instance.getClass().getField(fieldName);
            return field.get(instance);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
