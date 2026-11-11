package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ProjectilePath;
import com.jellypudding.offlineclient.util.ProjectilePath.Path;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// The box at the landing is the spot the thrower arrives at. PearlOwners keeps naming the
// thrower once they are out of sight. A pearl resting in a stasis chamber is marked where it
// waits.
public final class PearlTracker extends Module {

    // A pearl in the air and the rest of its flight. The landing is null until one is found.
    private record Flight(ThrownEnderpearl pearl, String thrower, List<Vec3> points, Vec3 landing) {
    }

    // A pearl a bubble column holds still and its owner.
    private record Resting(ThrownEnderpearl pearl, String owner) {
    }

    // Half a minute of flight. A pearl still up after that gets no landing.
    private static final int FLIGHT_TICKS = 600;

    // The label floats this far over the top of the landing box.
    private static final double LABEL_LIFT = 0.3;

    // A pearl is tiny. Its box is drawn this much wider all round.
    private static final double RESTING_MARGIN = 0.2;

    private static final int NAME_COLOUR = 0xFFFFFFFF;
    private static final float STASIS_HUE = 190;

    private final BoolSetting ownPearls = new BoolSetting("Own pearls",
        "Also tracks the pearls you throw.", false);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Leaves out the pearls your friends throw.", false);
    private final BoolSetting path = new BoolSetting("Path",
        "Draws the rest of each flight as a line.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 280f);
    private final BoolSetting names = new BoolSetting("Names",
        "Shows who threw each pearl and how far from you it lands.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the name.", 1, 0.5, 3, 0.1, "").min(0.1).under(names);
    private final BoolSetting stasis = new BoolSetting("Stasis pearls",
        "Also marks pearls resting in stasis chambers with the name of their owner.", false);
    private final ColorSetting stasisColour = new ColorSetting("Stasis colour",
        "Colour of a pearl resting in a stasis chamber.", STASIS_HUE, false).under(stasis);
    private final BoolSetting guess = new BoolSetting("Guess owners",
        "Names a pearl after the player who joined or left the moment it came or went.", true);
    private final BoolSetting remember = new BoolSetting("Remember owners",
        "Remembers who owns each stasis pearl for your next visit.", true);

    private final List<Flight> flights = new ArrayList<>();
    private final List<Resting> resting = new ArrayList<>();

    public PearlTracker() {
        super("PearlTracker", "Shows where other players' ender pearls will land and who threw them.",
            Category.RENDER);
        addSettings(ownPearls, ignoreFriends, path);
        addSettings(style.settings());
        addSettings(names, scale, stasis, stasisColour, guess, remember);
        searchTags("ender pearl", "pearl", "landing", "stasis", "pearl owner");
    }

    @Override
    public String getSuffix() {
        return count(flights.size());
    }

    // Read by PearlOwners through util/Modules.
    public boolean guessesOwners() {
        return guess.isOn();
    }

    public boolean remembersOwners() {
        return remember.isOn();
    }

    @Override
    protected void onDisable() {
        flights.clear();
        resting.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        flights.clear();
        resting.clear();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || !pearl.isAlive()) {
                continue;
            }
            if (pearl.getOwner() == mc.player && !ownPearls.isOn()) {
                continue;
            }
            String thrower = EntityUtil.throwerName(pearl);
            if (ignoreFriends.isOn() && OfflineClient.INSTANCE.getFriendManager().isFriend(thrower)) {
                continue;
            }
            // A bubble column carries a pearl off its usual path.
            if (ProjectileUtil.inBubbles(pearl)) {
                if (stasis.isOn() && ProjectileUtil.inStasis(pearl)) {
                    resting.add(new Resting(pearl, thrower));
                }
                continue;
            }
            Path rest = ProjectilePath.of(pearl, FLIGHT_TICKS);
            flights.add(new Flight(pearl, thrower, rest.points(), landingOf(rest)));
        }
    }

    // A pearl that runs out of flight or falls out of the world never lands.
    private static Vec3 landingOf(Path rest) {
        if (rest.type() == HitResult.Type.MISS) {
            return null;
        }
        return rest.points().getLast();
    }

    // The box the thrower stands in when the pearl brings them down.
    private static AABB arrival(Vec3 landing) {
        return EntityTypes.PLAYER.getDimensions().makeBoundingBox(landing);
    }

    private static AABB restingBox(ThrownEnderpearl pearl, float partialTicks) {
        return EntityUtil.lerpedBox(pearl, partialTicks).inflate(RESTING_MARGIN);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        for (Flight flight : flights) {
            if (path.isOn()) {
                Vec3 from = flight.pearl().getPosition(event.getPartialTicks());
                for (int i = 1; i < flight.points().size(); i++) {
                    Vec3 to = flight.points().get(i);
                    batch.line(from, to, style.lineColor(), true);
                    from = to;
                }
            }
            if (flight.landing() != null) {
                style.draw(batch, arrival(flight.landing()), true);
            }
        }
        for (Resting pearl : resting) {
            style.draw(batch, restingBox(pearl.pearl(), event.getPartialTicks()), stasisColour.getColor(), true);
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!names.isOn() || flights.isEmpty() && resting.isEmpty() || !inGame() || !WorldToScreen.update()) {
            return;
        }
        for (Flight flight : flights) {
            Vec3 anchor = flight.landing() == null
                ? flight.pearl().getPosition(event.getPartialTicks())
                : flight.landing().add(0, arrival(flight.landing()).getYsize() + LABEL_LIFT, 0);
            String distance = flight.landing() == null ? ""
                : " " + Math.round(mc.player.position().distanceTo(flight.landing())) + "m";
            label(event, anchor, flight.thrower(), distance);
        }
        for (Resting pearl : resting) {
            AABB box = restingBox(pearl.pearl(), event.getPartialTicks());
            label(event, box.getBottomCenter().add(0, box.getYsize() + LABEL_LIFT, 0), pearl.owner(), "");
        }
    }

    private void label(Render2DEvent event, Vec3 anchor, String name, String detail) {
        Vec3 screen = WorldToScreen.project(anchor);
        if (screen == null) {
            return;
        }
        RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
            List.of(EntityUtil.displayName(name), detail), List.of(NAME_COLOUR, RenderUtil.MUTED_TEXT));
    }
}
