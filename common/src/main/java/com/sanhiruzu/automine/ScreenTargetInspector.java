package com.sanhiruzu.automine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ScreenTargetInspector {
    private static final int SIDEBAR_HEADER_CONTROL_W = 20;
    private static final int SIDEBAR_HEADER_CONTROL_H = 14;

    private ScreenTargetInspector() {
    }

    static JsonObject inspectCurrentScreen() {
        JsonObject out = new JsonObject();
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        if (screen == null) {
            out.addProperty("screenPresent", false);
            out.add("targets", new JsonArray());
            out.addProperty("targetCount", 0);
            return out;
        }

        List<Target> targets = collectTargets(screen);
        JsonArray arr = new JsonArray();
        for (Target target : targets) {
            arr.add(target.toJson());
        }
        out.addProperty("screenPresent", true);
        out.addProperty("screenClass", screen.getClass().getName());
        out.add("targets", arr);
        out.addProperty("targetCount", targets.size());
        return out;
    }

    static JsonObject clickTarget(String targetId, int button) {
        JsonObject out = new JsonObject();
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        if (screen == null) {
            out.addProperty("screenPresent", false);
            out.addProperty("targetFound", false);
            out.addProperty("error", "No screen is open.");
            return out;
        }

        List<Target> beforeTargets = collectTargets(screen);
        Target before = findTarget(beforeTargets, targetId);
        out.addProperty("screenPresent", true);
        out.addProperty("targetId", targetId);
        out.addProperty("targetFound", before != null);
        if (before == null) {
            out.addProperty("error", "Unknown screen target: " + targetId);
            return out;
        }

        JsonElement beforeState = before.snapshotState();
        out.add("targetBefore", before.toJson());
        out.add("beforeState", beforeState);

        boolean clicked = before.click(button);
        out.addProperty("clicked", clicked);

        Screen afterScreen = minecraft.screen;
        List<Target> afterTargets = afterScreen == null ? List.of() : collectTargets(afterScreen);
        Target after = findTarget(afterTargets, targetId);
        JsonElement afterState = after != null ? after.snapshotState() : JsonNull.INSTANCE;
        boolean targetChanged = after == null
                || !Objects.equals(before.toJson().toString(), after.toJson().toString());
        out.addProperty("targetPresentAfter", after != null);
        out.add("afterState", afterState);
        out.addProperty("stateChanged",
                targetChanged || !Objects.equals(beforeState.toString(), afterState.toString()));
        if (after != null) {
            out.add("targetAfter", after.toJson());
        }
        return out;
    }

    private static Target findTarget(List<Target> targets, String id) {
        for (Target target : targets) {
            if (target.id.equals(id)) {
                return target;
            }
        }
        return null;
    }

    private static List<Target> collectTargets(Screen screen) {
        Map<String, Target> targets = new LinkedHashMap<>();
        addRecipeBookTarget(screen, targets);
        addAmiTargets(screen, targets);
        addWidgetTargets(screen, targets);
        return new ArrayList<>(targets.values());
    }

    private static void addRecipeBookTarget(Screen screen, Map<String, Target> targets) {
        if (!(screen instanceof RecipeUpdateListener recipeScreen)) {
            return;
        }

        AbstractWidget widget = findRecipeBookWidget(screen);
        if (widget == null || !widget.visible) {
            return;
        }

        targets.put("recipe_book_button", new Target(
                "recipe_book_button",
                "Recipe Book",
                "semantic",
                widget.getX(),
                widget.getY(),
                widget.getWidth(),
                widget.getHeight(),
                () -> {
                    JsonObject state = new JsonObject();
                    state.addProperty("recipeBookVisible", recipeScreen.getRecipeBookComponent().isVisible());
                    return state;
                },
                button -> screen.mouseClicked(centerX(widget), centerY(widget), button)
        ));
    }

    private static AbstractWidget findRecipeBookWidget(Screen screen) {
        AbstractWidget reflected = findNamedWidgetField(screen, Set.of("recipe", "book", "button"));
        if (reflected != null) {
            return reflected;
        }
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return null;
        }

        // The Forge-only accessors getGuiLeft/getGuiTop are not part of the
        // 1.21.1 official mappings used by Fabric. This remains a fallback
        // heuristic after reflected widget discovery: the recipe button lives
        // around the same screen-centred location in the vanilla container UI.
        int expectedLeft = screen.width / 2 + 16;
        int expectedTop = screen.height / 2 - 22;
        AbstractWidget best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (GuiEventListener listener : screen.children()) {
            if (!(listener instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            int width = widget.getWidth();
            int height = widget.getHeight();
            if (width < 12 || width > 24 || height < 12 || height > 24) {
                continue;
            }
            int dx = Math.abs(widget.getX() - expectedLeft);
            int dy = Math.abs(widget.getY() - expectedTop);
            int distance = dx + dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = widget;
            }
        }
        return bestDistance <= 48 ? best : null;
    }

    private static void addAmiTargets(Screen screen, Map<String, Target> targets) {
        Object manager = getAmiManager();
        if (manager == null) {
            return;
        }

        addAmiFavoritesCollapseTarget(manager, targets);
        addAmiFavoritesExpandTarget(manager, targets);
    }

    private static void addAmiFavoritesCollapseTarget(Object manager, Map<String, Target> targets) {
        try {
            Object sidebar = manager.getClass().getMethod("getLeftPanel").invoke(manager);
            if (!(sidebar instanceof AbstractWidget widget) || !widget.visible) {
                return;
            }
            Method contentTypeMethod = sidebar.getClass().getMethod("getContentType");
            Object contentType = contentTypeMethod.invoke(sidebar);
            if (contentType == null || !"FAVORITES".equals(String.valueOf(contentType))) {
                return;
            }

            Object innerPanel = sidebar.getClass().getMethod("getInnerPanel").invoke(sidebar);
            int x = invokePrivateInt(innerPanel, "collapseBtnX", Integer.MIN_VALUE);
            int y = invokePrivateInt(innerPanel, "sidebarSwapY", Integer.MIN_VALUE);
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
                return;
            }

            targets.put("ami_favorites_collapse", new Target(
                    "ami_favorites_collapse",
                    "AMI Favorites Collapse",
                    "semantic",
                    x,
                    y,
                    SIDEBAR_HEADER_CONTROL_W,
                    SIDEBAR_HEADER_CONTROL_H,
                    () -> snapshotAmiFavoritesState(manager),
                    button -> invokeManagerMouseClick(manager, x + SIDEBAR_HEADER_CONTROL_W / 2.0D,
                            y + SIDEBAR_HEADER_CONTROL_H / 2.0D, button)
            ));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static void addAmiFavoritesExpandTarget(Object manager, Map<String, Target> targets) {
        try {
            Object bounds = getFieldValue(manager, "leftPanelExpandBtnBounds");
            if (bounds == null) {
                return;
            }
            int x = invokeIntMethod(bounds, "x", Integer.MIN_VALUE);
            int y = invokeIntMethod(bounds, "y", Integer.MIN_VALUE);
            int width = invokeIntMethod(bounds, "width", Integer.MIN_VALUE);
            int height = invokeIntMethod(bounds, "height", Integer.MIN_VALUE);
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || width <= 0 || height <= 0) {
                return;
            }

            targets.put("ami_favorites_expand", new Target(
                    "ami_favorites_expand",
                    "AMI Favorites Expand",
                    "semantic",
                    x,
                    y,
                    width,
                    height,
                    () -> snapshotAmiFavoritesState(manager),
                    button -> invokeManagerMouseClick(manager, x + width / 2.0D, y + height / 2.0D, button)
            ));
        } catch (RuntimeException ignored) {
        }
    }

    private static JsonObject snapshotAmiFavoritesState(Object manager) {
        JsonObject state = new JsonObject();
        state.addProperty("leftPanelCollapsed", getBooleanField(manager, "leftPanelCollapsed", false));
        state.addProperty("hasVisibleFavoritesPanel", invokeBooleanMethod(manager, "hasVisibleFavoritesPanel", false));
        state.addProperty("hasCollapsedExpandBar", getFieldValue(manager, "leftPanelExpandBtnBounds") != null);
        return state;
    }

    private static boolean invokeManagerMouseClick(Object manager, double x, double y, int button) {
        try {
            Method method = manager.getClass().getMethod("mouseClicked", double.class, double.class, int.class);
            Object result = method.invoke(manager, x, y, button);
            return result instanceof Boolean bool ? bool : true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static void addWidgetTargets(Screen screen, Map<String, Target> targets) {
        Set<String> usedIds = new HashSet<>(targets.keySet());
        int widgetIndex = 0;
        for (GuiEventListener listener : screen.children()) {
            if (!(listener instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            String base = widget.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String label = widget.getMessage().getString().trim();
            String suffix = label.isEmpty() ? Integer.toString(widgetIndex) : slug(label);
            String id = uniqueId(usedIds, "widget_" + base + "_" + suffix);
            targets.put(id, new Target(
                    id,
                    label.isEmpty() ? widget.getClass().getSimpleName() : label,
                    widget.getClass().getName(),
                    widget.getX(),
                    widget.getY(),
                    widget.getWidth(),
                    widget.getHeight(),
                    () -> JsonNull.INSTANCE,
                    button -> screen.mouseClicked(centerX(widget), centerY(widget), button)
            ));
            widgetIndex++;
        }
    }

    private static String uniqueId(Set<String> usedIds, String candidate) {
        String id = candidate;
        int index = 2;
        while (!usedIds.add(id)) {
            id = candidate + "_" + index++;
        }
        return id;
    }

    private static String slug(String raw) {
        String slug = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        slug = slug.replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "widget" : slug;
    }

    private static AbstractWidget findNamedWidgetField(Object object, Set<String> tokens) {
        for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!AbstractWidget.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                String name = field.getName().toLowerCase(Locale.ROOT);
                boolean allMatch = true;
                for (String token : tokens) {
                    if (!name.contains(token)) {
                        allMatch = false;
                        break;
                    }
                }
                if (!allMatch) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object value = field.get(object);
                    if (value instanceof AbstractWidget widget) {
                        return widget;
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return null;
    }

    private static Object getAmiManager() {
        try {
            Class<?> handlerClass = Class.forName("com.sanhiruzu.ami.client.InventoryOverlayHandler");
            Method method = handlerClass.getMethod("getManager");
            return method.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Object getFieldValue(Object object, String fieldName) {
        if (object == null) {
            return null;
        }
        for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean getBooleanField(Object object, String fieldName, boolean fallback) {
        Object value = getFieldValue(object, fieldName);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static boolean invokeBooleanMethod(Object object, String methodName, boolean fallback) {
        try {
            Method method = object.getClass().getMethod(methodName);
            Object value = method.invoke(object);
            return value instanceof Boolean bool ? bool : fallback;
        } catch (ReflectiveOperationException e) {
            return fallback;
        }
    }

    private static int invokePrivateInt(Object object, String methodName, int fallback) {
        try {
            Method method = object.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(object);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (ReflectiveOperationException e) {
            return fallback;
        }
    }

    private static int invokeIntMethod(Object object, String methodName, int fallback) {
        try {
            Method method = object.getClass().getMethod(methodName);
            Object value = method.invoke(object);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (ReflectiveOperationException e) {
            return fallback;
        }
    }

    private static double centerX(AbstractWidget widget) {
        return widget.getX() + widget.getWidth() / 2.0D;
    }

    private static double centerY(AbstractWidget widget) {
        return widget.getY() + widget.getHeight() / 2.0D;
    }

    private interface ClickAction {
        boolean click(int button);
    }

    private interface StateSnapshot {
        JsonElement snapshot();
    }

    private static final class Target {
        private final String id;
        private final String label;
        private final String source;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final StateSnapshot snapshot;
        private final ClickAction click;

        private Target(String id, String label, String source, int x, int y, int width, int height,
                       StateSnapshot snapshot, ClickAction click) {
            this.id = id;
            this.label = label;
            this.source = source;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.snapshot = snapshot;
            this.click = click;
        }

        private JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("id", id);
            out.addProperty("label", label);
            out.addProperty("source", source);
            out.addProperty("x", x);
            out.addProperty("y", y);
            out.addProperty("width", width);
            out.addProperty("height", height);
            out.addProperty("centerX", x + width / 2.0D);
            out.addProperty("centerY", y + height / 2.0D);
            return out;
        }

        private JsonElement snapshotState() {
            return snapshot.snapshot();
        }

        private boolean click(int button) {
            return click.click(button);
        }
    }
}
