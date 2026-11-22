package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.World;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

// One case for each module that works a screen for the player. The block stands on the stage
// with what it needs in the backpack. The player opens it by hand and the module must turn
// out the goods.
public final class StationsSuite implements Suite {

    private static final String NAME = "Stations";
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;

    private static final int SEEN_TICKS = 10;
    private static final int OPEN_TICKS = 40;
    private static final int WORK_TICKS = 300;
    // A brewing stand takes twenty seconds over each of the two steps to a strength potion.
    private static final int BREW_TICKS = 1400;
    private static final int LEVELS = 30;
    private static final String NEW_NAME = "rigbox";

    private BlockPos home;
    private BlockPos station;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Rig rig) {
        Grounds.play(rig, NAME, plays(), stage -> {
            home = stage.home();
            station = home.north(2);
        });
    }

    private Map<String, Case.Body> plays() {
        Map<String, Case.Body> plays = new LinkedHashMap<>();
        plays.put("AutoCraft makes sticks in your own inventory", this::craftByHand);
        plays.put("AutoCraft makes stairs at a crafting table", this::craftAtTable);
        plays.put("AutoCraft says so when the recipe book has no recipe", this::craftUnknown);
        plays.put("AutoBrewer brews a strength potion", this::autoBrewer);
        plays.put("AutoEnchant enchants a book", this::autoEnchant);
        plays.put("Grinder grinds the enchantment off a bow", this::grinder);
        plays.put("AutoSmith makes a diamond sword netherite", this::autoSmith);
        plays.put("AutoMason cuts stone into slabs", this::autoMason);
        plays.put("AutoRename names a shulker box", this::autoRename);
        plays.put("AutoBooks puts a book onto a sword", this::autoBooks);
        plays.put("AutoTrade sells wheat to a villager", this::autoTrade);
        return plays;
    }

    private void open(Rig rig, String block) {
        rig.world().setBlock(station, block);
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAt(Vec3.atCenterOf(station));
        rig.ticks(2);
        rig.controls().click(USE);
        rig.until("the screen of the " + block + " to open", OPEN_TICKS,
            mc -> mc.gui.screen() instanceof AbstractContainerScreen);
    }

    private static void close(Rig rig) {
        rig.act(mc -> {
            if (mc.gui.screen() != null) {
                mc.player.closeContainer();
            }
        });
        rig.ticks(SEEN_TICKS);
    }

    private static void awaitGoods(Rig rig, String item, int count, int ticks) {
        try {
            int took = Props.await(rig, count + " " + item + " in the backpack", ticks,
                () -> Props.carried(rig, item) >= count);
            rig.note("The " + item + " was there after about " + took + " ticks");
        } finally {
            close(rig);
        }
    }

    private static void awaitStack(Rig rig, String what, Predicate<ItemStack> wanted) {
        try {
            rig.until(what, WORK_TICKS, mc -> Props.has(mc.player.getInventory(), wanted));
        } finally {
            close(rig);
        }
    }

    private void craftByHand(Rig rig) {
        Props.pick(rig, "AutoCraft", "Items", "minecraft:stick");
        planks(rig);
        rig.client().enable("AutoCraft");
        rig.act(mc -> mc.gui.setScreen(new InventoryScreen(mc.player)));
        awaitGoods(rig, "stick", 4, WORK_TICKS);
    }

    private void craftAtTable(Rig rig) {
        Props.pick(rig, "AutoCraft", "Items", "minecraft:oak_stairs");
        planks(rig);
        rig.client().enable("AutoCraft");
        open(rig, "crafting_table");
        awaitGoods(rig, "oak_stairs", 4, WORK_TICKS);
    }

    // Nobody learns the beacon without a nether star.
    private void craftUnknown(Rig rig) {
        Props.pick(rig, "AutoCraft", "Items", "minecraft:beacon");
        planks(rig);
        int mark = rig.client().chatMark();
        rig.client().enable("AutoCraft");
        open(rig, "crafting_table");
        try {
            rig.until("AutoCraft to give up", WORK_TICKS, mc -> !rig.client().module("AutoCraft").isEnabled());
        } finally {
            close(rig);
        }
        rig.expect(rig.client().saidSince(mark).stream().anyMatch(line -> line.contains("no recipe")),
            "Chat does not say the recipe book has no recipe");
    }

    // The game learns what planks make a moment after the first planks arrive.
    private static void planks(Rig rig) {
        Props.pack(rig, 0, "oak_planks 16");
        rig.ticks(SEEN_TICKS * 2);
    }

    private void autoBrewer(Rig rig) {
        for (int bottle = 0; bottle < 3; bottle++) {
            Props.pack(rig, bottle, "potion[potion_contents={potion:\"minecraft:water\"}]");
        }
        Props.pack(rig, 3, "nether_wart 4");
        Props.pack(rig, 4, "blaze_powder 8");
        rig.client().enable("AutoBrewer");
        open(rig, "brewing_stand");
        awaitGoods(rig, "potion[potion_contents={potion:\"minecraft:strength\"}]", 1, BREW_TICKS);
    }

    private void autoEnchant(Rig rig) {
        World world = rig.world();
        world.command("xp set " + world.player() + " " + LEVELS + " levels");
        Props.pack(rig, 0, "book 4");
        Props.pack(rig, 1, "lapis_lazuli 16");
        rig.client().enable("AutoEnchant");
        open(rig, "enchanting_table");
        awaitGoods(rig, "enchanted_book", 1, WORK_TICKS);
    }

    private void grinder(Rig rig) {
        Props.pack(rig, 0, "bow[enchantments={\"minecraft:power\":3}]");
        rig.ticks(SEEN_TICKS);
        boolean sharp = rig.ask(mc -> Props.has(mc.player.getInventory(),
            stack -> stack.is(Items.BOW) && stack.isEnchanted()));
        rig.client().enable("Grinder");
        open(rig, "grindstone[face=floor]");
        awaitStack(rig, "a bow with nothing on it", stack -> stack.is(Items.BOW) && !stack.isEnchanted());
        rig.expect(sharp, "The bow carried no enchantment to begin with. That proves nothing");
    }

    private void autoSmith(Rig rig) {
        Props.pack(rig, 0, "diamond_sword");
        Props.pack(rig, 1, "netherite_ingot");
        Props.pack(rig, 2, "netherite_upgrade_smithing_template");
        rig.client().enable("AutoSmith");
        open(rig, "smithing_table");
        awaitGoods(rig, "netherite_sword", 1, WORK_TICKS);
    }

    private void autoMason(Rig rig) {
        Props.pick(rig, "AutoMason", "Cut into", "minecraft:stone_slab");
        Props.pack(rig, 0, "stone 4");
        rig.client().enable("AutoMason");
        open(rig, "stonecutter");
        awaitGoods(rig, "stone_slab", 2, WORK_TICKS);
    }

    private void autoRename(Rig rig) {
        World world = rig.world();
        world.command("xp set " + world.player() + " " + LEVELS + " levels");
        rig.client().set("AutoRename", "Name", NEW_NAME);
        Props.pack(rig, 0, "shulker_box");
        rig.client().enable("AutoRename");
        open(rig, "anvil");
        awaitStack(rig, "a shulker box named " + NEW_NAME,
            stack -> stack.is(Items.SHULKER_BOX) && NEW_NAME.equals(stack.getHoverName().getString()));
    }

    private void autoBooks(Rig rig) {
        World world = rig.world();
        world.command("xp set " + world.player() + " " + LEVELS + " levels");
        Props.choose(rig, "AutoBooks", "Swords", "sharpness");
        Props.pack(rig, 0, "diamond_sword");
        Props.pack(rig, 1, "enchanted_book[stored_enchantments={\"minecraft:sharpness\":3}]");
        rig.client().enable("AutoBooks");
        open(rig, "anvil");
        awaitStack(rig, "a sword with the book on it", stack -> stack.is(Items.DIAMOND_SWORD) && stack.isEnchanted());
    }

    private void autoTrade(Rig rig) {
        Props.summon(rig, "villager", station, "{NoAI:1b,PersistenceRequired:1b,VillagerData:{profession:"
            + "\"minecraft:farmer\",level:2,type:\"minecraft:plains\"},Offers:{Recipes:[{buy:{id:\"minecraft:wheat\","
            + "count:20},sell:{id:\"minecraft:emerald\",count:1},maxUses:99}]}}");
        Props.pick(rig, "AutoTrade", "Sell", "minecraft:wheat");
        Props.pack(rig, 0, "wheat 64");
        rig.client().enable("AutoTrade");
        rig.ticks(SEEN_TICKS);
        rig.controls().aimAt(Vec3.atBottomCenterOf(station).add(0, 1, 0));
        rig.ticks(2);
        rig.controls().click(USE);
        rig.until("the trading screen to open", OPEN_TICKS, mc -> mc.gui.screen() instanceof AbstractContainerScreen);
        awaitGoods(rig, "emerald", 1, WORK_TICKS);
    }
}
