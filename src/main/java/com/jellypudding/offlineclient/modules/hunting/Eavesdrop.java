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
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// A global event goes to every player on the server. One within 32 blocks arrives at
// its true block. One further away arrives 32 blocks from you in its direction and
// one in another dimension arrives on your own block.
public final class Eavesdrop extends Module {

    // The global events worth hearing with the level event id the server sends.
    private enum Heard {
        WITHER(LevelEvent.SOUND_WITHER_BOSS_SPAWN, "Wither", "A wither was spawned"),
        DRAGON(LevelEvent.SOUND_DRAGON_DEATH, "Dragon death", "The ender dragon died"),
        PORTAL(LevelEvent.SOUND_END_PORTAL_SPAWN, "End portal", "An end portal was opened");

        private final int id;
        private final String label;
        private final String news;

        Heard(int id, String label, String news) {
            this.id = id;
            this.label = label;
            this.news = news;
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

    // A global event this close arrives at its true block.
    private static final double TRUE_RANGE = 32;

    // Room for rounding to a block and for how well your own spot is known.
    private static final double SLACK = 1.5;

    // An event in another dimension arrives on your own block. Where the server saw you
    // is only known to a block or two whilst you fly fast.
    private static final double OWN_SPOT = 4;

    // Nearer than this across the ground there is no telling which way it lies.
    private static final double LEVEL_MIN = 1;

    private static final double LINE_LENGTH = 4096;

    private static final double LINE_LIFT = 0.1;

    private static final double BEAM = 64;

    private static final double LABEL_LIFT = 1.5;

    private static final int MAX_MARKS = 64;

    private static final long MINUTE_MS = 60_000;

    private final BoolSetting withers = new BoolSetting("Wither spawns",
        "Reports a wither being spawned anywhere on the server.", true);
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
        long oldest = System.currentTimeMillis() - Math.round(markTime.getValue() * MINUTE_MS);
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
        double away = heardAt.distanceTo(me);
        if (away < OWN_SPOT) {
            if (elsewhere.isOn()) {
                say("§f" + kind.news + " §7in another dimension.");
            }
            return;
        }
        if (away < TRUE_RANGE - SLACK) {
            add(new Mark(kind.label, heardAt, null, System.currentTimeMillis()));
            say("§f" + kind.news + " §7at §f" + BlockUtil.text(pos) + "§7.");
            return;
        }
        String from = BlockUtil.text(BlockPos.containing(me));
        if (Math.hypot(heardAt.x - me.x, heardAt.z - me.z) < LEVEL_MIN) {
            say("§f" + kind.news + " §7straight above or below §f" + from + "§7.");
            return;
        }
        Bearing bearing = Bearing.between(me, heardAt);
        add(new Mark(kind.label, me, bearing, System.currentTimeMillis()));
        say("§f" + kind.news + " §7to the §f" + bearing.compass() + " §7of §f" + from + " §7along yaw §f"
            + String.format(Locale.ROOT, "%.1f", bearing.degrees()) + "§7.");
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
                double y = at.y + LINE_LIFT;
                FarShapes.line(batch, bearing.along(0, y), bearing.along(LINE_LENGTH, y), shade);
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
            // A direction is labelled where the server put the sound for you.
            Vec3 spot = bearing == null ? mark.at().add(0, LABEL_LIFT, 0)
                : bearing.along(TRUE_RANGE, mark.at().y + LABEL_LIFT);
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
