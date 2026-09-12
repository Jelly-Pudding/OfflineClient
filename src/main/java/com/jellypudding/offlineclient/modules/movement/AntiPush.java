package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.phys.Vec3;

// Entities and currents live in EntityMixin and LocalPlayerMixin.
// Geysers live in PotentSulfurBlockEntityMixin.
public final class AntiPush extends Module {

    private final BoolSetting entities = new BoolSetting("Entities",
        "Players and mobs cannot shove you whilst you still push them.", true);
    private final NumberSetting entityPush = new NumberSetting("Entity push",
        "How much of a shove you still take.", 0, 0, 100, 1, "%")
        .under(entities);
    private final BoolSetting currents = new BoolSetting("Water currents",
        "Flowing water and lava cannot drag you.", true);
    private final NumberSetting currentHorizontal = new NumberSetting("Current horizontal",
        "How much sideways drag you still take from a current.", 0, 0, 100, 1, "%")
        .under(currents);
    private final NumberSetting currentVertical = new NumberSetting("Current vertical",
        "How much upward or downward drag you still take from a current.", 0, 0, 100, 1, "%")
        .under(currents);
    private final BoolSetting bubbleColumns = new BoolSetting("Bubble columns",
        "Bubble columns cannot lift you or pull you down.", true);
    private final BoolSetting geysers = new BoolSetting("Geysers",
        "Sulfur geysers cannot launch you into the air.", true);
    private final BoolSetting sinking = new BoolSetting("Sinking",
        "Float in place in water or lava unless you hold jump or sneak.", false);

    public AntiPush() {
        super("AntiPush", "Stops water and entities from pushing you around.", Category.MOVEMENT);
        addSettings(entities, entityPush, currents, currentHorizontal, currentVertical,
            bubbleColumns, geysers, sinking);
        searchTags("anti water push", "anti entity push", "bubble", "collision", "shove", "sulfur",
            "velocity", "float");
    }

    // True when no shove at all should land. The cheap path that skips the push outright.
    public boolean blocksEntities() {
        return isEnabled() && entities.isOn() && entityPush.getValue() <= 0;
    }

    // The share of a shove from another entity that still lands.
    public double entityPushScale() {
        return isEnabled() && entities.isOn() ? entityPush.getValue() / 100.0 : 1;
    }

    // True when no current at all should pull. The cheap path that skips the flow maths.
    public boolean blocksCurrents() {
        return isEnabled() && currents.isOn()
            && currentHorizontal.getValue() <= 0 && currentVertical.getValue() <= 0;
    }

    // The flow of a current with the kept share of each axis.
    public Vec3 scaleCurrent(Vec3 flow) {
        if (!isEnabled() || !currents.isOn()) {
            return flow;
        }
        double h = currentHorizontal.getValue() / 100.0;
        double v = currentVertical.getValue() / 100.0;
        return flow.multiply(h, v, h);
    }

    public boolean blocksBubbleColumns() {
        return isEnabled() && bubbleColumns.isOn();
    }

    public boolean blocksGeysers() {
        return isEnabled() && geysers.isOn();
    }

    // Fluid gravity lands after the move each tick. Clearing it here leaves no sink at all.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        if (!inGame() || !sinking.isOn()) {
            return;
        }
        if (mc.options.keyJump.isDown() || mc.options.keyShift.isDown()) {
            return;
        }
        if (!mc.player.isInWater() && !mc.player.isInLava()) {
            return;
        }
        Vec3 delta = mc.player.getDeltaMovement();
        if (delta.y < 0) {
            mc.player.setDeltaMovement(delta.x, 0, delta.z);
        }
    }
}
