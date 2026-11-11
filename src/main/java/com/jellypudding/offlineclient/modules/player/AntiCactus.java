package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// BlockCollisionsMixin gives a cactus a full block shape and the player never enters the
// block a cactus hurts in. The server also lets a copy of the player fall each tick from
// where they stand and puts it back. On top of a cactus that copy drops the sixteenth
// down to the real top and is hurt there. The server jumps a player it holds on the
// ground who then rises and a jump sends the copy up for six ticks instead.
public final class AntiCactus extends Module {

    // How far the real top of a cactus lies under the full block the player stands on.
    private static final double TOP_GAP = 1.0 / 16;

    // Ticks from one jump of the copy to the next. Two short of what one jump covers.
    private static final int JUMP_EVERY = 4;

    // The rise that reads as leaving the ground.
    private static final double RISE = 1.0E-6;

    // A block the box only touches along an edge holds nothing up.
    private static final double EDGE = 1.0E-5;

    private final BoolSetting tops = new BoolSetting("Tops",
        "Makes standing on a cactus safe as well where you have half a block of room over your head."
            + " Costs hunger whilst you stand on one.", true);

    private boolean rested;
    private int beat;

    public AntiCactus() {
        super("AntiCactus", "Stops cacti from hurting you.", Category.PLAYER);
        addSettings(tops);
    }

    @Override
    protected void onEnable() {
        rested = false;
    }

    // A ground claim and a rise in one tick jump the copy. The tick after puts the server
    // back on the spot for the next rise to be one.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame() || !tops.isOn() || !restsOnCactus()) {
            rested = false;
            return;
        }
        Vec3 at = mc.player.position();
        Vec3 risen = at.add(0, RISE, 0);
        if (!rested) {
            rested = true;
            beat = arrives(at, risen) ? 1 : 0;
            return;
        }
        if (beat == 0) {
            MoveGate.fillers(1, true);
            MoveGate.send(risen, false);
        } else if (beat == 1) {
            MoveGate.send(at, true);
        }
        beat = (beat + 1) % JUMP_EVERY;
    }

    // The server has yet to hear of a spot just landed on and a rise only counts from it.
    // The landing and the rise leave in one tick where two positions may. False where
    // only one may and the jump waits a tick.
    private static boolean arrives(Vec3 at, Vec3 risen) {
        return MoveGate.bursts() && MoveGate.send(at, true) && MoveGate.endTick() && MoveGate.send(risen, false);
    }

    // The copy rides its jump off the ground and the server judges a break by the ground
    // flag it holds. A claim just ahead of the break puts the player back on the ground
    // for it.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        if (player != null && rested && PacketUtil.judgesBreak(event.getPacket())) {
            PacketUtil.claimGround(player);
        }
    }

    // True whilst nothing but cactus holds the player up and the copy has room to jump. A
    // real block beside the cactus holds the copy up as well. A copy that bumps its head
    // on the first tick of a jump stops dead and falls into the cactus all the same.
    private boolean restsOnCactus() {
        if (!mc.player.onGround() || mc.player.isPassenger() || mc.player.getAbilities().invulnerable) {
            return false;
        }
        AABB slim = mc.player.getBoundingBox().deflate(EDGE, 0, EDGE);
        AABB under = slim.move(0, -TOP_GAP, 0);
        double jump = mc.player.getAttributeValue(Attributes.JUMP_STRENGTH) + mc.player.getJumpBoostPower();
        // With no entity given the game answers with the shape the server collides with.
        return mc.level.noCollision(under) && mc.level.noCollision(slim.expandTowards(0, jump, 0))
            && mc.level.getBlockStates(under.setMaxY(under.minY)).anyMatch(state -> state.is(Blocks.CACTUS));
    }
}
