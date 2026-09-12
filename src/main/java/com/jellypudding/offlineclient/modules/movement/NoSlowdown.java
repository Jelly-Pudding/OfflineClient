package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

// Each slowdown source has its own mixin hook across several classes.
// Vanilla slows slime twice through step and friction. Both are covered here.
public final class NoSlowdown extends Module {

    public enum WebMode { FULL_SPEED, TIMER, OFF }

    // Slowness takes this share of the speed off per level.
    public static final double SLOWNESS_PER_LEVEL = 0.15;

    // The friction of plain ground. Slime is stickier which drags the top speed down.
    private static final float NORMAL_FRICTION = 0.6f;

    private static final String TIMER_KEY = "noslowdown";

    private final BoolSetting items = new BoolSetting("Items",
        "Eating and drinking and drawing a bow no longer slow you.", true);
    private final BoolSetting airStrict = new BoolSetting("Air strict",
        "Tells the server you sneak whilst you use an item. Some anticheats then allow the speed.", false)
        .under(items);
    private final BoolSetting sneaking = new BoolSetting("Sneaking",
        "Sneaking no longer slows you down. You can even sprint whilst you sneak.", false);
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
    private final EnumSetting<WebMode> webs = new EnumSetting<>("Cobwebs",
        "How cobwebs are handled.", WebMode.FULL_SPEED)
        .describe(WebMode.FULL_SPEED, "Walk through cobwebs at full speed.")
        .describe(WebMode.TIMER, "Speeds the game up whilst you hang in a cobweb off the ground.")
        .describe(WebMode.OFF, "Cobwebs slow you as normal.");
    private final NumberSetting webTimer = new NumberSetting("Web timer",
        "Game speed whilst in a cobweb.", 10, 1, 20, 0.5, "x")
        .min(1).under(webs, WebMode.TIMER);
    private final BoolSetting fluidDrag = new BoolSetting("Fluid drag",
        "Water and lava no longer drag on you. The server still caps how far you get.", false);

    // Whether the server has been told the sneak that air strict fakes.
    private boolean strictTold;

    public NoSlowdown() {
        super("NoSlowdown", "Keeps your full speed through everything that would normally slow you down.",
            Category.MOVEMENT);
        addSettings(items, airStrict, sneaking, hunger, slowness, honey, soulSand, slime,
            berryBushes, webs, webTimer, fluidDrag);
        searchTags("noslow", "eating", "sneak speed", "soul sand", "honey", "cobweb");
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
        if (strictTold) {
            tellShift(false);
        }
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
        return isEnabled() && webs.is(WebMode.FULL_SPEED);
    }

    // Read by EntityMixin. True whilst the local player should read as out of every fluid.
    public boolean skipsFluidDrag() {
        return isEnabled() && fluidDrag.isOn();
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

    // The speed with the slowness share put back. Level six leaves nothing to restore.
    public float withoutSlowness(float speed, int amplifier) {
        if (!isEnabled() || !slowness.isOn()) {
            return speed;
        }
        double kept = 1 - SLOWNESS_PER_LEVEL * (amplifier + 1);
        return kept <= 0 ? speed : (float) (speed / kept);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        boolean hanging = webs.is(WebMode.TIMER) && !mc.player.onGround()
            && mc.player.getInBlockState().is(Blocks.COBWEB);
        Timer.override(TIMER_KEY, hanging ? webTimer.getFloat() : 1f);

        boolean strict = items.isOn() && airStrict.isOn() && mc.player.isUsingItem();
        if (strict != strictTold) {
            tellShift(strict);
        }
    }

    private void tellShift(boolean shift) {
        strictTold = shift;
        InputUtil.sendShift(shift);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!strictTold || !(event.getPacket() instanceof ServerboundPlayerInputPacket packet)) {
            return;
        }
        if (!packet.input().shift()) {
            event.setPacket(new ServerboundPlayerInputPacket(InputUtil.withShift(packet.input(), true)));
        }
    }
}
