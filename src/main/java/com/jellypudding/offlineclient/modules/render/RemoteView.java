package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.AwayView;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// CameraMixin places the view the same way it does for Freecam.
public final class RemoteView extends Module implements AwayView {

    public enum Pick { NEAREST, NAME }

    private static final double SEARCH_RANGE = 128;

    private final EnumSetting<Pick> pick = new EnumSetting<>("View", "Whose eyes are borrowed.",
        Pick.NEAREST)
        .describe(Pick.NEAREST, "The nearest entity the filter allows.")
        .describe(Pick.NAME, "The nearest entity with the name you type in. Switching on again moves"
            + " to the next one of that name.");
    private final TextSetting name = new TextSetting("Name",
        "A player name or the name on a name tag or the name of a kind such as Zombie. Click to type it.", "")
        .under(pick, Pick.NAME);
    private final EntityFilter filter = EntityFilter.living("Views", "viewed", true,
        EntityFilter.Pick.ALL, List.of());
    private final BoolSetting ignoreCreative = new BoolSetting("Ignore creative",
        "Never picks a player in creative mode.", false)
        .visibleWhen(filter::wantsPlayers);
    private final EnumSetting<InteractFrom> interactFrom = AwayView.interactFromSetting();
    private final BoolSetting hideHand = AwayView.hideHandSetting();

    private Entity viewed;
    private boolean wasInvisible;

    // The network id of whoever was viewed last. A second ask for the same name starts after it.
    private int lastViewed = -1;
    private Entity asked;

    public RemoteView() {
        super("RemoteView", "Shows the world through the eyes of another player or mob.", Category.RENDER);
        addSettings(pick, name);
        addSettings(filter.settings());
        addSettings(ignoreCreative, interactFrom, hideHand);
        searchTags("remote view", "spectate", "possess");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return viewed == null ? null : EntityUtil.displayNameOf(viewed);
    }

    public void view(Entity entity) {
        setEnabled(false);
        asked = entity;
        setEnabled(true);
        asked = null;
    }

    @Override
    protected void onEnable() {
        viewed = !inGame() ? null : asked != null ? asked : pickTarget();
        if (viewed == null) {
            disable("Nothing to view was found.");
            return;
        }
        AwayView.claim(this);
        lastViewed = viewed.getId();
        wasInvisible = viewed.isInvisible();
        ChatUtil.message("Now viewing §b" + viewed.getName().getString() + "§7.");
    }

    @Override
    protected void onDisable() {
        if (viewed != null) {
            viewed.setInvisible(wasInvisible);
            ChatUtil.message("No longer viewing §b" + viewed.getName().getString() + "§7.");
            viewed = null;
        }
    }

    private Entity pickTarget() {
        List<Entity> fits = EntityUtil.ranked(SEARCH_RANGE, TargetPriority.NEAREST, this::viewable);
        if (fits.isEmpty()) {
            return null;
        }
        int last = -1;
        for (int i = 0; pick.is(Pick.NAME) && i < fits.size(); i++) {
            if (fits.get(i).getId() == lastViewed) {
                last = i;
            }
        }
        return fits.get((last + 1) % fits.size());
    }

    private boolean viewable(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !living.isAlive() || Modules.isLocalBody(entity)
            || !filter.matches(entity)) {
            return false;
        }
        if (ignoreCreative.isOn() && entity instanceof Player player && player.isCreative()) {
            return false;
        }
        return pick.is(Pick.NEAREST) || EntityUtil.isCalled(entity, name.getValue().trim());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (viewed == null || viewed.isRemoved() || viewed.level() != mc.level
            || !viewed.isAlive()) {
            setEnabled(false);
            return;
        }
        // The camera sits inside its head and the model would fill the screen.
        viewed.setInvisible(true);
    }

    @Override
    public Vec3 getCamPos(float partialTicks) {
        return viewed.getEyePosition(partialTicks);
    }

    @Override
    public float getCamYaw() {
        return viewed.getViewYRot(framePartial());
    }

    @Override
    public float getCamPitch() {
        return viewed.getViewXRot(framePartial());
    }

    private float framePartial() {
        return mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }

    @Override
    public boolean hidesHand() {
        return isEnabled() && hideHand.isOn();
    }

    @Override
    public boolean interactsFromCamera() {
        return isEnabled() && interactFrom.is(InteractFrom.CAMERA);
    }

    @Override
    public Entity carrier() {
        return viewed;
    }
}
