package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
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

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// The server sends a destruction stage from zero to nine for every block being broken.
public final class BreakIndicators extends Module {

    private record Stage(int breaker, BlockPos pos, int progress) {
    }

    private static final class Indicator {

        private final BlockPos pos;
        private final String name;
        private int progress;
        private long updated;

        private Indicator(BlockPos pos, String name, int progress, long updated) {
            this.pos = pos;
            this.name = name;
            this.progress = progress;
            this.updated = updated;
        }
    }

    private static final int LOW_COLOR = 0xFF50FF50;
    private static final int HIGH_COLOR = 0xFFFF3030;
    // The server stops sending updates when a player walks off mid break.
    private static final long TIMEOUT_MS = 2000;

    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside each box.", true);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show boxes behind blocks.", true);
    private final BoolSetting progressColor = new BoolSetting("Color by progress",
        "Fade from green to red as the block gives way.", true);
    private final ColorSetting color = new ColorSetting("Color",
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
        addSettings(fill, throughWalls, progressColor, color, grow, names, scale, self);
        searchTags("mining", "break progress", "city");
    }

    @Override
    public String getSuffix() {
        return indicators.isEmpty() ? null : String.valueOf(indicators.size());
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
            pending.add(new Stage(packet.getId(), packet.getPos(), packet.getProgress()));
        }
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
        indicators.values().removeIf(indicator -> now - indicator.updated > TIMEOUT_MS);
    }

    private void apply(Stage stage, long now) {
        if (!inGame()) {
            return;
        }
        if (!self.isOn() && stage.breaker() == mc.player.getId()) {
            return;
        }
        // Anything outside zero to nine means the job is over.
        if (stage.progress() < 0 || stage.progress() > 9) {
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
            AABB box = boxOf(indicator);
            int argb = colorOf(indicator);
            batch.outlineBox(box, argb, through);
            if (fill.isOn()) {
                batch.solidBox(box, ColorUtil.withAlpha(argb, 50), through);
            }
        }
    }

    private AABB boxOf(Indicator indicator) {
        AABB full = DrawBatch.blockBox(indicator.pos);
        if (!grow.isOn()) {
            return full;
        }
        double fraction = (indicator.progress + 1) / 10.0;
        return full.deflate((1 - fraction) * 0.5);
    }

    private int colorOf(Indicator indicator) {
        if (!progressColor.isOn()) {
            return color.getColor();
        }
        return ColorUtil.lerp(LOW_COLOR, HIGH_COLOR, indicator.progress / 9f);
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
            Vec3 top = new Vec3(box.getCenter().x, box.maxY + 0.3, box.getCenter().z);
            Vec3 screen = WorldToScreen.project(top);
            if (screen != null) {
                drawName(event.getContext(), indicator, screen);
            }
        }
    }

    private void drawName(GuiGraphicsExtractor context, Indicator indicator, Vec3 screen) {
        String text = indicator.name + " " + (indicator.progress + 1) * 10 + "%";
        RenderUtil.label(context, mc.font, screen.x, screen.y, scale.getFloat(),
            List.of(text), List.of(colorOf(indicator)));
    }
}
