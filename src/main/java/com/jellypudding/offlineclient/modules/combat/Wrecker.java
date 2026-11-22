package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSentEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MoveGate;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

// A boat or minecart adds up ten times every hit it takes and breaks once the sum passes forty.
// Nothing makes the server wait between two hits. The hits a break still needs follow the
// first one straight on the wire and land in the same tick.
public final class Wrecker extends Module {

    private static final float BREAKS_PAST = 40;
    private static final float PER_POINT = 10;

    // A hit after another in the same tick lands with the swing timer just reset. The game reads
    // the timer half a tick on.
    private static final float RESET_CHARGE = 0.5f;

    // Ticks the server's swing timer may run behind ours. Its count starts when the last hit
    // arrived and a slow trip shortens it. Sharpness and a critical only add damage and are
    // left out.
    private static final int TIMER_LAG = 3;

    // A fist takes sixteen. Twice that means the hits barely scratch it.
    private static final int MOST_HITS = 32;

    // A TNT minecart broken whilst it rolls faster than this is lit instead.
    private static final double LIT_SPEED_SQR = 0.01;

    private final ChatWarning warning = new ChatWarning();

    // Set whilst the extra hits go out. They pass through here again.
    private boolean sending;

    public Wrecker() {
        super("Wrecker", "Breaks a boat or minecart with one hit.", Category.COMBAT);
        searchTags("vehicle one hit", "one hit", "boat", "minecart", "break vehicle", "raft");
    }

    @Override
    protected void onEnable() {
        warning.clear();
    }

    @Subscribe
    private void onPacketSent(PacketSentEvent event) {
        if (sending || !(event.getPacket() instanceof ServerboundAttackPacket hit) || !inGame()
            || !mc.isSameThread() || !(mc.level.getEntity(hit.entityId()) instanceof VehicleEntity vehicle)
            || !vehicle.isAlive() || mc.player.getAbilities().instabuild) {
            return;
        }
        if (vehicle instanceof MinecartTNT && (rolls(vehicle) || pushes(mc.player))) {
            warning.say("A TNT minecart gets one hit whilst it rolls or your hit would push it. Breaking it as it"
                + " moves would light it.");
            return;
        }
        if (!ItemUtil.landsPlainHit(mc.player.getMainHandItem())) {
            warning.say("A spear lands no plain hit on a vehicle.");
            return;
        }
        int extra = hitsToBreak() - 1;
        if (extra <= 0) {
            return;
        }
        if (extra >= MOST_HITS) {
            warning.say("Your hits are too weak to break a vehicle at once.");
            return;
        }
        if (extra > MoveGate.spare()) {
            warning.say("The packet budget is spent. The hit went out once.");
            return;
        }
        repeat(vehicle, extra);
    }

    private void repeat(Entity vehicle, int times) {
        sending = true;
        try {
            for (int i = 0; i < times; i++) {
                mc.player.connection.send(new ServerboundAttackPacket(vehicle.getId()));
            }
        } finally {
            sending = false;
        }
    }

    // The server judges by the speed it holds. Either the speed sent or the last tick's move counts here.
    private static boolean rolls(VehicleEntity vehicle) {
        return Math.max(vehicle.getDeltaMovement().horizontalDistanceSqr(),
            EntityUtil.velocityOf(vehicle).horizontalDistanceSqr()) >= LIT_SPEED_SQR;
    }

    // A sprinting hit or a weapon with knockback pushes what it lands on before the next hit
    // arrives. A falling mace can blow it along with Wind Burst.
    private static boolean pushes(Player player) {
        ItemStack held = player.getMainHandItem();
        return player.isSprinting() || withHeld(player, Attributes.ATTACK_KNOCKBACK) > 0
            || EnchantmentHelper.has(held, EnchantmentEffectComponents.KNOCKBACK)
            || held.getItem() instanceof MaceItem && !player.onGround();
    }

    // The packet is out but the swing timer has not been reset yet. It still holds the first charge.
    private static int hitsToBreak() {
        return hitsFor(DamageUtil.hitDamage(mc.player, mc.player.getMainHandItem()),
            mc.player.getCurrentItemAttackStrengthDelay(), mc.player.getAttackStrengthScale(RESET_CHARGE - TIMER_LAG));
    }

    // The first hit carries the charge the swing had and every later one the charge left right
    // after a hit.
    private static int hitsFor(float damage, float delay, float firstCharge) {
        float first = damage * strength(firstCharge);
        float later = damage * strength(Math.clamp(RESET_CHARGE / delay, 0, 1));
        if (later <= 0) {
            return Integer.MAX_VALUE;
        }
        float left = BREAKS_PAST / PER_POINT - first;
        return left < 0 ? 1 : 2 + (int) Math.floor(left / later);
    }

    private static float strength(float charge) {
        return 0.2f + charge * charge * 0.8f;
    }

    // The game keeps knockback on the server alone. It is the player's own plus what the held
    // item adds.
    private static double withHeld(Player player, Holder<Attribute> attribute) {
        return player.getAttributeBaseValue(attribute)
            + ItemUtil.attributeValue(player.getMainHandItem(), attribute, EquipmentSlot.MAINHAND);
    }
}
