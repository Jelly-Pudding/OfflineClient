package com.jellypudding.offlineclient.rig.core;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// The spot of a flat world a blanket run plays on. Before every case the ground and the player
// and the client are put back to how they began and nothing one module did reaches the next.
public final class Stage {

    private static final int REACH = 6;
    // A case may build this high and no higher. What stands above it outlives the case.
    private static final int HEADROOM = 14;
    private static final int RESPAWN_TICKS = 100;
    private static final int LAND_TICKS = 200;
    private static final int REOPEN_TRIES = 30;
    private static final int REOPEN_WAIT = 20;
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

    public static void expectAlive(Rig rig) {
        boolean left = rig.ask(mc -> mc.player == null || mc.level == null);
        rig.expect(!left, "The world was left");
        rig.expect(!dead(rig), "The player died");
    }

    public static boolean dead(Rig rig) {
        return rig.ask(mc -> mc.player != null && mc.level != null
            && (mc.player.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen));
    }

    public static void revive(Rig rig) {
        if (!dead(rig)) {
            return;
        }
        rig.act(mc -> mc.player.respawn());
        rig.until("the player to come back to life", RESPAWN_TICKS,
            mc -> mc.player != null && !mc.player.isDeadOrDying());
        rig.act(mc -> mc.gui.setScreen(null));
    }

    // Opens a fresh world after one was lost. The server of the lost one may still be on its
    // way down and the game makes no new world before it has gone.
    public void reopen(WorldCreationUiState.SelectedGameMode mode) {
        try {
            rig.closeWorld();
        } catch (RuntimeException | AssertionError lost) {
            rig.forgetWorld();
        }
        rig.act(mc -> mc.gui.setScreen(new TitleScreen()));
        for (int tries = 0; ; tries++) {
            try {
                rig.openWorld(mode);
                break;
            } catch (IllegalStateException stillStopping) {
                if (tries >= REOPEN_TRIES) {
                    throw stillStopping;
                }
                rig.ticks(REOPEN_WAIT);
            }
        }
        findHome();
    }

    // The mobs drop out of the world before they are killed. A dying mob would lie on the stage
    // for a second and take a click meant for what stands behind it. What they drop goes too.
    public void reset(boolean withKit) {
        if (!worldStands()) {
            return;
        }
        revive(rig);
        // Smoke and sparks of the case before would drift through the pictures of this one.
        rig.act(mc -> {
            mc.gui.setScreen(null);
            mc.particleEngine.clearParticles();
        });
        rig.client().restoreDefaults();
        World world = rig.world();
        holdLand(world);
        BlockPos low = home.offset(-REACH, -1, -REACH);
        BlockPos high = home.offset(REACH, -1, REACH);
        String player = world.player();
        world.quietly(List.of(fill(low.below(3), high.below(3), "bedrock"), fill(low.below(2), high.below(1), "dirt"),
            fill(low, high, "grass_block"), fill(low.above(), high.above(HEADROOM), "air"),
            "execute as @e[type=!player] at @s run tp @s ~ ~-300 ~", "kill @e[type=!player]", "kill @e[type=item]",
            "clear " + player, "effect clear " + player,
            "effect give " + player + " instant_health 1 20 true", "effect give " + player + " saturation 1 20 true"));
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

    // The server lets go of land nobody stands near and of all that is on it. That happens a
    // second after a death and when a case ends far from home. A tidy would miss what was left
    // there and it would come back with the player in the middle of the next case.
    private void holdLand(World world) {
        StringBuilder loaded = new StringBuilder("execute");
        for (int x : List.of(-REACH, REACH)) {
            for (int z : List.of(-REACH, REACH)) {
                loaded.append(" if loaded ").append(World.at(home.offset(x, 0, z)));
            }
        }
        String held = loaded.toString();
        if (world.attempt(held)) {
            return;
        }
        world.teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, FACING, 0);
        for (int waited = 0; !world.attempt(held); waited++) {
            if (waited >= LAND_TICKS) {
                throw new RigFailure("The server never loaded the land round the stage");
            }
            rig.ticks(1);
        }
    }

    private static String fill(BlockPos from, BlockPos to, String block) {
        return "fill " + World.at(from) + " " + World.at(to) + " " + block;
    }

    private void giveKit(World world) {
        String slots = "item replace entity " + world.player() + " ";
        List<String> kit = new ArrayList<>();
        for (int slot = 0; slot < KIT_HOTBAR.size(); slot++) {
            kit.add(slots + "hotbar." + slot + " with " + KIT_HOTBAR.get(slot));
        }
        for (int slot = 0; slot < KIT_PACK.size(); slot++) {
            kit.add(slots + "inventory." + slot + " with " + KIT_PACK.get(slot));
        }
        kit.add("summon husk " + (home.getX() + 0.5) + " " + home.getY() + " " + (home.getZ() + 0.5 - MOB_DISTANCE)
            + " " + MOB_TAGS);
        kit.add("summon cow " + (home.getX() + 0.5 + MOB_DISTANCE) + " " + home.getY() + " " + (home.getZ() + 0.5)
            + " " + MOB_TAGS);
        world.quietly(kit);
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(0));
    }
}
