package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.Swap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class AutoTotem extends Module {

    // Gliding into a wall kills outright so a totem is always worth holding.
    private static final double ELYTRA_TRIGGER_SPEED = 0.5;

    private final NumberSetting health = new NumberSetting("Health",
        "Only equip a totem at or below this many hearts. Zero means always.", 0, 0, 10, 0.5, " hearts");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait before equipping the next totem.", 0, 0, 20, 1, " ticks");
    private final BoolSetting explosions = new BoolSetting("Explosions",
        "Equip early when a nearby crystal or bed or anchor could take you out.", true);
    private final NumberSetting blastRange = new NumberSetting("Blast range",
        "How far away a charge is counted as a threat.", 8, 2, 16, 0.5, " blocks")
        .visibleWhen(explosions::isOn);
    private final BoolSetting fall = new BoolSetting("Fall",
        "Equip early when the fall you are in would kill you.", true);
    private final BoolSetting elytra = new BoolSetting("Elytra",
        "Always hold a totem whilst gliding at speed.", true);

    private int returnSlot = -1;
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
        returnSlot = -1;
        timer = 0;
        hadTotem = false;
    }

    @Override
    protected void onDisable() {
        if (inGame() && returnSlot != -1 && InventoryUtil.canClick()
            && !InventoryUtil.carried().isEmpty()) {
            InventoryUtil.click(returnSlot);
        }
        returnSlot = -1;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        boolean canClick = InventoryUtil.canClick();

        // A refused click can leave an item on the cursor.
        if (returnSlot != -1) {
            if (canClick && !InventoryUtil.carried().isEmpty()) {
                InventoryUtil.click(returnSlot);
            }
            if (!InventoryUtil.carried().isEmpty()) {
                return;
            }
            returnSlot = -1;
        }

        int totemSlot = findTotem();

        if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            hadTotem = true;
            return;
        }
        if (hadTotem) {
            timer = delay.getInt();
            hadTotem = false;
        }
        if (totemSlot == -1) {
            return;
        }

        float minHealth = health.getFloat();
        if (minHealth > 0 && mc.player.getHealth() > minHealth * 2f && !threatened()) {
            return;
        }

        if (!canClick) {
            return;
        }
        // Anything already on the cursor belongs to the player.
        if (!InventoryUtil.carried().isEmpty()) {
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        if (InventoryUtil.swap(totemSlot, InventoryUtil.OFFHAND_SLOT) == Swap.STRANDED) {
            // The item the totem replaced had nowhere to go.
            returnSlot = totemSlot;
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
        float headroom = ExplosionUtil.totalHealth(mc.player);
        if (fall.isOn() && fallDamage() >= headroom) {
            return true;
        }
        return explosions.isOn() && blastThreat() >= headroom;
    }

    // Vanilla takes one heart for every block past the first three.
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

    // The worst single charge in range rather than the sum of all of them.
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
                Vec3.atCenterOf(pos), ExplosionUtil.RESPAWN_BLOCK_POWER));
        }
        return worst;
    }

    // A bed only goes off outside the overworld and an anchor only outside the nether.
    private boolean isCharge(BlockState state) {
        if (state.getBlock() instanceof BedBlock) {
            return mc.level.dimension() != Level.OVERWORLD;
        }
        return state.getBlock() instanceof RespawnAnchorBlock
            && mc.level.dimension() != Level.NETHER;
    }

    private int findTotem() {
        // The one already equipped counts toward the total on the HUD.
        totems = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
            ? mc.player.getOffhandItem().getCount() : 0;
        int found = -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                totems += mc.player.getInventory().getItem(i).getCount();
                if (found == -1) {
                    found = InventoryUtil.networkSlot(i);
                }
            }
        }
        return found;
    }
}
