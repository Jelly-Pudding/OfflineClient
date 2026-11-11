package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.debug.BeeDebugRenderer;
import net.minecraft.client.renderer.debug.BrainDebugRenderer;
import net.minecraft.client.renderer.debug.BreezeDebugRenderer;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.client.renderer.debug.EntityBlockIntersectionDebugRenderer;
import net.minecraft.client.renderer.debug.GameEventListenerRenderer;
import net.minecraft.client.renderer.debug.GoalSelectorDebugRenderer;
import net.minecraft.client.renderer.debug.NeighborsUpdateRenderer;
import net.minecraft.client.renderer.debug.PathfindingRenderer;
import net.minecraft.client.renderer.debug.PoiDebugRenderer;
import net.minecraft.client.renderer.debug.RaidDebugRenderer;
import net.minecraft.client.renderer.debug.RedstoneWireOrientationsRenderer;
import net.minecraft.client.renderer.debug.StructureRenderer;
import net.minecraft.client.renderer.debug.VillageSectionsDebugRenderer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.debug.DebugSubscription;
import net.minecraft.util.debug.DebugSubscriptions;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

// Switches on the game's own debug drawings that need data from the built in server. The game
// asks for that data only in its development builds and a server sends it to operators alone.
// Nothing is asked of any other server. In single player the owner is an operator whilst cheats
// are on.
public final class DebugDrawings extends Module {

    private enum Drawing {
        PATHS("Mob paths", "The path each mob means to walk and the blocks it looked at on the way.",
            List.of(DebugSubscriptions.ENTITY_PATHS), mc -> new PathfindingRenderer()),
        GOALS("Mob goals", "What each mob near you is trying to do.",
            List.of(DebugSubscriptions.GOAL_SELECTORS), GoalSelectorDebugRenderer::new),
        BRAINS("Brains", "What the mob you look at remembers and plans.",
            List.of(DebugSubscriptions.BRAINS), BrainDebugRenderer::new),
        BEES("Bees", "Where each bee is headed and the hives and flowers it knows.",
            List.of(DebugSubscriptions.BEES, DebugSubscriptions.BEE_HIVES, DebugSubscriptions.GOAL_SELECTORS),
            BeeDebugRenderer::new),
        BREEZES("Breezes", "Where each breeze aims its jumps and its shots.",
            List.of(DebugSubscriptions.BREEZES), BreezeDebugRenderer::new),
        POINTS("Points of interest", "Work blocks and beds and bells and who has claimed each.",
            List.of(DebugSubscriptions.POIS), mc -> new PoiDebugRenderer(new BrainDebugRenderer(mc))),
        VILLAGES("Village sections", "The sections of the world that count as a village.",
            List.of(DebugSubscriptions.VILLAGE_SECTIONS), mc -> new VillageSectionsDebugRenderer()),
        RAIDS("Raids", "The centre of every raid going on.",
            List.of(DebugSubscriptions.RAIDS), RaidDebugRenderer::new),
        STRUCTURES("Structures", "The outline of every structure and its pieces.",
            List.of(DebugSubscriptions.STRUCTURES), mc -> new StructureRenderer()),
        EVENTS("Game events", "The game events sculk sensors hear and every listener that can hear them.",
            List.of(DebugSubscriptions.GAME_EVENTS, DebugSubscriptions.GAME_EVENT_LISTENERS),
            mc -> new GameEventListenerRenderer()),
        NEIGHBOURS("Neighbour updates", "Each block a change next to it wakes up.",
            List.of(DebugSubscriptions.NEIGHBOR_UPDATES), mc -> new NeighborsUpdateRenderer()),
        WIRES("Redstone wire order", "The order redstone wire updates in where the newer redstone is on.",
            List.of(DebugSubscriptions.REDSTONE_WIRE_ORIENTATIONS), mc -> new RedstoneWireOrientationsRenderer()),
        INSIDE("Blocks in entities", "The blocks each entity overlaps.",
            List.of(DebugSubscriptions.ENTITY_BLOCK_INTERSECTIONS), mc -> new EntityBlockIntersectionDebugRenderer());

        private final String label;
        private final String description;
        private final List<DebugSubscription<?>> data;
        private final Function<Minecraft, DebugRenderer.SimpleDebugRenderer> renderer;

        Drawing(String label, String description, List<DebugSubscription<?>> data,
                Function<Minecraft, DebugRenderer.SimpleDebugRenderer> renderer) {
            this.label = label;
            this.description = description;
            this.data = data;
            this.renderer = renderer;
        }
    }

    // The server tells a joining player their operator level a moment after the world loads.
    private static final int PERMISSION_WAIT_TICKS = 100;
    private static final int CHECKED = -1;

    private final Map<Drawing, BoolSetting> switches = new EnumMap<>(Drawing.class);
    private final WorldWatch world = new WorldWatch();
    private int permissionCheckIn = CHECKED;
    // The drawings the renderer was last told about.
    private Set<Drawing> shown = EnumSet.noneOf(Drawing.class);

    public DebugDrawings() {
        super("DebugDrawings", "Shows the game's own debug drawings in single player whilst cheats are on.",
            Category.RENDER);
        for (Drawing drawing : Drawing.values()) {
            BoolSetting setting = new BoolSetting(drawing.label, drawing.description, drawing == Drawing.PATHS);
            switches.put(drawing, setting);
            addSettings(setting);
        }
        searchTags("debug renderer", "debug", "pathfinding", "brain", "poi", "village", "structures");
    }

    @Override
    protected void onEnable() {
        world.forget();
        refresh();
    }

    @Override
    protected void onDisable() {
        shown = EnumSet.noneOf(Drawing.class);
        rebuild();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (world.changed()) {
            permissionCheckIn = PERMISSION_WAIT_TICKS;
        }
        if (permissionCheckIn != CHECKED && permissionCheckIn-- == 0) {
            permissionCheckIn = CHECKED;
            tellWhyNothingShows();
        }
        if (!picked().equals(shown)) {
            refresh();
        }
    }

    private void tellWhyNothingShows() {
        if (!mc.hasSingleplayerServer()) {
            ChatUtil.error("DebugDrawings works only in single player.");
        } else if (!mc.player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
            ChatUtil.error("DebugDrawings shows nothing here. The game sends these drawings only whilst cheats are on.");
        }
    }

    // The drawings switched on. None outside single player.
    private Set<Drawing> picked() {
        Set<Drawing> picked = EnumSet.noneOf(Drawing.class);
        if (!mc.hasSingleplayerServer()) {
            return picked;
        }
        switches.forEach((drawing, setting) -> {
            if (setting.isOn()) {
                picked.add(drawing);
            }
        });
        return picked;
    }

    private void refresh() {
        shown = picked();
        rebuild();
    }

    // The saved config switches modules on before the game has made its renderer. The game then
    // builds the list itself once it has.
    private static void rebuild() {
        if (mc.levelExtractor != null) {
            mc.levelExtractor.debugRenderer.refreshRendererList();
        }
    }

    // The data the game asks the server for on top of its own.
    public Set<DebugSubscription<?>> subscriptions() {
        Set<DebugSubscription<?>> wanted = new HashSet<>();
        for (Drawing drawing : inUse()) {
            wanted.addAll(drawing.data);
        }
        return wanted;
    }

    // Fresh renderers for the picked drawings each time the game builds its list.
    public List<DebugRenderer.SimpleDebugRenderer> renderers() {
        List<DebugRenderer.SimpleDebugRenderer> renderers = new ArrayList<>();
        for (Drawing drawing : inUse()) {
            renderers.add(drawing.renderer.apply(mc));
        }
        return renderers;
    }

    // None outside single player. A new connection asks for its data before the next tick has
    // caught up with the change.
    private Set<Drawing> inUse() {
        return mc.hasSingleplayerServer() ? shown : Set.of();
    }
}
