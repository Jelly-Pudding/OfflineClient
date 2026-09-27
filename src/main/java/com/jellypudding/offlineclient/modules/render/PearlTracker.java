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
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ProjectilePath;
import com.jellypudding.offlineclient.util.ProjectilePath.Path;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// The thrower is read as the pearl appears. The client forgets who threw it once the
// thrower is out of sight. The box at the landing is the spot the thrower arrives at.
public final class PearlTracker extends Module {

    // Who threw a pearl as it was first seen.
    private record Thrower(String name, boolean own) {
    }

    // A pearl in the air and the rest of its flight. The landing is null until one is found.
    private record Flight(ThrownEnderpearl pearl, Thrower thrower, List<Vec3> points, Vec3 landing) {
    }

    private static final int MAX_TRACKED = 256;

    // Half a minute of flight. A pearl still up after that gets no landing.
    private static final int FLIGHT_TICKS = 600;

    // The label floats this far over the top of the landing box.
    private static final double LABEL_LIFT = 0.3;

    private static final int NAME_COLOUR = 0xFFFFFFFF;

    private final BoolSetting ownPearls = new BoolSetting("Own pearls",
        "Also track the pearls you throw.", false);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Leaves out the pearls your friends throw.", false);
    private final BoolSetting path = new BoolSetting("Path",
        "Draws the rest of each flight as a line.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 280f);
    private final BoolSetting names = new BoolSetting("Names",
        "Shows who threw each pearl and how far from you it lands.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the name.", 1, 0.5, 3, 0.1, "").min(0.1).under(names);

    private final Map<Integer, Thrower> throwers = new BoundedMap<>(MAX_TRACKED);
    private final List<Flight> flights = new ArrayList<>();
    private final WorldWatch world = new WorldWatch();

    public PearlTracker() {
        super("PearlTracker", "Shows where other players' ender pearls will land and who threw them.",
            Category.RENDER);
        addSettings(ownPearls, ignoreFriends, path);
        addSettings(style.settings());
        addSettings(names, scale);
        searchTags("ender pearl", "pearl", "landing");
    }

    @Override
    public String getSuffix() {
        return count(flights.size());
    }

    @Override
    protected void onDisable() {
        throwers.clear();
        flights.clear();
        world.forget();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        flights.clear();
        if (!inGame()) {
            return;
        }
        // Entity ids start again in a new world.
        if (world.changed()) {
            throwers.clear();
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || !pearl.isAlive() || onBubbles(pearl)) {
                continue;
            }
            Thrower thrower = throwers.computeIfAbsent(pearl.getId(), id -> throwerOf(pearl));
            if (thrower.own() && !ownPearls.isOn()) {
                continue;
            }
            if (ignoreFriends.isOn() && OfflineClient.INSTANCE.getFriendManager().isFriend(thrower.name())) {
                continue;
            }
            Path rest = ProjectilePath.of(pearl, FLIGHT_TICKS);
            flights.add(new Flight(pearl, thrower, rest.points(), landingOf(rest)));
        }
    }

    private Thrower throwerOf(ThrownEnderpearl pearl) {
        return new Thrower(EntityUtil.throwerName(pearl), pearl.getOwner() == mc.player);
    }

    // A bubble column holds a pearl up in a stasis chamber. It never lands.
    private static boolean onBubbles(ThrownEnderpearl pearl) {
        BlockPos pos = pearl.blockPosition();
        return BlockUtil.state(pos).is(Blocks.BUBBLE_COLUMN) || BlockUtil.state(pos.below()).is(Blocks.BUBBLE_COLUMN);
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
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!names.isOn() || flights.isEmpty() || !inGame() || !WorldToScreen.update()) {
            return;
        }
        for (Flight flight : flights) {
            Vec3 anchor = flight.landing() == null
                ? flight.pearl().getPosition(event.getPartialTicks())
                : flight.landing().add(0, arrival(flight.landing()).getYsize() + LABEL_LIFT, 0);
            Vec3 screen = WorldToScreen.project(anchor);
            if (screen == null) {
                continue;
            }
            String distance = flight.landing() == null ? ""
                : " " + Math.round(mc.player.position().distanceTo(flight.landing())) + "m";
            RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                List.of(EntityUtil.displayName(flight.thrower().name()), distance),
                List.of(NAME_COLOUR, RenderUtil.MUTED_TEXT));
        }
    }
}
