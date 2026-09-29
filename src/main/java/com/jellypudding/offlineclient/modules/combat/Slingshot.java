package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.HeldShot;
import com.jellypudding.offlineclient.util.Hop;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// The server adds the move of the last position packet before a shot to its speed. A hop
// back and a hop home before the shot or a hop ahead with it turn that into a launch. On
// Paper the hops and the shot fit one tick.
public final class Slingshot extends Module {

    public enum Way { BACK, AHEAD }

    // Hops are tried this much shorter each time from the longest down.
    private static final double SEARCH_STEP = 0.5;

    // A hop ahead stops this far short of anything alive in the way. The shot would
    // otherwise start past it.
    private static final double CLEARANCE = 1;

    private final RegistryListSetting<Item> items = HeldShot.items("Which shots are launched.",
        List.of(Items.BOW));
    private final EnumSetting<Way> way = new EnumSetting<>("Hop", "Which way the hop goes.", Way.BACK)
        .describe(Way.BACK, "Hops back and fires as you land home. The shot starts where you stand.")
        .describe(Way.AHEAD, "Hops ahead and fires from there before coming home. It stops short of"
            + " anything alive in the way.");
    private final NumberSetting distance = new NumberSetting("Distance",
        "How far the hop goes. Each block adds a block a tick to the shot. Vanilla allows about twenty"
            + " and Paper about a hundred and ninety.", 200, 1, 200, 0.5, " blocks").min(SEARCH_STEP);
    private final NumberSetting least = new NumberSetting("Least distance",
        "A shorter hop is not worth it and the shot goes out as it was.", 2, 0.5, 10, 0.5, " blocks")
        .min(SEARCH_STEP);
    private final BoolSetting otherWays = new BoolSetting("Other ways",
        "When the way along your aim is blocked it tries a level hop and then the other way round.", true);
    private final BoolSetting report = new BoolSetting("Report",
        "Says in chat how much faster each shot left.", false);

    private final ChatWarning warning = new ChatWarning();

    // The shot kept back until its hop reaches the server.
    private HeldShot held;

    public Slingshot() {
        super("Slingshot", "Makes your arrows and throws fly much faster.", Category.COMBAT);
        addSettings(items, way, distance, least, otherWays, report);
        searchTags("arrow damage", "bow boost", "arrow speed", "pearl boost");
    }

    @Override
    public String getSuffix() {
        return way.getValueString();
    }

    @Override
    protected void onEnable() {
        warning.clear();
    }

    // Runs after SkyDrop. A shot it carries over a target is left to it.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || !inGame()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (held != null) {
            if (held.gather(packet)) {
                event.cancel();
            }
            return;
        }
        HeldShot shot = Hop.travelling() ? null : HeldShot.of(packet, items);
        if (shot == null) {
            return;
        }
        Hop.Result trouble = Hop.plan().problem();
        Hop.Plan launch = trouble == null ? launch(shot) : null;
        if (launch == null) {
            warning.say(trouble == null ? "There is no room to hop along your aim." : trouble.problem());
            return;
        }
        warning.clear();
        event.cancel();
        held = shot;
        launch.go(this::landed);
    }

    // The chosen way first and then the level and the other ways where allowed. Null when
    // none has room for a hop.
    private Hop.Plan launch(HeldShot shot) {
        Vec3 aim = shot.aim();
        List<Way> ways = new ArrayList<>(List.of(way.getValue()));
        List<Vec3> lines = new ArrayList<>(List.of(aim));
        if (otherWays.isOn()) {
            ways.add(way.is(Way.BACK) ? Way.AHEAD : Way.BACK);
            Vec3 level = aim.multiply(1, 0, 1).normalize();
            if (aim.y != 0 && level.lengthSqr() > 0) {
                lines.add(level);
            }
        }
        for (Way hop : ways) {
            for (Vec3 line : lines) {
                Hop.Plan plan = furthest(shot, hop, line);
                if (plan != null) {
                    return plan;
                }
            }
        }
        return null;
    }

    // The longest hop along the line that the server takes both ways.
    private Hop.Plan furthest(HeldShot shot, Way hop, Vec3 line) {
        Hop.Plan plan = Hop.plan();
        Entity mover = Hop.mover(mc.player);
        Vec3 home = mover.position();
        double longest = Math.min(distance.getValue(), plan.longestHop());
        if (hop == Way.AHEAD) {
            longest = Math.min(longest, clearAhead(mover, line, longest));
        }
        for (double length = longest; length >= least.getValue(); length -= SEARCH_STEP) {
            double speed = length;
            Vec3 out = home.add(line.scale(hop == Way.BACK ? -length : length));
            boolean taken = plan.attempt(steps -> {
                if (hop == Way.BACK) {
                    steps.hopTo(out).hopTo(home).then(() -> fire(shot, speed));
                } else {
                    steps.hopTo(out).then(() -> fire(shot, speed)).hopTo(home);
                }
            });
            if (taken) {
                return plan;
            }
        }
        return null;
    }

    // How far the mover goes along the line before it runs into anything alive.
    private double clearAhead(Entity mover, Vec3 line, double longest) {
        AABB box = mover.getBoundingBox();
        Vec3 centre = box.getCenter();
        Vec3 end = centre.add(line.scale(longest));
        double clear = longest;
        List<Entity> inTheWay = mc.level.getEntities(mover, box.expandTowards(line.scale(longest)),
            other -> other instanceof LivingEntity && other.isAlive() && other.getRootVehicle() != mover);
        for (Entity other : inTheWay) {
            // The centre's path meets the other box grown by half the mover's size where the boxes touch.
            Optional<Vec3> touch = other.getBoundingBox()
                .inflate(box.getXsize() / 2, box.getYsize() / 2, box.getZsize() / 2).clip(centre, end);
            if (touch.isPresent()) {
                clear = Math.min(clear, centre.distanceTo(touch.get()) - CLEARANCE);
            }
        }
        return clear;
    }

    // Runs straight after the hop that makes the shot fast has reached the server. The
    // shot leaves that many blocks a tick faster.
    private void fire(HeldShot shot, double speed) {
        shot.fire();
        if (report.isOn()) {
            ChatUtil.message(String.format(Locale.ROOT, "The shot left %.1f blocks a tick faster.", speed));
        }
    }

    // A trip cut short before the shot lets it go as it was. A player who left takes
    // nothing along.
    private void landed(Hop.Result result) {
        if (result != Hop.Result.LEFT) {
            held.finish();
        }
        held = null;
        if (result != Hop.Result.MOVED) {
            warning.say(result.problem());
        }
    }
}
