package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.HeldPacket;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.MaceSmash;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.SprintPause;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// A critical hit needs the server to have seen the player come down. The packet modes hold
// the hit back and report a lift in one tick and the drop in the next and send the hit right
// behind the drop. The jump modes really jump and send the hit behind the first position on
// the way down. A mace smash rides a lift and a drop by packet and lands the hit at the bottom.
public final class Criticals extends Module {

    // A server pulls back a player whose box sinks into the floor however little. The lift
    // goes up into free air a tick ahead and the drop back to the floor is the fall.
    private static final double CRIT_LIFT = 0.0625;
    private static final double SUBTLE_LIFT = 0.0000008;

    private static final double MINI_JUMP_SPEED = 0.25;

    private static final int GIVE_UP_TICKS = 10;
    // The longest wait for a jump to turn down. A jump under a strong jump boost climbs for longer.
    private static final int JUMP_TICKS = 30;

    private enum Stage { IDLE, LIFT, DIP, READY, JUMP, SMASH }

    public enum Mode { NONE, PACKET, SUBTLE, MINI_JUMP, FULL_JUMP }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How to get the fall a critical hit needs.", Mode.PACKET)
        .describe(Mode.NONE, "No critical hits. Mace smash still works.")
        .describe(Mode.PACKET, "Lifts your reported height a sixteenth of a block and drops it again and"
            + " hits with the drop.")
        .describe(Mode.SUBTLE, "The same with a lift far too small to notice.")
        .describe(Mode.MINI_JUMP, "A small hop and the hit goes out on the way down.")
        .describe(Mode.FULL_JUMP, "A normal jump and the hit goes out on the way down.");

    private final BoolSetting onlyKillAura = new BoolSetting("Only KillAura targets",
        "Only makes hits on the KillAura target critical.", false)
        .visibleWhen(() -> !mode.is(Mode.NONE));

    private final BoolSetting mace = new BoolSetting("Mace smash",
        "Fakes a long fall whilst holding a mace to land every swing as a smash attack.", false);

    private final NumberSetting height = new NumberSetting("Height",
        "How far the faked fall is. Each block past eight adds one damage.",
        20, 2, 100, 1, " blocks").under(mace);

    private final BoolSetting skipShields = new BoolSetting("Skip shields",
        "Hits normally when the target holds up a shield or plays in creative.", true).under(mace);

    private final BoolSetting totemBypass = new BoolSetting("Totem bypass",
        "Lands more smashes whilst the target is still hurt. A totem only saves them from the first.",
        false).under(mace);

    private final NumberSetting hits = new NumberSetting("Hits",
        "How many smashes land one after another.", 3, 2, 5, 1).under(totemBypass);

    private final NumberSetting heightStep = new NumberSetting("Height step",
        "How much further each smash after the first falls. It has to hit harder than the last.",
        9, 1, 30, 1, " blocks").under(totemBypass);

    private final BoolSetting stopSprint = new BoolSetting("Stop sprinting",
        "Drops sprint for the hit and takes it straight back. A sprinting player cannot crit.",
        true);

    private final ChatWarning warning = new ChatWarning();

    private final HeldPacket<ServerboundAttackPacket> held = new HeldPacket<>();
    // The punch of the click whose hit is held. It follows the hit out.
    private boolean punchOwed;
    // The player whose hit is held. The id of its target means nothing to the next player.
    // It is let go with the hold. A player kept here keeps its whole world alive.
    private LocalPlayer heldBy;
    private Stage stage = Stage.IDLE;
    // The height the game last sent whilst a jump is under way.
    private double lastY;

    // How far the next outgoing packet moves the reported height.
    private double offset;
    private int waited;

    // The smash under way. Hits after the first go out as fresh attacks on the target.
    private int smashTarget;
    private boolean struck;
    private boolean followingUp;

    public Criticals() {
        super("Criticals", "Makes every melee hit a critical hit.", Category.COMBAT);
        addSettings(mode, onlyKillAura, mace, height, skipShields, totemBypass, hits, heightStep,
            stopSprint);
        searchTags("crit", "mace", "smash");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        clearHeld();
        warning.clear();
        SprintPause.attach(this);
    }

    @Override
    protected void onDisable() {
        clearHeld();
        SprintPause.detach(this);
    }

    // Read by Knockback. True whilst a hit held back for a critical goes out.
    public boolean releasing() {
        return held.releasing() || followingUp;
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (releasing() || !inGame() || mc.player.isSpectator()) {
            return;
        }
        if (event.getPacket() instanceof ServerboundAttackPacket attack) {
            onAttackPacket(event, attack);
        } else if (event.getPacket() instanceof ServerboundPunchPacket && stage != Stage.IDLE) {
            // The punch that ends a click starts the attack cooldown again on the server. Ahead of
            // the held hit it would leave that hit with no strength. It goes out behind the hit.
            event.cancel();
            punchOwed = true;
        }
    }

    private void onAttackPacket(PacketSendEvent event, ServerboundAttackPacket attack) {
        // A hit on yourself is only ever a request to be kicked.
        if (attack.entityId() == mc.player.getId()) {
            return;
        }
        if (stage != Stage.IDLE) {
            return;
        }
        if (mace.isOn() && mc.player.getMainHandItem().is(Items.MACE)) {
            if (canSmash() && smashable(attack.entityId())) {
                smash(event, attack);
            }
            return;
        }
        if (mode.is(Mode.NONE) || skipCrit() || !wantsCrit(attack.entityId())) {
            return;
        }
        switch (mode.getValue()) {
            case PACKET, SUBTLE -> {
                hold(event, attack);
                offset = lift();
                stage = Stage.LIFT;
            }
            case MINI_JUMP, FULL_JUMP -> {
                hold(event, attack);
                stage = Stage.JUMP;
                jump();
            }
            case NONE -> {
            }
        }
    }

    // MaceCombo smashes on a real fall and puts the mace away in the same tick. A rider's
    // own position never reaches the server and neither does one Latency holds.
    private boolean canSmash() {
        return !Modules.maceComboAirborne() && !mc.player.isFallFlying() && !mc.player.isInWater()
            && !mc.player.isInLava() && !mc.player.isPassenger() && !Hop.travelling()
            && !MoveGate.held(false);
    }

    // Only a living target the hit can hurt pays for a smash. A missed smash leaves the
    // server holding the whole fall until it is wiped.
    private boolean smashable(int entityId) {
        Entity target = mc.level.getEntity(entityId);
        if (!(target instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (!skipShields.isOn()) {
            return true;
        }
        boolean untouchable = living instanceof Player player && (player.isCreative() || player.isSpectator());
        return !living.isBlocking() && !untouchable;
    }

    // Plans every lift and drop at once. Without room above the click goes out as it was.
    private void smash(PacketSendEvent event, ServerboundAttackPacket attack) {
        int wanted = totemBypass.isOn() ? hits.getInt() : 1;
        Hop.Plan plan = Hop.plan();
        MaceSmash.Smash smash = MaceSmash.append(plan, mc.player.position(), height.getValue(), wanted,
            heightStep.getValue(), this::strike);
        if (smash.hits() == 0) {
            return;
        }
        if (smash.hits() < wanted && smash.outOfTime()) {
            warning.say("Totem bypass needs " + smash.followTicks() + " ticks but the hurt window lasts ten. Only "
                + smash.hits() + " hits go out.");
        }
        hold(event, attack);
        smashTarget = attack.entityId();
        struck = false;
        stage = Stage.SMASH;
        plan.go(this::smashFinished);
    }

    // The first hit is the one the player swung. Later ones go out fresh whilst the mace
    // is still in hand.
    private void strike(int hit) {
        if (stage != Stage.SMASH || !inGame()) {
            return;
        }
        struck = true;
        if (hit == 0) {
            letOut();
            return;
        }
        if (!mc.player.getMainHandItem().is(Items.MACE)) {
            return;
        }
        followingUp = true;
        try {
            mc.player.connection.send(new ServerboundAttackPacket(smashTarget));
        } finally {
            followingUp = false;
        }
    }

    // A smash cut short still lands the swing as a plain hit. A player who left takes
    // nothing along.
    private void smashFinished(Hop.Result result) {
        if (stage != Stage.SMASH) {
            return;
        }
        if (result == Hop.Result.LEFT) {
            held.discard();
        } else if (!struck) {
            letOut();
        }
        resetStage();
    }

    // True when a hit on the entity from where the player stands would be made critical.
    public boolean makesCritical(Entity target) {
        return !mode.is(Mode.NONE) && wantsCrit(target.getId()) && !skipCrit();
    }

    private boolean wantsCrit(int entityId) {
        Entity target = mc.level.getEntity(entityId);
        if (!(target instanceof LivingEntity)) {
            return false;
        }
        if (!onlyKillAura.isOn()) {
            return true;
        }
        KillAura killAura = Modules.get(KillAura.class);
        return killAura != null && target == killAura.getTarget();
    }

    // A jump cannot start from a ladder or a web. The packet modes need solid ground and
    // positions that go out at once and room overhead for the lift.
    private boolean skipCrit() {
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()) {
            return true;
        }
        if (mode.isAny(Mode.PACKET, Mode.SUBTLE)) {
            return MoveGate.held(false)
                || !mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0, lift(), 0));
        }
        return BlockUtil.inCobweb(mc.player);
    }

    private double lift() {
        return mode.is(Mode.PACKET) ? CRIT_LIFT : SUBTLE_LIFT;
    }

    private void jump() {
        if (mode.is(Mode.FULL_JUMP)) {
            mc.player.jumpFromGround();
        } else {
            Vec3 motion = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(motion.x, MINI_JUMP_SPEED, motion.z);
        }
        lastY = mc.player.getY();
    }

    // A respawn or a change of world or server replaces the player. A hit held for the player
    // it replaced is dropped.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (stage != Stage.IDLE && heldBy != mc.player) {
            clearHeld();
        }
    }

    // The lift and the drop each ride on the one position packet their tick allows. The hit
    // goes out right behind the drop. The game's own packet of that tick comes after it and
    // claims the ground and that would wipe the fall before a hit sent any later.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (stage != Stage.LIFT && stage != Stage.DIP) {
            return;
        }
        if (++waited > GIVE_UP_TICKS) {
            stage = Stage.READY;
            return;
        }
        if (!MoveGate.send(mc.player.getX(), mc.player.getY() + offset, mc.player.getZ(), false)) {
            return;
        }
        waited = 0;
        if (stage == Stage.LIFT) {
            stage = Stage.DIP;
            offset = 0;
        } else {
            release();
        }
    }

    // A jump counts for nothing until the server has seen the player come down. Every
    // position on the way up wipes the fall it holds. The hit goes out behind the first
    // position the game sends from lower than the one before. A jump that never left the
    // ground lets the hit go as it is.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        if (stage != Stage.JUMP) {
            return;
        }
        double y = mc.player.getY();
        boolean grounded = waited > 0 && mc.player.onGround();
        if (y < lastY || grounded || ++waited > JUMP_TICKS) {
            release();
        }
        lastY = y;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (stage == Stage.READY) {
            release();
        }
    }

    // Sends the held hit now that the server sees a fall.
    private void release() {
        resetStage();
        letOut();
    }

    private void clearHeld() {
        resetStage();
        held.discard();
        punchOwed = false;
    }

    private void resetStage() {
        stage = Stage.IDLE;
        heldBy = null;
        offset = 0;
        waited = 0;
    }

    // The client plays the hit out the moment it is held. It must not slow itself for a
    // hit the server is going to see without a sprint.
    private void hold(PacketSendEvent event, ServerboundAttackPacket attack) {
        held.hold(event, attack);
        heldBy = mc.player;
        if (stopSprint.isOn()) {
            SprintPause.willHide();
        }
    }

    // The server refuses a critical hit to anyone it believes is sprinting. The stop goes
    // out with the hit and the start straight behind it.
    private void letOut() {
        if (stopSprint.isOn()) {
            SprintPause.hide();
        }
        held.release();
        if (punchOwed) {
            punchOwed = false;
            mc.player.connection.send(ServerboundPunchPacket.INSTANCE);
        }
    }
}
