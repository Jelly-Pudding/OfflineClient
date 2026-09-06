package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.mixinterface.IRenderState;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.ModelWireframe;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

// LevelExtractorMixin feeds the frozen copies into the frame.
public final class PopChams extends Module {

    public enum Mode { MODEL, WIREFRAME }

    // The state is built on the game thread during extraction.
    private static final class Ghost {

        private final Player player;
        private final UUID id;
        private final Vec3 pos;
        private final long start;
        private EntityRenderState state;
        private float baseScale = 1;
        private List<Vec3[]> quads;
        private Vec3 origin;

        private Ghost(Player player, Vec3 pos, long start) {
            this.player = player;
            this.id = player.getUUID();
            this.pos = pos;
            this.start = start;
        }
    }

    private static final int MAX_GHOSTS = 32;

    private final NumberSetting duration = new NumberSetting("Duration",
        "How long the copy takes to fade away.", 1.5, 0.5, 5, 0.1, "s").min(0.1).max(30);
    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode", "How the copy is drawn.", Mode.MODEL)
        .describe(Mode.MODEL, "The textured player model in one tint.")
        .describe(Mode.WIREFRAME, "Boxes around each part of the model.");
    private final ColorSetting color = new ColorSetting("Colour",
        "Copy colour.", 0, false).under(mode, Mode.MODEL);
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How solid the copy starts out.", 0.6, 0.1, 1, 0.05).min(0.05).max(1).under(mode, Mode.MODEL);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 0f).under(mode, Mode.WIREFRAME);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show the copy over blocks.", true);
    private final BoolSetting self = new BoolSetting("Self",
        "Also show your own pops.", false);
    private final BoolSetting onlyOne = new BoolSetting("Only one",
        "A new pop replaces that player's earlier copy instead of adding another.", false);
    private final NumberSetting rise = new NumberSetting("Rise",
        "Blocks per second the copy drifts upward. Negative sinks it.", 0.75, -4, 4, 0.05, " blocks");
    private final NumberSetting grow = new NumberSetting("Grow",
        "How much the copy's size changes each second. Negative shrinks it.", -0.25, -4, 4, 0.05);
    private final BoolSetting fadeOut = new BoolSetting("Fade out",
        "The copy fades as it ages. Off keeps it solid until it vanishes.", true);

    // Entity ids filled on the netty thread and drained on the game thread.
    private final Queue<Integer> pending = new ConcurrentLinkedQueue<>();
    private final List<Ghost> ghosts = new ArrayList<>();

    public PopChams() {
        super("PopChams", "Flashes a copy of a player where their totem popped.", Category.RENDER);
        addSettings(duration, mode, color, opacity);
        addSettings(style.settings());
        addSettings(throughWalls, self, onlyOne, rise, grow, fadeOut);
        searchTags("totem", "pop", "chams");
    }

    @Override
    protected void onEnable() {
        pending.clear();
        ghosts.clear();
    }

    @Override
    protected void onDisable() {
        pending.clear();
        ghosts.clear();
    }

    // Fired on the netty thread.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundEntityEventPacket packet
            && packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH) {
            pending.add(packet.entityId);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        Integer id;
        while ((id = pending.poll()) != null) {
            if (!inGame() || !(mc.level.getEntity(id) instanceof Player player)) {
                continue;
            }
            if (player == mc.player && !self.isOn()) {
                continue;
            }
            if (player.isSpectator()) {
                continue;
            }
            if (onlyOne.isOn()) {
                ghosts.removeIf(ghost -> ghost.id.equals(player.getUUID()));
            }
            Ghost ghost = new Ghost(player, player.position(), System.currentTimeMillis());
            if (mode.is(Mode.WIREFRAME)) {
                ghost.quads = ModelWireframe.capture(player, 1);
                ghost.origin = ModelWireframe.origin(player, 1);
            }
            ghosts.add(ghost);
            if (ghosts.size() > MAX_GHOSTS) {
                ghosts.removeFirst();
            }
        }
    }

    // Share of the copy's life that has passed. One or more once it is spent.
    private float ageOf(Ghost ghost, long now) {
        return (float) ((now - ghost.start) / (duration.getValue() * 1000));
    }

    private float strength(float age) {
        return fadeOut.isOn() ? 1 - age : 1;
    }

    private double lift(float age) {
        return rise.getValue() * age * duration.getValue();
    }

    private double sizeOf(float age) {
        return Math.max(0, 1 + grow.getValue() * age * duration.getValue());
    }

    // Called on the game thread once the world entities are extracted.
    public void addGhosts(LevelRenderState level, float partialTicks) {
        if (ghosts.isEmpty() || mc.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int base = color.getColor();
        double peak = opacity.getValue() * 255;

        Iterator<Ghost> iterator = ghosts.iterator();
        while (iterator.hasNext()) {
            Ghost ghost = iterator.next();
            float age = ageOf(ghost, now);
            if (age >= 1) {
                iterator.remove();
                continue;
            }
            if (ghost.quads != null) {
                continue;
            }
            if (ghost.state == null) {
                if (ghost.player.isRemoved()) {
                    iterator.remove();
                    continue;
                }
                ghost.state = freeze(ghost, partialTicks);
            }
            ghost.state.y = ghost.pos.y + lift(age);
            if (ghost.state instanceof LivingEntityRenderState living) {
                living.scale = (float) (ghost.baseScale * sizeOf(age));
            }
            IRenderState extra = (IRenderState) ghost.state;
            extra.offlineclient$clear();
            extra.offlineclient$setForceVisible(true);
            extra.offlineclient$setChams(throughWalls.isOn());
            extra.offlineclient$setTint(ColorUtil.withAlpha(base, (int) (peak * strength(age))));
            level.entityRenderStates.add(ghost.state);
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        long now = System.currentTimeMillis();
        DrawBatch batch = event.getBatch();
        for (Ghost ghost : ghosts) {
            if (ghost.quads == null) {
                continue;
            }
            float age = ageOf(ghost, now);
            if (age >= 1) {
                continue;
            }
            float strength = strength(age);
            ModelWireframe.draw(batch, ghost.quads, ghost.origin.add(0, lift(age), 0), sizeOf(age),
                style.shape(), ColorUtil.fade(style.lineColor(), strength),
                ColorUtil.fade(style.fillColor(), strength), throughWalls.isOn());
        }
    }

    private EntityRenderState freeze(Ghost ghost, float partialTicks) {
        EntityRenderState state = mc.getEntityRenderDispatcher().extractEntity(ghost.player, partialTicks);
        state.x = ghost.pos.x;
        state.y = ghost.pos.y;
        state.z = ghost.pos.z;
        state.nameTag = null;
        state.scoreText = null;
        state.displayFireAnimation = false;
        state.outlineColor = EntityRenderState.NO_OUTLINE;
        state.shadowRadius = 0;
        state.shadowPieces.clear();
        state.leashStates = null;
        if (state instanceof LivingEntityRenderState living) {
            ghost.baseScale = living.scale;
        }
        return state;
    }
}
