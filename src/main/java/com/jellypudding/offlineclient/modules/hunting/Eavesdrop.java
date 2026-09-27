package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FarShapes;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Bearing;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.PositionHistory;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

// A global event reaches every player on the server. Vanilla sends one within 32 blocks
// at its true block and one further away 32 blocks from you in its direction. Paper sends
// the true block within the server's view distance and otherwise a point that far away
// across the ground at the event's own height. Vanilla puts an event in another dimension
// on your own block. Paper measures it from where you stand as if it were beside you.
public final class Eavesdrop extends Module {

    // The global events worth hearing with the level event id the server sends. A dragon
    // only dies in the End and a survival portal only opens in the Overworld.
    private enum Heard {
        WITHER(LevelEvent.SOUND_WITHER_BOSS_SPAWN, "Wither", "A wither was spawned", null, null),
        DRAGON(LevelEvent.SOUND_DRAGON_DEATH, "Dragon death", "The ender dragon died",
            Level.END, "in the End"),
        PORTAL(LevelEvent.SOUND_END_PORTAL_SPAWN, "End portal", "An end portal was opened",
            Level.OVERWORLD, "in the Overworld");

        private final int id;
        private final String label;
        private final String news;
        // Null for an event that can happen in any dimension.
        private final ResourceKey<Level> home;
        private final String away;

        Heard(int id, String label, String news, ResourceKey<Level> home, String away) {
            this.id = id;
            this.label = label;
            this.news = news;
            this.home = home;
            this.away = away;
        }

        private static Heard of(int id) {
            for (Heard heard : values()) {
                if (heard.id == id) {
                    return heard;
                }
            }
            return null;
        }
    }

    // A spot in the world or a line from where you stood when it was only a direction.
    private record Mark(String label, Vec3 at, Bearing bearing, long made) {
    }

    // Vanilla sends a far event this far from you.
    private static final double VANILLA_RELAY = 32;

    // Room for rounding to a block and for how well the server knew where you stood.
    private static final double RELAY_SLACK = 2.5;

    // An event on any block you stood on in the last second landed on your own spot.
    private static final double OWN_SPOT = 1;
    private static final int OWN_TICKS = 20;

    // Nearer than this across the ground there is no telling which way it lies.
    private static final double LEVEL_MIN = 1;

    // Where along a direction line its name sits.
    private static final double LABEL_ALONG = 32;

    private static final double BEAM = 64;

    private static final double LABEL_LIFT = 1.5;

    private static final int MAX_MARKS = 64;

    private final BoolSetting withers = new BoolSetting("Wither spawns",
        "Reports a wither being spawned anywhere on the server. Paper sends one from another"
            + " dimension as if it were in yours.", true);
    private final BoolSetting dragons = new BoolSetting("Dragon deaths",
        "Reports the ender dragon dying.", true);
    private final BoolSetting portals = new BoolSetting("End portals",
        "Reports an end portal being opened which gives away a stronghold.", true);
    private final BoolSetting elsewhere = new BoolSetting("Other dimensions",
        "Also reports events in a dimension you are not in. They carry no place.", true);
    private final BoolSetting chat = new BoolSetting("Chat",
        "Posts each event in chat.", true);
    private final NumberSetting markTime = new NumberSetting("Mark time",
        "How long each mark stays in the world.", 10, 1, 60, 1, " minutes").min(0.1);
    private final ColorSetting color = new ColorSetting("Colour",
        "Colour of event marks and bearing lines.", 280, false);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1);

    private final List<Mark> marks = new ArrayList<>();
    // Filled from the network thread and handled on the next tick.
    private final Queue<ClientboundLevelEventPacket> heard = new ConcurrentLinkedQueue<>();
    private final PositionHistory history = new PositionHistory();
    private final WorldWatch world = new WorldWatch();

    public Eavesdrop() {
        super("Eavesdrop", "Points to wither spawns and end portals and dragon deaths anywhere on the server.",
            Category.HUNTING);
        addSettings(withers, dragons, portals, elsewhere, chat, markTime, color, scale);
        searchTags("wither", "stronghold", "sound", "world event", "coords");
    }

    @Override
    public String getSuffix() {
        return count(marks.size());
    }

    @Override
    protected void onEnable() {
        clear();
        if (inGame()) {
            world.accept();
        }
    }

    @Override
    protected void onDisable() {
        clear();
        world.forget();
    }

    private void clear() {
        marks.clear();
        heard.clear();
        history.clear();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundLevelEventPacket packet && packet.isGlobalEvent()) {
            heard.add(packet);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            clear();
        }
        history.record(mc.player.position());
        ClientboundLevelEventPacket packet;
        while ((packet = heard.poll()) != null) {
            hear(packet);
        }
        long oldest = System.currentTimeMillis()
            - Math.round(markTime.getValue() * TimeUnit.MINUTES.toMillis(1));
        marks.removeIf(mark -> mark.made() < oldest);
    }

    private boolean watching(Heard kind) {
        return switch (kind) {
            case WITHER -> withers.isOn();
            case DRAGON -> dragons.isOn();
            case PORTAL -> portals.isOn();
        };
    }

    private void hear(ClientboundLevelEventPacket packet) {
        Heard kind = Heard.of(packet.getType());
        Vec3 me = history.asServerSaw();
        if (kind == null || !watching(kind) || me == null) {
            return;
        }
        BlockPos pos = packet.getPos();
        Vec3 heardAt = Vec3.atCenterOf(pos);
        if (inAnotherDimension(kind, heardAt)) {
            if (elsewhere.isOn()) {
                say("§f" + kind.news + " §7" + (kind.away != null ? kind.away : "in another dimension") + ".");
            }
            return;
        }
        double across = Math.hypot(heardAt.x - me.x, heardAt.z - me.z);
        // A far event lands at a fixed distance. Anything nearer is its true block.
        boolean paper = ServerInfo.runsPaper();
        boolean relayed = paper ? across >= serverViewBlocks() - RELAY_SLACK
            : heardAt.distanceTo(me) >= VANILLA_RELAY - RELAY_SLACK;
        if (!relayed) {
            add(new Mark(kind.label, heardAt, null, System.currentTimeMillis()));
            say("§f" + kind.news + " §7at §f" + BlockUtil.text(pos) + "§7.");
            return;
        }
        String from = BlockUtil.text(BlockPos.containing(me));
        if (across < LEVEL_MIN) {
            say("§f" + kind.news + " §7straight above or below §f" + from + "§7.");
            return;
        }
        Bearing bearing = Bearing.between(me, heardAt);
        add(new Mark(kind.label, me, bearing, System.currentTimeMillis()));
        // Paper keeps the event's own height on the far point.
        String height = paper ? " §7at height §f" + pos.getY() : "";
        say("§f" + kind.news + " §7to the §f" + bearing.compass() + " §7of §f" + from + " §7along yaw §f"
            + String.format(Locale.ROOT, "%.1f", bearing.degrees()) + height + "§7.");
    }

    // A dragon or a portal is known to be elsewhere by the dimension you are in. A wither
    // elsewhere lands on a block you stood on. The server may have seen you a moment ago.
    private boolean inAnotherDimension(Heard kind, Vec3 heardAt) {
        if (kind.home != null) {
            return !mc.level.dimension().equals(kind.home);
        }
        return history.wasNear(heardAt, OWN_SPOT, OWN_TICKS);
    }

    // How far the server sends chunks to you. Paper relays a far event at its own view
    // distance and that is never nearer.
    private int serverViewBlocks() {
        return SectionPos.sectionToBlockCoord(mc.player.connection.serverChunkRadius);
    }

    private void add(Mark mark) {
        marks.add(mark);
        if (marks.size() > MAX_MARKS) {
            marks.removeFirst();
        }
    }

    private void say(String text) {
        if (chat.isOn()) {
            ChatUtil.message("§bEavesdrop " + text);
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || marks.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        int shade = color.getColor();
        for (Mark mark : marks) {
            Vec3 at = mark.at();
            batch.outlineBox(FarShapes.pullIn(DrawBatch.blockBox(BlockPos.containing(at))), shade, true);
            Bearing bearing = mark.bearing();
            if (bearing == null) {
                FarShapes.line(batch, at, at.add(0, BEAM, 0), shade);
            } else {
                FarShapes.ray(batch, bearing, at.y, shade);
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || marks.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        Vec3 camera = WorldToScreen.cameraPos();
        for (Mark mark : marks) {
            Bearing bearing = mark.bearing();
            Vec3 spot = bearing == null ? mark.at().add(0, LABEL_LIFT, 0)
                : bearing.along(LABEL_ALONG, mark.at().y + LABEL_LIFT);
            Vec3 screen = WorldToScreen.project(spot);
            if (screen == null) {
                continue;
            }
            String detail = bearing == null
                ? String.format(Locale.ROOT, " %dm", Math.round(camera.distanceTo(mark.at())))
                : " " + bearing.compass();
            RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                List.of(mark.label(), detail), List.of(color.getColor(), RenderUtil.MUTED_TEXT));
        }
    }
}
