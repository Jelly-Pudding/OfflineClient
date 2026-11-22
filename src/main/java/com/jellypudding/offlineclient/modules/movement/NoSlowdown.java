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
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.ServerSneak;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

// Each slowdown source has its own mixin hook across several classes.
// Vanilla slows slime twice through step and friction. Both are covered here.
public final class NoSlowdown extends Module {

    public enum WebMode { FULL_SPEED, TIMER, OFF }

    private static final String TIMER_KEY = "noslowdown";

    // Lava keeps half the sideways speed each tick and plain ground a little more. A push
    // this much above the one on land ends at the same pace.
    private static final float LAVA_PUSH =
        0.5f / (1 - MovementUtil.GROUND_FRICTION * LivingEntity.BASE_HORIZONTAL_AIR_DRAG);

    // Every climbable block but ladders and scaffolding.
    private static final Set<Block> VINES = Set.of(Blocks.VINE, Blocks.WEEPING_VINES,
        Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT,
        Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT);

    private final BoolSetting items = new BoolSetting("Items",
        "Eating and drinking and drawing a bow no longer slow you.", true);
    private final BoolSetting airStrict = new BoolSetting("Air strict",
        "Tells the server you sneak whilst you use an item. Some anticheats then allow the speed.", false)
        .under(items);
    private final BoolSetting sneaking = new BoolSetting("Sneaking",
        "Sneaking no longer slows you down. You can even sprint whilst you sneak.", false);
    private final BoolSetting hunger = new BoolSetting("Hunger",
        "Lets you sprint with an empty hunger bar.", true);
    private final BoolSetting slowness = new BoolSetting("Slowness",
        "The slowness effect no longer slows you.", true);
    private final BoolSetting honey = new BoolSetting("Honey blocks",
        "Lets you walk over honey at full speed.", true);
    private final BoolSetting soulSand = new BoolSetting("Soul sand",
        "Lets you walk over soul sand and soul soil at full speed.", true);
    private final BoolSetting slime = new BoolSetting("Slime blocks",
        "Lets you walk over slime blocks at full speed.", true);
    private final BoolSetting berryBushes = new BoolSetting("Berry bushes",
        "Lets you walk through berry bushes at full speed.", true);
    private final BoolSetting vines = new BoolSetting("Vines",
        "Vines of every kind no longer slow you or catch your fall. You can no longer climb them either.", false);
    private final EnumSetting<WebMode> webs = new EnumSetting<>("Cobwebs",
        "How cobwebs are handled.", WebMode.FULL_SPEED)
        .describe(WebMode.FULL_SPEED, "Lets you walk through cobwebs at full speed.")
        .describe(WebMode.TIMER, "Speeds the game up whilst a cobweb holds you. Every tick sends its packets"
            + " and the speed keeps to a pace the server never drops you for.")
        .describe(WebMode.OFF, "Cobwebs slow you as normal.");
    private final NumberSetting webTimer = new NumberSetting("Web timer",
        "Game speed whilst you walk in a cobweb. A fall through one always runs at the top pace and"
            + " still stays slow next to a walk because a web holds a fall back far harder.",
        MoveGate.STEADY_SPEED, 1, MoveGate.STEADY_SPEED, 0.5, "x")
        .under(webs, WebMode.TIMER);
    private final BoolSetting fluidDrag = new BoolSetting("Fluid drag",
        "Water and lava no longer slow you sideways. A sprint still ends at the surface as in the plain game.",
        false);
    private final BoolSetting buoyancy = new BoolSetting("Buoyancy",
        "You still float and sink and swim up and down as usual. Off lets you fall through water and lava"
            + " as through air.", true)
        .under(fluidDrag);

    private final ServerSneak strictSneak = new ServerSneak();

    public NoSlowdown() {
        super("NoSlowdown", "Keeps your full speed through everything that would normally slow you down.",
            Category.MOVEMENT);
        addSettings(items, airStrict, sneaking, hunger, slowness, honey, soulSand, slime,
            berryBushes, vines, webs, webTimer, fluidDrag, buoyancy);
        searchTags("noslow", "eating", "sneak speed", "soul sand", "honey", "cobweb", "NoWeb", "no web");
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
        strictSneak.release();
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

    // Read by LivingEntityMixin. The vine you are in reads as air.
    public boolean skipsVine(BlockState state) {
        return isEnabled() && vines.isOn() && VINES.contains(state.getBlock());
    }

    public boolean skipsWebs() {
        return isEnabled() && webs.is(WebMode.FULL_SPEED);
    }

    // Read by LivingEntityMixin.
    public boolean skipsFluidDrag() {
        return isEnabled() && fluidDrag.isOn();
    }

    // Read by EntityMixin.
    public boolean ignoresFluids() {
        return skipsFluidDrag() && !buoyancy.isOn();
    }

    // Read by LivingEntityMixin.
    public float lavaPush(float push, float speed) {
        return skipsFluidDrag() ? speed * LAVA_PUSH : push;
    }

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

    public float groundFriction(Block block, float friction) {
        if (block == Blocks.SLIME_BLOCK && skipsSlime()) {
            return MovementUtil.GROUND_FRICTION;
        }
        return friction;
    }

    // Level six leaves nothing to restore.
    public float withoutSlowness(float speed, int amplifier) {
        if (!isEnabled() || !slowness.isOn()) {
            return speed;
        }
        double kept = 1 - MovementUtil.SLOWNESS_PER_LEVEL * (amplifier + 1);
        return kept <= 0 ? speed : (float) (speed / kept);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        Timer.override(TIMER_KEY, webSpeed());
        strictSneak.want(items.isOn() && airStrict.isOn() && mc.player.isUsingItem());
    }

    // A web takes far more of a fall than of a walk and the fall gets all the speed there is.
    private float webSpeed() {
        Entity mover = mc.player.getRootVehicle();
        if (!webs.is(WebMode.TIMER) || !websHold(mover) || !BlockUtil.inCobweb(mover)) {
            return 1f;
        }
        float pace = MoveGate.steadySpeed();
        return mover.onGround() ? Math.min(webTimer.getFloat(), pace) : pace;
    }

    // A web holds a mount and not its rider and never a player who flies. The timer only
    // helps a mount the client moves itself.
    private static boolean websHold(Entity mover) {
        return mover == mc.player ? !mc.player.getAbilities().flying : mover.isLocalInstanceAuthoritative();
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        strictSneak.keep(event);
    }
}
