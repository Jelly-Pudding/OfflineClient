package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.LeftClickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.ListMode;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BreakTime;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class FastBreak extends Module {

    public enum Mode { NORMAL, HASTE, DAMAGE }

    // A block whose tick progress passes this is gone on the second server tick.
    private static final float INSTAMINE_PROGRESS = 0.5f;

    // The highest progress a tick may predict before the client breaks the
    // block on its own and drifts away from the server.
    private static final float SAFE_PROGRESS = 0.9f;

    // Vanilla waits this many ticks between one broken block and the next.
    private static final int VANILLA_DELAY = 5;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the break is sped up.", Mode.NORMAL)
        .describe(Mode.NORMAL, "Multiplies the break speed the client predicts.")
        .describe(Mode.HASTE, "Gives you a Haste effect the server never sees.")
        .describe(Mode.DAMAGE, "Reports the block finished as soon as the server would accept it.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "Speeds up the break the client predicts. The server keeps its own timer.",
        1, 1, 10, 0.1, "x").under(mode, Mode.NORMAL);
    private final NumberSetting hasteLevel = new NumberSetting("Haste level",
        "Level of the Haste effect. Above two is not recommended.", 2, 1, 5, 1, "")
        .under(mode, Mode.HASTE);
    private final BoolSetting instamine = new BoolSetting("Instamine",
        "Removes a block on the first click when the server will finish it within two ticks.",
        true).under(mode, Mode.DAMAGE);
    private final BoolSetting grimBypass = new BoolSetting("Grim bypass",
        "Sends an extra abort packet after every finish to slip past the Grim fast break check.",
        false).under(mode, Mode.DAMAGE);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks the speed up applies to. Click to pick them.", BuiltInRegistries.BLOCK, List.of())
        .under(mode, Mode.NORMAL, Mode.DAMAGE);
    private final EnumSetting<ListMode> listMode = ListMode.setting("List mode", ListMode.BLACKLIST,
        "Only the listed blocks are sped up.", "Every block except the listed ones is sped up.")
        .under(mode, Mode.NORMAL, Mode.DAMAGE);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between breaking blocks. Vanilla waits " + VANILLA_DELAY + ".", 0, 0, VANILLA_DELAY, 1, " ticks");
    private final NumberSetting chance = new NumberSetting("Chance",
        "Share of blocks that get sped up. Below a hundred the timing looks less mechanical.",
        100, 1, 100, 1, "%").max(100);
    private final BoolSetting safeFirstClick = new BoolSetting("Safe first click",
        "A single click on a block that breaks instantly does nothing. Hold to break it.", false);
    private final BoolSetting airPenalty = new BoolSetting("No air penalty",
        "Lets you mine at full speed in the air. Vanilla mines five times slower off the ground.", true);

    private int guardTicks;

    private boolean freshClick;

    private BlockPos rolledFor;
    private boolean rolled;

    public FastBreak() {
        super("FastBreak", "Breaks blocks faster and without the vanilla wait.", Category.PLAYER);
        addSettings(mode, speed, hasteLevel, instamine, grimBypass, blocks, listMode, delay,
            chance, safeFirstClick, airPenalty);
        searchTags("fast break", "speed mine", "instant mine", "nuker speed", "haste",
            "break delay", "instamine");
    }

    @Override
    public String getSuffix() {
        return switch (mode.getValue()) {
            case NORMAL -> speed.getValue() > 1 ? speed.getValueString() : null;
            case HASTE -> "haste " + hasteLevel.getInt();
            case DAMAGE -> "damage";
        };
    }

    @Override
    protected void onEnable() {
        guardTicks = 0;
        freshClick = false;
    }

    @Override
    protected void onDisable() {
        removeHaste();
    }

    // Called from the player mixin for every break speed the client predicts.
    public float adjustSpeed(BlockState state, float vanilla) {
        if (!isEnabled() || !inGame()) {
            return vanilla;
        }
        float adjusted = vanilla;
        // Vanilla divides purely on being off the ground. Water has its own factor.
        if (claimsGround(mc.player)) {
            adjusted *= BreakTime.AIR_PENALTY;
        }
        if (!mode.is(Mode.NORMAL) || !allows(state.getBlock()) || !lucky(aimedBlock())) {
            return adjusted;
        }
        return capBelowInstant(state, adjusted, adjusted * speed.getFloat());
    }

    private static BlockPos aimedBlock() {
        BlockHitResult hit = BlockUtil.aimedBlock();
        return hit == null ? null : hit.getBlockPos();
    }

    // One roll per block. The same block reads the same way for as long as it is aimed at.
    private boolean lucky(BlockPos pos) {
        if (chance.getInt() >= 100 || pos == null) {
            return true;
        }
        if (!pos.equals(rolledFor)) {
            rolledFor = pos.immutable();
            rolled = ThreadLocalRandom.current().nextInt(100) < chance.getInt();
        }
        return rolled;
    }

    // A multiplier that turns a slow block into an instant one on the client would
    // have it vanish and come back. The speed stops just short of that instead.
    private float capBelowInstant(BlockState state, float before, float after) {
        if (!(mc.hitResult instanceof BlockHitResult hit)) {
            return after;
        }
        BlockPos pos = hit.getBlockPos();
        if (mc.level.getBlockState(pos) != state) {
            return after;
        }
        float hardness = state.getDestroySpeed(mc.level, pos);
        if (hardness <= 0) {
            return after;
        }
        float divisor = BreakTime.breakDivisor(hardness, mc.player.hasCorrectToolForDrops(state));
        boolean instantBefore = before / divisor >= 1;
        boolean instantAfter = after / divisor >= 1;
        return instantBefore == instantAfter ? after : SAFE_PROGRESS * divisor;
    }

    // Called from the game mode mixin with the progress one tick adds whilst mining.
    public float adjustProgress(BlockState state, float progress, float delta) {
        if (!isEnabled() || !mode.is(Mode.DAMAGE) || !allows(state.getBlock()) || !lucky(aimedBlock())) {
            return delta;
        }
        return progress + delta >= BreakTime.SERVER_ACCEPTS ? 1 : delta;
    }

    public boolean instamines(BlockState state, BlockPos pos) {
        if (!isEnabled() || !inGame() || !mode.is(Mode.DAMAGE) || !instamine.isOn()
            || mc.player.getAbilities().instabuild || !allows(state.getBlock()) || !lucky(pos)) {
            return false;
        }
        float delta = state.getDestroyProgress(mc.player, mc.level, pos);
        return delta > INSTAMINE_PROGRESS && delta < 1;
    }

    public boolean guardsClick(BlockState state, BlockPos pos) {
        if (!isEnabled() || !inGame() || !safeFirstClick.isOn() || !freshClick) {
            return false;
        }
        freshClick = false;
        if (mc.player.getAbilities().instabuild
            || state.getDestroyProgress(mc.player, mc.level, pos) < 1) {
            return false;
        }
        guardTicks = VANILLA_DELAY;
        mc.gameMode.destroyDelay = VANILLA_DELAY;
        return true;
    }

    private boolean allows(Block block) {
        return listMode.getValue().admits(blocks.contains(block));
    }

    @Subscribe
    private void onLeftClick(LeftClickEvent event) {
        freshClick = true;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.gameMode == null) {
            return;
        }
        if (mode.is(Mode.HASTE)) {
            addHaste();
        } else {
            removeHaste();
        }
        // A press is handled before this tick. An unused one is stale.
        freshClick = false;
        if (guardTicks > 0) {
            guardTicks--;
            return;
        }
        mc.gameMode.destroyDelay = Math.min(mc.gameMode.destroyDelay, delay.getInt());
    }

    // The effect is hidden. The status bar and the server side both stay as they were.
    private void addHaste() {
        int amplifier = hasteLevel.getInt() - 1;
        MobEffectInstance haste = mc.player.getEffect(MobEffects.HASTE);
        if (haste == null || haste.getAmplifier() < amplifier) {
            mc.player.addEffect(new MobEffectInstance(MobEffects.HASTE,
                MobEffectInstance.INFINITE_DURATION, amplifier, false, false, false));
        }
    }

    // A real Haste effect shows its icon and is left alone.
    private void removeHaste() {
        if (!inGame()) {
            return;
        }
        MobEffectInstance haste = mc.player.getEffect(MobEffects.HASTE);
        if (haste != null && !haste.showIcon()) {
            mc.player.removeEffect(MobEffects.HASTE);
        }
    }

    // The server lifts its airborne penalty only whilst it is told you stand on the ground.
    // Where no claim can go the client keeps the penalty as well and the two stay agreed.
    private boolean claimsGround(LocalPlayer player) {
        return airPenalty.isOn() && !player.onGround() && PacketUtil.mayClaimGround(player);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        // The packet thread can drop the player and the game mode mid handler.
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            claimGround(event, packet, player);
            return;
        }
        // The rewrite below only runs whilst the game mines a block. A block that breaks on
        // the first hit and a break sent by packet go out without it.
        if (PacketUtil.judgesBreak(event.getPacket()) && claimsGround(player)) {
            PacketUtil.claimGround(player);
        }
        if (event.getPacket() instanceof ServerboundPlayerActionPacket packet) {
            abortAbove(packet, player);
        }
    }

    private void claimGround(PacketSendEvent event, ServerboundMovePlayerPacket packet, LocalPlayer player) {
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (packet.isOnGround() || gameMode == null || !gameMode.isDestroying()
            || !airPenalty.isOn() || !PacketUtil.mayClaimGround(player)) {
            return;
        }
        event.setPacket(PacketUtil.withOnGround(packet, player, true));
    }

    // Grim skips its fast break check when an abort for another block follows a finish.
    private void abortAbove(ServerboundPlayerActionPacket packet, LocalPlayer player) {
        if (!mode.is(Mode.DAMAGE) || !grimBypass.isOn()
            || packet.getAction() != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
            return;
        }
        player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
            packet.getPos().above(), packet.getDirection()));
    }
}
