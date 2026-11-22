package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.Landing;
import com.jellypudding.offlineclient.util.Totems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.OptionalDouble;

// Makes the server think the player fell where they stand. The player goes up by packet and
// straight back down and the landing claims the ground. The server banks a fall only from the
// packets and charges the whole drop since the last rise on that landing.
public final class FallCommand extends Command {

    // The server deals the whole damage a fall comes to. The fall is sized half a point past
    // it and the server's own rounding can never tip it over or under.
    private static final double MIDDLE = 0.5;

    // A fresh hit only counts in full once the cooldown of the last one is down to this.
    private static final int FULL_HIT_COOLDOWN = 10;

    // A death is sized this much health past what is left. The server works the protection
    // out in sums of its own that may come a hair lower.
    private static final float DEATH_MARGIN = 1;

    private enum Aim { HEARTS, DEATH }

    private final Aim aim;

    private FallCommand(String name, String description, String usage, Aim aim) {
        super(name, description, usage);
        this.aim = aim;
    }

    public static FallCommand hurt() {
        return new FallCommand("hurt", "Makes the server think you fell where you stand. It costs you that many"
            + " hearts.", "hurt <hearts>", Aim.HEARTS);
    }

    public static FallCommand die() {
        return new FallCommand("die", "Makes the server think you fell where you stand far enough to die.", "die",
            Aim.DEATH);
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || args.length != (aim == Aim.HEARTS ? 1 : 0)) {
            usage();
            return;
        }
        float health;
        if (aim == Aim.HEARTS) {
            OptionalDouble hearts = number(args[0]);
            if (hearts.isEmpty()) {
                return;
            }
            if (hearts.getAsDouble() <= 0) {
                ChatUtil.error("Give a number of hearts above nought.");
                return;
            }
            health = (float) Math.min(hearts.getAsDouble() * EntityUtil.HEART, Float.MAX_VALUE);
        } else if (Totems.inHand(player)) {
            ChatUtil.error("A totem in your hand would keep you alive.");
            return;
        } else {
            health = EntityUtil.totalHealth(player) + DEATH_MARGIN;
        }
        String problem = problem(player);
        if (problem != null) {
            ChatUtil.error(problem);
            return;
        }
        DamageSource fall = player.level().damageSources().fall();
        float perPoint = DamageUtil.reduce(1, player, fall);
        double safe = player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
        double scale = player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER);
        if (perPoint <= 0 || scale <= 0) {
            ChatUtil.error("No fall can hurt you.");
            return;
        }
        double most = Math.floor((Hop.MAX_TRIP - safe) * scale - MIDDLE);
        if (health / perPoint > most) {
            ChatUtil.error("That needs a fall of more than " + Hop.MAX_TRIP + " blocks.");
            return;
        }
        int damage = damageFor(player, fall, health, perPoint, (int) most);
        double height = heightFor(damage, safe, scale);
        if (height <= 0) {
            ChatUtil.error("Even the smallest fall costs you more than that.");
            return;
        }
        float dealt = DamageUtil.reduce(damage, player, fall);
        // A totem in hand lets a fatal fall through to test it.
        if (aim == Aim.HEARTS && dealt >= EntityUtil.totalHealth(player) && !Totems.inHand(player)) {
            ChatUtil.error("That would kill you. Use " + OfflineClient.INSTANCE.getCommandManager().getPrefix()
                + "die for that.");
            return;
        }
        Vec3 ground = player.position();
        Vec3 top = ground.add(0, height, 0);
        if (!Hop.safeAt(player, top)) {
            ChatUtil.error("There is no room " + blocks(height) + " blocks above you.");
            return;
        }
        // The server applies what the player passes through on the way. Lava sets them alight
        // and water clears the fall.
        if (player.level().containsAnyLiquid(player.getBoundingBox().expandTowards(0, height, 0))) {
            ChatUtil.error("There is water or lava within " + blocks(height) + " blocks above you.");
            return;
        }
        Hop.plan().to(top).landOn(ground).go(Hop.telling("§7Took a §b" + blocks(height) + "§7 block fall worth §b"
            + EntityUtil.hearts(dealt) + "§7."));
    }

    private static String problem(LocalPlayer player) {
        if (player.isPassenger()) {
            return "Get off what you are riding first.";
        }
        if (!player.onGround()) {
            return "Stand on the ground first.";
        }
        if (player.isInLiquid()) {
            return "Get out of the water or lava first.";
        }
        if (player.getAbilities().mayfly) {
            return "You take no fall damage whilst you can fly.";
        }
        if (player.damageCooldownTime > FULL_HIT_COOLDOWN) {
            return "You were hurt too recently for all of it to count. Try again in a moment.";
        }
        if (!Landing.on(landing(player)).plain()) {
            return "The block under you changes what a fall costs. Step off it first.";
        }
        return null;
    }

    // The server looks for the landing block a fifth of a block under the feet.
    @SuppressWarnings("deprecation")
    private static BlockState landing(LocalPlayer player) {
        return player.level().getBlockState(player.getOnPosLegacy());
    }

    // The server floors the fall past the safe distance times the scale.
    static double heightFor(int damage, double safe, double scale) {
        return safe + (damage + MIDDLE) / scale;
    }

    // The protection only scales a hit and the guess from one point lands within a step.
    private static int damageFor(LocalPlayer player, DamageSource fall, float health, float perPoint, int most) {
        int damage = Math.clamp((long) Math.ceil(health / perPoint), 1, most);
        while (damage > 1 && DamageUtil.reduce(damage - 1, player, fall) >= health) {
            damage--;
        }
        while (damage < most && DamageUtil.reduce(damage, player, fall) < health) {
            damage++;
        }
        return damage;
    }

    private static String blocks(double height) {
        return String.format(Locale.ROOT, "%.1f", height);
    }
}
