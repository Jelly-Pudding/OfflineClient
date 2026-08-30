package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.Swap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class AutoTotem extends Module {

    // Gliding into a wall kills outright. A totem is always worth holding.
    private static final double ELYTRA_TRIGGER_SPEED = 0.5;

    private final NumberSetting health = new NumberSetting("Health",
        "Puts a totem in your offhand once you drop to this many hearts. Zero keeps one there at all times.",
        0, 0, 10, 0.5, " hearts");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait before equipping the next totem.", 0, 0, 20, 1, " ticks");
    private final BoolSetting explosions = new BoolSetting("Explosions",
        "Equip early when a nearby crystal or bed or anchor could take you out.", true);
    private final NumberSetting blastRange = new NumberSetting("Blast range",
        "How far away a charge is counted as a threat.", 8, 2, 16, 0.5, " blocks")
        .under(explosions);
    private final BoolSetting fall = new BoolSetting("Fall",
        "Equip early when the fall you are in would kill you.", true);
    private final BoolSetting elytra = new BoolSetting("Elytra",
        "Always hold a totem whilst gliding at speed.", true);

    private final InventoryUtil.StrandedStack cursor = new InventoryUtil.StrandedStack();
    private int totems;
    private int timer;
    private boolean hadTotem;

    public AutoTotem() {
        super("AutoTotem", "Keeps a totem of undying in your offhand.", Category.COMBAT);
        addSettings(health, delay, explosions, blastRange, fall, elytra);
        searchTags("totem", "pop");
    }

    @Override
    public String getSuffix() {
        return totems + " left";
    }

    @Override
    protected void onEnable() {
        timer = 0;
        hadTotem = false;
    }

    @Override
    protected void onDisable() {
        if (inGame()) {
            cursor.giveBack();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (!cursor.recover()) {
            return;
        }
        totems = countTotems();

        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            hadTotem = true;
            return;
        }
        if (hadTotem) {
            timer = delay.getInt();
            hadTotem = false;
        }
        int totemSlot = findTotem();
        if (totemSlot == -1) {
            return;
        }

        float minHealth = health.getFloat();
        if (minHealth > 0 && !EntityUtil.healthAtOrBelow(minHealth) && !threatened()) {
            return;
        }

        if (!InventoryUtil.canClick()) {
            return;
        }
        if (!InventoryUtil.carried().isEmpty()) {
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        if (InventoryUtil.swap(totemSlot, InventoryUtil.OFFHAND_SLOT) == Swap.STRANDED) {
            // The item the totem replaced had nowhere to go.
            cursor.hold(totemSlot);
        }
    }

    /**
     * True when something already in the world would kill before the health
     * threshold could react.
     */
    private boolean threatened() {
        if (elytra.isOn() && mc.player.isFallFlying()
            && mc.player.getDeltaMovement().length() > ELYTRA_TRIGGER_SPEED) {
            return true;
        }
        float headroom = EntityUtil.totalHealth(mc.player);
        if (fall.isOn() && fallDamage() >= headroom) {
            return true;
        }
        return explosions.isOn() && blastThreat() >= headroom;
    }

    // Vanilla takes half a heart for every block past the first three.
    private float fallDamage() {
        double drop = mc.player.fallDistance - 3;
        if (drop <= 0 || mc.player.isFallFlying()) {
            return 0;
        }
        MobEffectInstance jump = mc.player.getEffect(MobEffects.JUMP_BOOST);
        if (jump != null) {
            drop -= jump.getAmplifier() + 1;
        }
        return (float) Math.max(0, drop);
    }

    // The worst single charge in range.
    private float blastThreat() {
        double range = blastRange.getValue();
        float worst = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal) || entity.distanceTo(mc.player) > range) {
                continue;
            }
            worst = Math.max(worst,
                ExplosionUtil.crystalDamage(mc.player, entity.position()));
        }
        for (BlockPos pos : BlockUtil.positionsWithin(range)) {
            BlockState state = BlockUtil.state(pos);
            if (!isCharge(state)) {
                continue;
            }
            worst = Math.max(worst, ExplosionUtil.blastDamage(mc.player,
                Vec3.atCenterOf(pos), ExplosionUtil.RESPAWN_BLOCK_POWER, Vec3.ZERO, ExplosionUtil.halvesOf(pos)));
        }
        return worst;
    }

    private boolean isCharge(BlockState state) {
        if (state.getBlock() instanceof BedBlock) {
            return ExplosionUtil.bedsExplodeHere();
        }
        return state.getBlock() instanceof RespawnAnchorBlock && ExplosionUtil.anchorsExplodeHere();
    }

    // Every totem the player owns. The one already equipped counts.
    private int countTotems() {
        int offhand = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            ? mc.player.getOffhandItem().getCount() : 0;
        return offhand + InventoryUtil.count(Items.TOTEM_OF_UNDYING, InventoryUtil.WHOLE_INVENTORY);
    }

    // Network slot of the first totem in the inventory. Minus one when there is none.
    private int findTotem() {
        int slot = InventoryUtil.findSlot(Items.TOTEM_OF_UNDYING, InventoryUtil.WHOLE_INVENTORY);
        return slot == -1 ? -1 : InventoryUtil.networkSlot(slot);
    }
}
