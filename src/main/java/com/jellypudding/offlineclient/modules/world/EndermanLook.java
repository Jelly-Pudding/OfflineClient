package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.phys.Vec3;

// The server decides you are staring from the angle it last heard from you.
// Holding that angle away from an enderman keeps it calm.
public final class EndermanLook extends Module {

    public enum Mode { AWAY, AT }

    // How wide the vanilla stare test is before distance widens it.
    private static final double STARE_SPREAD = 0.025;

    // Straight down.
    private static final float FLOOR_PITCH = 90f;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What to do about the endermen near you.", Mode.AWAY)
        .describe(Mode.AWAY, "Looks at the floor whenever you would stare at one.")
        .describe(Mode.AT, "Stares at every calm enderman to wind it up.");
    private final BoolSetting stun = new BoolSetting("Stun angry ones",
        "Stares at an angry enderman so it freezes instead of closing in.", true)
        .under(mode, Mode.AWAY);

    public EndermanLook() {
        super("EndermanLook", "Keeps your gaze off endermen or puts it right on them.", Category.WORLD);
        addSettings(mode, stun);
        searchTags("enderman", "stare", "gaze");
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        // A carved pumpkin already hides your gaze and creative mode is ignored.
        if (mc.player.getItemBySlot(EquipmentSlot.HEAD).is(ItemTags.GAZE_DISGUISE_EQUIPMENT)
            || mc.player.getAbilities().instabuild) {
            return;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EnderMan enderman) || !enderman.isAlive()
                || !mc.player.hasLineOfSight(enderman)) {
                continue;
            }
            if (mode.is(Mode.AT)) {
                if (!enderman.isCreepy()) {
                    stareAt(enderman);
                    return;
                }
                continue;
            }
            if (enderman.isCreepy() && stun.isOn()) {
                stareAt(enderman);
                return;
            }
            if (staringAt(enderman)) {
                RotationManager.requestExact(mc.player.getYRot(), FLOOR_PITCH, RotationPriority.IDLE);
                return;
            }
        }
    }

    private void stareAt(EnderMan enderman) {
        Vec3 head = new Vec3(enderman.getX(), enderman.getEyeY(), enderman.getZ());
        RotationManager.requestExact(RotationManager.yawTo(head),
            RotationManager.pitchTo(head), RotationPriority.IDLE);
    }

    // The same test the game runs. A distant enderman needs a tighter aim to notice.
    private boolean staringAt(EnderMan enderman) {
        Vec3 view = mc.player.getViewVector(1f).normalize();
        Vec3 toward = new Vec3(enderman.getX() - mc.player.getX(),
            enderman.getEyeY() - mc.player.getEyeY(), enderman.getZ() - mc.player.getZ());
        double distance = toward.length();
        return view.dot(toward.normalize()) > 1 - STARE_SPREAD / distance;
    }
}
