package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.mixinterface.IRenderState;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// LevelExtractorMixin feeds the frozen copies into the frame.
public final class PopChams extends Module {

    // The state is built on the render thread.
    private static final class Ghost {

        private final Player player;
        private final Vec3 pos;
        private final long start;
        private EntityRenderState state;

        private Ghost(Player player, Vec3 pos, long start) {
            this.player = player;
            this.pos = pos;
            this.start = start;
        }
    }

    private static final int MAX_GHOSTS = 32;

    private final NumberSetting duration = new NumberSetting("Duration",
        "How long the copy takes to fade away.", 1.5, 0.5, 5, 0.1, "s").min(0.1).max(30);
    private final ColorSetting color = new ColorSetting("Color",
        "Copy colour.", 0, false);
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How solid the copy starts out.", 0.6, 0.1, 1, 0.05).min(0.05).max(1);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show the copy over blocks.", true);
    private final BoolSetting self = new BoolSetting("Self",
        "Also show your own pops.", false);

    // Entity ids filled on the netty thread and drained on the game thread.
    private final Queue<Integer> pending = new ConcurrentLinkedQueue<>();
    private final List<Ghost> ghosts = new ArrayList<>();

    public PopChams() {
        super("PopChams", "Flashes a copy of a player where their totem popped.", Category.RENDER);
        addSettings(duration, color, opacity, throughWalls, self);
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
            ghosts.add(new Ghost(player, player.position(), System.currentTimeMillis()));
            if (ghosts.size() > MAX_GHOSTS) {
                ghosts.removeFirst();
            }
        }
    }

    // Called from the render thread once the world entities are extracted.
    public void addGhosts(LevelRenderState level, float partialTicks) {
        if (ghosts.isEmpty() || mc.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        double life = duration.getValue() * 1000;
        int base = color.getColor();
        double peak = opacity.getValue() * 255;

        Iterator<Ghost> iterator = ghosts.iterator();
        while (iterator.hasNext()) {
            Ghost ghost = iterator.next();
            float age = (float) ((now - ghost.start) / life);
            if (age >= 1) {
                iterator.remove();
                continue;
            }
            if (ghost.state == null) {
                if (ghost.player.isRemoved()) {
                    iterator.remove();
                    continue;
                }
                ghost.state = freeze(ghost, partialTicks);
            }
            IRenderState extra = (IRenderState) ghost.state;
            extra.offlineclient$clear();
            extra.offlineclient$setForceVisible(true);
            extra.offlineclient$setChams(throughWalls.isOn());
            extra.offlineclient$setTint(ColorUtil.withAlpha(base, (int) (peak * (1 - age))));
            level.entityRenderStates.add(ghost.state);
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
        return state;
    }
}
