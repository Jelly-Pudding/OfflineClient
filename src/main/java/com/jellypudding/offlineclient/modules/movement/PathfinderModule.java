package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

// The path package reads these settings. A walk also follows the movement modules without being
// told. Jesus lets it cross water and lava and NoFall lets it take any drop and Step lets it up a
// block without a jump.
public final class PathfinderModule extends Module {

    private static final float ROUTE_HUE = 190;

    private final BoolSetting pauseInFight = new BoolSetting("Pause in a fight",
        "A walk stands still whilst a combat module has a target and carries on afterwards. FightBot and"
            + " Protect walk to their fights and whilst either is on no walk stops.", false);
    private final BoolSetting showRoute = new BoolSetting("Show route",
        "Draws the way a walk is taking as a line through the world.", false);
    private final ColorSetting routeColor = new ColorSetting("Route colour",
        "Colour of that line and of the one the path command draws.", ROUTE_HUE, false);
    private final BoolSetting bridge = new BoolSetting("Bridge",
        "A walk may lay blocks from your hotbar across a gap.", false);
    private final BoolSetting pillar = new BoolSetting("Pillar",
        "A walk may jump and stack blocks from your hotbar under you to climb.", false);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks Bridge and Pillar may lay. Nothing else leaves your hotbar.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK,
            Blocks.DIRT, Blocks.STONE, Blocks.DEEPSLATE, Blocks.OBSIDIAN))
        .visibleWhen(() -> bridge.isOn() || pillar.isOn());

    public PathfinderModule() {
        super("Pathfinder", "Settings for every walk the client makes for you such as goto and Follow.",
            Category.MOVEMENT);
        addSettings(pauseInFight, showRoute, routeColor, bridge, pillar, blocks);
        searchTags("goto", "path", "route", "walking", "bridge", "pillar");
    }

    // The row only holds settings. There is nothing to switch on.
    @Override
    public boolean isTogglable() {
        return false;
    }

    // A walk that takes the player to the fight is part of it.
    public static boolean pausesWalk() {
        PathfinderModule row = Modules.get(PathfinderModule.class);
        return row != null && row.pauseInFight.isOn() && Modules.fighting() && !Modules.walksToFight();
    }

    public static boolean showsRoute() {
        PathfinderModule row = Modules.get(PathfinderModule.class);
        return row != null && row.showRoute.isOn();
    }

    public static int routeColor() {
        PathfinderModule row = Modules.get(PathfinderModule.class);
        return row == null ? 0 : row.routeColor.getColor();
    }

    public static boolean bridges() {
        PathfinderModule row = Modules.get(PathfinderModule.class);
        return row != null && row.bridge.isOn();
    }

    public static boolean pillars() {
        PathfinderModule row = Modules.get(PathfinderModule.class);
        return row != null && row.pillar.isOn();
    }

    public static boolean lays(Block block) {
        PathfinderModule row = Modules.get(PathfinderModule.class);
        return row != null && row.blocks.contains(block);
    }
}
