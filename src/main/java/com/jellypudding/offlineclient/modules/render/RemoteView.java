package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.FakePlayer;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Puts your eyes inside another entity. Your body waits where you left it and
// the server never hears about the trip.
public final class RemoteView extends Module {

    public enum Pick { NEAREST, NAME }

    private static final double SEARCH_RANGE = 128;
    // Fake health for the stand in body.
    private static final float BODY_HEALTH = 20;

    private final EnumSetting<Pick> pick = new EnumSetting<>("View", "Whose eyes are borrowed.",
        Pick.NEAREST)
        .describe(Pick.NEAREST, "The nearest entity the filter allows.")
        .describe(Pick.NAME, "The entity whose name you type in.");
    private final TextSetting name = new TextSetting("Name",
        "The name of the entity to view. Click to type it.", "")
        .under(pick, Pick.NAME);
    private final EntityFilter filter = EntityFilter.living("View", "viewed", true,
        EntityFilter.Pick.ALL, List.of());

    private Entity viewed;
    private boolean wasInvisible;
    private FakePlayer.Body body;

    public RemoteView() {
        super("RemoteView", "See the world through the eyes of another player or mob.", Category.RENDER);
        addSettings(pick, name);
        addSettings(filter.settings());
        searchTags("remote view", "spectate", "possess");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return viewed == null ? null : viewed.getName().getString();
    }

    @Override
    protected void onEnable() {
        viewed = inGame() ? pickTarget() : null;
        if (viewed == null) {
            ChatUtil.error("Nothing to view was found.");
            setEnabled(false);
            return;
        }
        wasInvisible = viewed.isInvisible();
        mc.player.noPhysics = true;
        body = new FakePlayer.Body(mc.level, mc.player, BODY_HEALTH, true, true);
        mc.level.addEntity(body);
        ChatUtil.message("Now viewing §b" + viewed.getName().getString() + "§7.");
    }

    @Override
    protected void onDisable() {
        if (viewed != null) {
            viewed.setInvisible(wasInvisible);
            ChatUtil.message("No longer viewing §b" + viewed.getName().getString() + "§7.");
            viewed = null;
        }
        if (mc.player != null) {
            mc.player.noPhysics = false;
        }
        if (body != null) {
            if (mc.player != null && body.level() == mc.level) {
                mc.player.setPos(body.getX(), body.getY(), body.getZ());
                mc.player.setYRot(body.getYRot());
                mc.player.setXRot(body.getXRot());
                mc.player.setOldPosAndRot();
                mc.player.setDeltaMovement(Vec3.ZERO);
                mc.level.removeEntity(body.getId(), Entity.RemovalReason.DISCARDED);
            }
            body = null;
        }
    }

    private Entity pickTarget() {
        String wanted = name.getValue().trim();
        return EntityUtil.nearest(SEARCH_RANGE, entity -> entity instanceof LivingEntity living
            && living.isAlive() && filter.matches(entity)
            && (pick.is(Pick.NEAREST) || entity.getName().getString().equalsIgnoreCase(wanted)));
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || viewed == null || viewed.isRemoved() || viewed.level() != mc.level
            || !(viewed instanceof LivingEntity living) || !living.isAlive()) {
            setEnabled(false);
            return;
        }
        mc.player.copyPosition(viewed);
        mc.player.setPosRaw(viewed.getX(),
            viewed.getY() - mc.player.getEyeHeight(mc.player.getPose()) + viewed.getEyeHeight(viewed.getPose()),
            viewed.getZ());
        mc.player.setOldPosAndRot();
        mc.player.setDeltaMovement(Vec3.ZERO);
        viewed.setInvisible(true);
    }

    // The server must never learn where the eyes went.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundMovePlayerPacket) {
            event.cancel();
        }
    }
}
