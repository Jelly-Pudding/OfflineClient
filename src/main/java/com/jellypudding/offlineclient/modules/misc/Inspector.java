package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.gui.InspectorScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.AwayView;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.TextLines;
import com.jellypudding.offlineclient.util.WorldWatch;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Opens a sheet about one entity on its key. The entity looked at comes first and the nearest
// one included below when the crosshair is on nothing. A mark put on from the sheet glows
// through walls until it is taken off.
public final class Inspector extends Module {

    private static final String NAME = "{name}";

    private final NumberSetting range = new NumberSetting("Range",
        "How far away the entity may be.", 64, 4, 128, 4, " blocks").min(1);
    private final BoolSetting nearest = new BoolSetting("Nearest if missed",
        "When the crosshair is on nothing the nearest entity included below opens instead.", true);
    private final EntityFilter included = EntityFilter.living("Includes", "included", true,
        EntityFilter.Pick.ALL, List.of());
    private final ColorSetting markColour = new ColorSetting("Mark colour",
        "The glow on an entity you mark from the sheet.", 50, false);
    private final ActionSetting clearMarks = new ActionSetting("Clear marks",
        "Takes the glow off every entity you marked.", () -> Tally.cleared(unmarkAll(), "mark"));
    private final TextLines messages = new TextLines("Messages", "How many message lines the sheet offers.",
        "A line the sheet offers. It opens the chat box with " + NAME + " swapped for the name and sends"
            + " nothing until you press enter.", "/msg " + NAME, "/tpa " + NAME).plain();

    // The marked entities by network id and the world they were marked in.
    private final IntOpenHashSet marked = new IntOpenHashSet();
    private final WorldWatch world = new WorldWatch();

    public Inspector() {
        super("Inspector", "Its key opens a sheet about the entity you look at with actions for it.",
            Category.MISC);
        for (Setting<?> setting : included.settings()) {
            if (setting.getParent() == null) {
                setting.under(nearest);
            }
        }
        addSettings(range, nearest);
        addSettings(included.settings());
        addSettings(markColour, clearMarks);
        addSettings(messages.settings());
        searchTags("interaction menu", "entity info", "entity menu", "stats", "inspect", "glow", "msg");
    }

    // The key opens the sheet and there is nothing to switch on.
    @Override
    public boolean isTogglable() {
        return false;
    }

    @Override
    public void onKeybind() {
        if (!inGame() || mc.gui.screen() != null) {
            return;
        }
        Entity target = lookedAt();
        if (target == null && nearest.isOn()) {
            target = EntityUtil.nearest(range.getValue(),
                entity -> included.matches(entity) && !Modules.isLocalBody(entity));
        }
        if (target == null) {
            ChatUtil.error("There is nothing to inspect in sight.");
            return;
        }
        InspectorScreen.open(this, target);
    }

    // The entity the crosshair is on within range. A block in the way hides what is behind it.
    // The crosshair follows the camera whilst Freecam or RemoteView has it.
    private Entity lookedAt() {
        LocalPlayer player = mc.player;
        AwayView away = AwayView.active();
        Vec3 from = away == null ? player.getEyePosition() : away.getCamPos(1);
        Vec3 look = away == null ? player.getViewVector(1)
            : Vec3.directionFromRotation(away.getCamPitch(), away.getCamYaw());
        Vec3 end = from.add(look.scale(range.getValue()));
        HitResult block = mc.level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
            player));
        Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        Entity carrier = away == null ? null : away.carrier();
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, from, stop, new AABB(from, stop).inflate(1),
            entity -> entity != carrier && !entity.isSpectator() && entity.isPickable() && !Modules.isLocalBody(entity),
            from.distanceToSqr(stop));
        return hit == null ? null : hit.getEntity();
    }

    // Every message line in use with text in it.
    public List<String> messageLines() {
        return messages.all();
    }

    // The line with the name written in.
    public static String fillIn(String line, String name) {
        return line.replace(NAME, name);
    }

    public boolean isMarked(Entity entity) {
        forgetOldWorld();
        return marked.contains(entity.getId());
    }

    // True when the entity is marked afterwards.
    public boolean toggleMark(Entity entity) {
        forgetOldWorld();
        if (marked.remove(entity.getId())) {
            return false;
        }
        marked.add(entity.getId());
        return true;
    }

    // Takes every mark off and counts how many there were.
    private int unmarkAll() {
        forgetOldWorld();
        int count = marked.size();
        marked.clear();
        return count;
    }

    // True for a marked entity whilst the client draws in the world.
    public boolean glows(Entity entity) {
        return !marked.isEmpty() && isMarked(entity) && !Conceal.hides(Conceal.Part.WORLD);
    }

    public int markColour() {
        return markColour.getColor();
    }

    // Network ids only hold within one world.
    private void forgetOldWorld() {
        if (world.changed()) {
            marked.clear();
        }
    }
}
