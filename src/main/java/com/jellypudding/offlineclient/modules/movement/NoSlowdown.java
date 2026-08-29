package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Each source of slowdown is lifted by its own hook. Items and sneaking and
 * slowness live in LocalPlayerMixin. Hunger is FoodDataMixin and the blocks
 * have a mixin each. Slime is slowed twice by vanilla so both the step and
 * the friction are covered. Cobwebs share WebBlockMixin with NoWeb.
 */
public final class NoSlowdown extends Module {

    // Slowness takes this share of the speed off per level.
    private static final double SLOWNESS_PER_LEVEL = 0.15;

    // The friction of plain ground. Slime is stickier which drags the top speed down.
    private static final float NORMAL_FRICTION = 0.6f;

    private final BoolSetting items = new BoolSetting("Items",
        "Eating and drinking and drawing a bow no longer slow you.", true);
    private final BoolSetting sneaking = new BoolSetting("Sneaking",
        "Sneak at walking speed.", false);
    private final BoolSetting hunger = new BoolSetting("Hunger",
        "Sprint with an empty hunger bar.", true);
    private final BoolSetting slowness = new BoolSetting("Slowness",
        "The slowness effect no longer slows you.", true);
    private final BoolSetting honey = new BoolSetting("Honey blocks",
        "Walk over honey at full speed.", true);
    private final BoolSetting soulSand = new BoolSetting("Soul sand",
        "Walk over soul sand and soul soil at full speed.", true);
    private final BoolSetting slime = new BoolSetting("Slime blocks",
        "Walk over slime blocks at full speed.", true);
    private final BoolSetting berryBushes = new BoolSetting("Berry bushes",
        "Walk through berry bushes at full speed.", true);
    private final BoolSetting webs = new BoolSetting("Cobwebs",
        "Walk through cobwebs at full speed.", true);

    public NoSlowdown() {
        super("NoSlowdown", "Keeps your full speed through everything that would normally slow you down.",
            Category.MOVEMENT);
        addSettings(items, sneaking, hunger, slowness, honey, soulSand, slime, berryBushes, webs);
        searchTags("noslow", "eating", "sneak speed", "soul sand", "honey", "cobweb");
    }

    public boolean skipsItems() {
        return isEnabled() && items.isOn();
    }

    public boolean skipsSneaking() {
        return isEnabled() && sneaking.isOn();
    }

    public boolean skipsHunger() {
        return isEnabled() && hunger.isOn();
    }

    public boolean skipsSlime() {
        return isEnabled() && slime.isOn();
    }

    public boolean skipsBerryBushes() {
        return isEnabled() && berryBushes.isOn();
    }

    public boolean skipsWebs() {
        return isEnabled() && webs.isOn();
    }

    // True when the block under or around the feet should not drag the speed down.
    public boolean skipsBlockFriction(Block block) {
        if (!isEnabled()) {
            return false;
        }
        if (block == Blocks.HONEY_BLOCK) {
            return honey.isOn();
        }
        if (block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL) {
            return soulSand.isOn();
        }
        return false;
    }

    // The friction the ground under the player should have. Slime reads as plain ground.
    public float groundFriction(Block block, float friction) {
        if (block == Blocks.SLIME_BLOCK && skipsSlime()) {
            return NORMAL_FRICTION;
        }
        return friction;
    }

    // The speed with the slowness share put back. Level six and up leaves nothing to restore.
    public float withoutSlowness(float speed, int amplifier) {
        if (!isEnabled() || !slowness.isOn()) {
            return speed;
        }
        double kept = 1 - SLOWNESS_PER_LEVEL * (amplifier + 1);
        return kept <= 0 ? speed : (float) (speed / kept);
    }
}
