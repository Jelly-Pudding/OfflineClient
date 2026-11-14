package com.jellypudding.offlineclient.rig.core;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// The spot of a flat world a blanket run plays on. Before every case the ground and the player
// and the client are put back to how they began and nothing one module did reaches the next.
public final class Stage {

    private static final int REACH = 6;
    private static final int HEADROOM = 6;
    private static final int RESPAWN_TICKS = 100;
    private static final double AT_HOME = 0.05;
    private static final float FACING = 180;
    // The husk stands north of the player and the cow east. A husk does not burn in daylight.
    private static final int MOB_DISTANCE = 3;
    private static final String MOB_TAGS = "{NoAI:1b,PersistenceRequired:1b}";
    private static final List<String> KIT_HOTBAR = List.of("diamond_sword", "diamond_pickaxe", "cobblestone 64",
        "cooked_beef 16", "bow", "ender_pearl 8", "water_bucket", "totem_of_undying", "obsidian 64");
    private static final List<String> KIT_PACK = List.of("arrow 64", "end_crystal 16", "golden_apple 8",
        "diamond_hoe", "wheat_seeds 16", "flint_and_steel", "red_bed", "shield", "diamond_chestplate", "elytra",
        "firework_rocket 16", "experience_bottle 16");

    private final Rig rig;
    private BlockPos home;

    public Stage(Rig rig) {
        this.rig = rig;
        findHome();
    }

    private void findHome() {
        home = rig.ask(mc -> mc.player.blockPosition());
    }

    public BlockPos home() {
        return home;
    }

    public boolean worldStands() {
        return rig.ask(mc -> mc.player != null && mc.level != null);
    }

    // Fails the running case when the player is dead or the world is gone.
    public static void expectAlive(Rig rig) {
        String state = rig.ask(mc -> {
            if (mc.player == null || mc.level == null) {
                return "The world was left";
            }
            return mc.player.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen ? "The player died" : "";
        });
        rig.expect(state.isEmpty(), state);
    }

    // Opens a fresh world after one was lost.
    public void reopen(WorldCreationUiState.SelectedGameMode mode) {
        try {
            rig.closeWorld();
        } catch (RuntimeException | AssertionError lost) {
            rig.forgetWorld();
        }
        rig.openWorld(mode);
        findHome();
    }

    public void reset(boolean withKit) {
        if (!worldStands()) {
            return;
        }
        revive();
        rig.act(mc -> mc.gui.setScreen(null));
        rig.client().restoreDefaults();
        World world = rig.world();
        BlockPos low = home.offset(-REACH, -1, -REACH);
        BlockPos high = home.offset(REACH, -1, REACH);
        world.refill(low.below(3), high.below(3), "bedrock");
        world.refill(low.below(2), high.below(1), "dirt");
        world.refill(low, high, "grass_block");
        world.refill(low.above(), high.above(HEADROOM), "air");
        world.attempt("kill @e[type=!player]");
        world.attempt("clear @p");
        world.attempt("effect clear @p");
        if (withKit) {
            giveKit(world);
        }
        boolean away = rig.ask(mc -> mc.player.position().distanceTo(Vec3.atBottomCenterOf(home)) > AT_HOME
            || mc.player.getYRot() != FACING || mc.player.getXRot() != 0);
        if (away) {
            world.teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, FACING, 0);
        } else {
            rig.ticks(1);
        }
    }

    private void giveKit(World world) {
        for (int slot = 0; slot < KIT_HOTBAR.size(); slot++) {
            world.command("item replace entity @p hotbar." + slot + " with " + KIT_HOTBAR.get(slot));
        }
        for (int slot = 0; slot < KIT_PACK.size(); slot++) {
            world.command("item replace entity @p inventory." + slot + " with " + KIT_PACK.get(slot));
        }
        world.command("effect give @p instant_health 1 20 true");
        world.command("effect give @p saturation 1 20 true");
        world.command("summon husk " + (home.getX() + 0.5) + " " + home.getY() + " "
            + (home.getZ() + 0.5 - MOB_DISTANCE) + " " + MOB_TAGS);
        world.command("summon cow " + (home.getX() + 0.5 + MOB_DISTANCE) + " " + home.getY() + " "
            + (home.getZ() + 0.5) + " " + MOB_TAGS);
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(0));
    }

    // A dead player is brought back before anything else happens.
    private void revive() {
        boolean dead = rig.ask(mc -> mc.player.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen);
        if (!dead) {
            return;
        }
        rig.act(mc -> mc.player.respawn());
        rig.until("the player to come back to life", RESPAWN_TICKS,
            mc -> mc.player != null && !mc.player.isDeadOrDying());
        rig.act(mc -> mc.gui.setScreen(null));
    }
}
