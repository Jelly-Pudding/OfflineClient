package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.mixin.MultiPlayerGameModeAccessor;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// The server sends a destruction stage from zero to nine for every block being broken.
public final class BreakIndicators extends Module {

    private record Stage(int breaker, BlockPos pos, float progress) {
    }

    private static final class Indicator {

        private final BlockPos pos;
        private final String name;
        private float progress;
        private long updated;

        private Indicator(BlockPos pos, String name, float progress, long updated) {
            this.pos = pos;
            this.name = name;
            this.progress = progress;
            this.updated = updated;
        }
    }

    private static final int STAGES = 10;
    // The server stops sending updates when a player walks off mid break.
    private static final long TIMEOUT_MS = 2000;

    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show boxes behind blocks.", true);
    private final BoolSetting progressColor = new BoolSetting("Colour by progress",
        "Blend from the start colours to the end colours as the block gives way.", true);
    private final ColorSetting startLine = new ColorSetting("Start line colour",
        "Edge colour of a block that has just been hit.", 120, 0.9f, 0.99f, false)
        .under(progressColor);
    private final ColorSetting startFill = new ColorSetting("Start fill colour",
        "Face colour of a block that has just been hit.", 120, 0.9f, 0.99f, false)
        .under(progressColor);
    private final ColorSetting endLine = new ColorSetting("End line colour",
        "Edge colour of a block about to break.", 0, 0.9f, 1f, false)
        .under(progressColor);
    private final ColorSetting endFill = new ColorSetting("End fill colour",
        "Face colour of a block about to break.", 0, 0.9f, 1f, false)
        .under(progressColor);
    private final ColorSetting color = new ColorSetting("Colour",
        "Box colour when progress colouring is off.", 20, false)
        .unless(progressColor);
    private final BoolSetting grow = new BoolSetting("Grow",
        "Start the box small and grow it to full size.", true);
    private final BoolSetting names = new BoolSetting("Names",
        "Show who is mining above the box.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the name text.", 1, 0.5, 3, 0.1).min(0.1);
    private final BoolSetting self = new BoolSetting("Self",
        "Also show blocks you are mining.", false);

    // Filled on the netty thread and drained on the game thread.
    private final Queue<Stage> pending = new ConcurrentLinkedQueue<>();
    // The server keys break progress by breaker id.
    private final Map<Integer, Indicator> indicators = new HashMap<>();

    private WeakReference<Level> world;

    public BreakIndicators() {
        super("BreakIndicators", "Shows blocks other players are mining.", Category.RENDER);
        addSettings(style.settings());
        addSettings(throughWalls, progressColor, startLine, startFill, endLine, endFill, color,
            grow, names, scale, self);
        searchTags("mining", "break progress", "city");
    }

    @Override
    public String getSuffix() {
        return count(indicators.size());
    }

    @Override
    protected void onEnable() {
        clear();
    }

    @Override
    protected void onDisable() {
        clear();
    }

    private void clear() {
        pending.clear();
        indicators.clear();
    }

    // Fired on the netty thread.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundBlockDestructionPacket packet) {
            pending.add(new Stage(packet.getId(), packet.getPos(), stageFraction(packet.getProgress())));
        }
    }

    // Anything outside zero to nine means the job is over.
    private static float stageFraction(int stage) {
        return stage < 0 || stage >= STAGES ? -1 : (stage + 1) / (float) STAGES;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (world == null || world.get() != mc.level) {
            // Block positions and entity ids from the old world mean nothing here.
            world = new WeakReference<>(mc.level);
            clear();
            return;
        }
        long now = System.currentTimeMillis();
        Stage stage;
        while ((stage = pending.poll()) != null) {
            apply(stage, now);
        }
        if (self.isOn()) {
            applyOwnProgress(now);
        }
        indicators.values().removeIf(indicator -> now - indicator.updated > TIMEOUT_MS);
    }

    // The server never sends your own break progress back. The client keeps a
    // smooth fraction of its own which beats the ten coarse stages.
    private void applyOwnProgress(long now) {
        if (!inGame() || mc.gameMode == null) {
            return;
        }
        MultiPlayerGameModeAccessor mode = (MultiPlayerGameModeAccessor) mc.gameMode;
        BlockPos pos = mode.offlineclient$destroyBlockPos();
        if (!mc.gameMode.isDestroying() || pos == null) {
            return;
        }
        apply(new Stage(mc.player.getId(), pos, Math.clamp(mode.offlineclient$destroyProgress(), 0, 1)), now);
    }

    private void apply(Stage stage, long now) {
        if (!inGame()) {
            return;
        }
        if (!self.isOn() && stage.breaker() == mc.player.getId()) {
            return;
        }
        if (stage.progress() < 0) {
            indicators.remove(stage.breaker());
            return;
        }
        Indicator indicator = indicators.get(stage.breaker());
        if (indicator == null || !indicator.pos.equals(stage.pos())) {
            indicators.put(stage.breaker(), new Indicator(stage.pos(),
                nameOf(stage.breaker()), stage.progress(), now));
            return;
        }
        indicator.progress = stage.progress();
        indicator.updated = now;
    }

    private String nameOf(int entityId) {
        if (mc.level.getEntity(entityId) instanceof Player player) {
            return player.getGameProfile().name();
        }
        return null;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (indicators.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        boolean through = throughWalls.isOn();
        for (Indicator indicator : indicators.values()) {
            style.draw(batch, boxOf(indicator), lineColor(indicator), fillColor(indicator), through);
        }
    }

    // The block's own outline so a slab or a fence gets a box its size.
    private AABB boxOf(Indicator indicator) {
        VoxelShape shape = mc.level.getBlockState(indicator.pos).getShape(mc.level, indicator.pos);
        AABB full = shape.isEmpty() ? DrawBatch.blockBox(indicator.pos)
            : shape.bounds().move(indicator.pos).deflate(DrawBatch.BLOCK_INSET);
        if (!grow.isOn()) {
            return full;
        }
        double shrink = (1 - indicator.progress) * 0.5;
        return full.deflate(shrink * full.getXsize(), shrink * full.getYsize(), shrink * full.getZsize());
    }

    private int lineColor(Indicator indicator) {
        if (!progressColor.isOn()) {
            return color.getColor();
        }
        return ColorUtil.lerp(startLine.getColor(), endLine.getColor(), indicator.progress);
    }

    private int fillColor(Indicator indicator) {
        if (!progressColor.isOn()) {
            return color.getColor();
        }
        return ColorUtil.lerp(startFill.getColor(), endFill.getColor(), indicator.progress);
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!names.isOn() || indicators.isEmpty() || !inGame() || !WorldToScreen.update()) {
            return;
        }
        for (Indicator indicator : indicators.values()) {
            if (indicator.name == null) {
                continue;
            }
            AABB box = boxOf(indicator);
            Vec3 centre = box.getCenter();
            Vec3 top = new Vec3(centre.x, box.maxY + 0.3, centre.z);
            Vec3 screen = WorldToScreen.project(top);
            if (screen != null) {
                drawName(event.getContext(), indicator, screen);
            }
        }
    }

    private void drawName(GuiGraphicsExtractor context, Indicator indicator, Vec3 screen) {
        String text = indicator.name + " " + Math.round(indicator.progress * 100) + "%";
        RenderUtil.label(context, mc.font, screen.x, screen.y, scale.getFloat(),
            List.of(text), List.of(lineColor(indicator)));
    }
}
