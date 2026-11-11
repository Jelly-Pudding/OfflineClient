package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSentEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.PacketNames;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.TextCut;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

// Prints or files every packet of the kinds you pick. The kick report keeps every packet
// both ways for a few seconds and files them when the server drops you.
public final class PacketLogger extends Module {

    // One line of the kick report and when it happened on the nano clock.
    private record Recent(long nanos, String line) {
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    // A packet line in the kick report stops here. A chunk sent whole would fill the file.
    private static final int REPORT_LINE = 400;

    // Lines the kick report holds at most however busy the window.
    private static final int MAX_RECENT = 50_000;

    private static final int MEGABYTE = 1024 * 1024;

    // Lines held whilst the game thread is busy. Anything past this is dropped.
    private static final int MAX_PENDING = 8192;

    private final ChoiceListSetting incoming = new ChoiceListSetting("Incoming",
        "Kinds of packet from the server to log. Click to pick them.", PacketNames::incoming)
        .onChange(this::resolve);
    private final ChoiceListSetting outgoing = new ChoiceListSetting("Outgoing",
        "Kinds of packet to the server to log. Click to pick them.", PacketNames::outgoing)
        .onChange(this::resolve);
    private final BoolSetting timestamp = new BoolSetting("Timestamp",
        "Puts the time in front of every line.", true);
    private final BoolSetting showData = new BoolSetting("Show data",
        "Adds the contents of the packet to the line.", false);
    private final BoolSetting showCount = new BoolSetting("Show count",
        "Adds the running count for that kind to the line.", true);
    private final BoolSetting showSummary = new BoolSetting("Show summary",
        "Prints a count for every kind when the module is switched off.", true);
    private final BoolSetting toChat = new BoolSetting("Log to chat",
        "Prints each line into your chat.", true);
    private final BoolSetting toFile = new BoolSetting("Log to file",
        "Writes each line into a log file and names the file in chat.", false);
    private final NumberSetting flushSeconds = new NumberSetting("Flush interval",
        "Seconds between one write to the file and the next.", 1, 1, 10, 1, "s")
        .under(toFile);
    private final NumberSetting maxFileSize = new NumberSetting("Max file size",
        "A new file is started once this one gets bigger.", 10, 1, 100, 1, " MB")
        .under(toFile);
    private final NumberSetting maxTotalSize = new NumberSetting("Max total size",
        "The oldest files are deleted once the folder gets bigger.", 50, 1, 500, 1, " MB")
        .under(toFile);
    private final BoolSetting kickReport = new BoolSetting("Kick report",
        "Writes the last few seconds of packets and your movement to a file when the server drops you.", false);
    private final NumberSetting reportWindow = new NumberSetting("Report window",
        "Seconds of packets and movement the report keeps.", 5, 1, 30, 1, "s")
        .under(kickReport);

    // The picked names resolved to types. Read from the netty thread and replaced whole.
    private volatile Set<PacketType<?>> logIncoming = Set.of();
    private volatile Set<PacketType<?>> logOutgoing = Set.of();

    // Filled from the netty thread and drained on the main thread.
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger held = new AtomicInteger();
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    private final List<String> buffer = new ArrayList<>();
    private Path file;
    private long written;
    private int flushTimer;

    // Filled from the netty thread and the game thread and read when the server drops you.
    private final ConcurrentLinkedDeque<Recent> recent = new ConcurrentLinkedDeque<>();
    private final AtomicInteger recentCount = new AtomicInteger();
    // When the last client tick with a player in the world ran on the nano clock. A report needs
    // one inside its window.
    private long lastTick = Long.MIN_VALUE;
    // The server you are on. It is gone by the time the disconnected screen opens.
    private String server;

    public PacketLogger() {
        super("PacketLogger", "Writes the packets you pick to chat or to a file.", Category.MISC);
        addSettings(incoming, outgoing, timestamp, showData, showCount, showSummary, toChat,
            toFile, flushSeconds, maxFileSize, maxTotalSize, kickReport, reportWindow);
        searchTags("packet log", "network log", "sniffer", "kick", "forensics");
    }

    @Override
    public String getSuffix() {
        int total = 0;
        for (AtomicInteger count : counts.values()) {
            total += count.get();
        }
        return count(total);
    }

    @Override
    protected void onEnable() {
        resolve();
        pending.clear();
        held.set(0);
        counts.clear();
        buffer.clear();
        file = null;
        written = 0;
        flushTimer = 0;
        forgetRecent();
    }

    @Override
    protected void onDisable() {
        drain();
        flush();
        if (showSummary.isOn()) {
            summarise();
        }
        pending.clear();
        held.set(0);
        counts.clear();
        buffer.clear();
        file = null;
        forgetRecent();
    }

    private void summarise() {
        List<Map.Entry<String, AtomicInteger>> rows = new ArrayList<>(counts.entrySet());
        rows.sort(Comparator.comparingInt((Map.Entry<String, AtomicInteger> row)
            -> row.getValue().get()).reversed());
        for (Map.Entry<String, AtomicInteger> row : rows) {
            ChatUtil.message("§b" + row.getValue().get() + " §7" + row.getKey());
        }
    }

    private void resolve() {
        logIncoming = PacketNames.incomingTypes(incoming.getValue());
        logOutgoing = PacketNames.outgoingTypes(outgoing.getValue());
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        record(event.getPacket(), logIncoming, "in");
    }

    // Right behind the write. Only what really left is listed and in the order it left.
    @Subscribe
    private void onPacketSent(PacketSentEvent event) {
        record(event.getPacket(), logOutgoing, "out");
        remember("out", event.getPacket());
    }

    // Before any module can drop a packet that arrived.
    @Subscribe(priority = Subscribe.FIRST)
    private void onPacketArrived(PacketReceiveEvent event) {
        remember("in", event.getPacket());
    }

    // Where you were and how you moved on each tick of the window.
    @Subscribe
    private void onTick(TickEvent event) {
        LocalPlayer player = mc.player;
        if (!kickReport.isOn() || player == null) {
            return;
        }
        Vec3 motion = player.getDeltaMovement();
        remember(String.format(Locale.ROOT, "tick at %.3f %.3f %.3f moving %.3f %.3f %.3f ground %b fall %.2f"
                + " ping %d floating %d", player.getX(), player.getY(), player.getZ(), motion.x, motion.y, motion.z,
            player.onGround(), player.fallDistance, ServerInfo.ping(), MoveGate.floatingTicks(false)));
    }

    private void remember(String direction, Packet<?> packet) {
        if (!kickReport.isOn()) {
            return;
        }
        remember(direction + " " + packet.type().id() + " " + TextCut.quote(String.valueOf(packet), REPORT_LINE));
    }

    // Lines older than the window go as each new one comes.
    private void remember(String line) {
        long now = System.nanoTime();
        recent.addLast(new Recent(now, line));
        long oldest = windowStart(now);
        Recent first;
        while ((first = recent.peekFirst()) != null
            && (first.nanos() < oldest || recentCount.get() >= MAX_RECENT)) {
            if (recent.pollFirst() != null) {
                recentCount.decrementAndGet();
            }
        }
        recentCount.incrementAndGet();
    }

    private long windowStart(long now) {
        return now - (long) (reportWindow.getValue() * NANOS_PER_SECOND);
    }

    private void forgetRecent() {
        recent.clear();
        recentCount.set(0);
        lastTick = Long.MIN_VALUE;
    }

    // Called as the disconnected screen opens. A failed join or a refused retry has no tick in a world
    // inside the window and writes nothing.
    public void kickReport(Component reason) {
        if (!kickReport.isOn()) {
            return;
        }
        long now = System.nanoTime();
        long oldest = windowStart(now);
        if (lastTick < oldest) {
            forgetRecent();
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Kick report written " + DataFiles.timestamp());
        lines.add("Server " + (server == null ? "unknown" : server));
        lines.add("Reason " + ChatSender.plain(reason).replace('\n', ' '));
        lines.add("");
        for (Recent line : recent) {
            if (line.nanos() >= oldest) {
                lines.add(String.format(Locale.ROOT, "%+.3fs %s", (line.nanos() - now) / NANOS_PER_SECOND,
                    line.line()));
            }
        }
        forgetRecent();
        try {
            Path report = nextFile(folder(), "kick-" + LocalDate.now());
            if (DataFiles.appendLines(report, lines)) {
                SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.literal(getName()), Component.literal("Saved the kick report as " + report.getFileName()));
            }
        } catch (IOException | UncheckedIOException error) {
            OfflineClient.LOG.error("Failed to write the kick report", error);
        }
    }

    // Fired on the netty thread. Only the line is built here.
    private void record(Packet<?> packet, Set<PacketType<?>> picked, String direction) {
        if (!picked.contains(packet.type())) {
            return;
        }
        String name = packet.type().id().toString();
        int count = counts.computeIfAbsent(direction + " " + name, key -> new AtomicInteger())
            .incrementAndGet();
        StringBuilder line = new StringBuilder();
        if (timestamp.isOn()) {
            line.append('[').append(LocalTime.now().format(CLOCK)).append("] ");
        }
        line.append(direction).append(' ').append(name);
        if (showCount.isOn()) {
            line.append(" x").append(count);
        }
        if (showData.isOn()) {
            line.append(' ').append(packet);
        }
        if (held.incrementAndGet() > MAX_PENDING) {
            held.decrementAndGet();
            return;
        }
        pending.add(line.toString());
    }

    // Every client tick including loading screens where the player tick waits for chunks.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        drain();
        if (kickReport.isOn() && mc.player != null) {
            lastTick = System.nanoTime();
            server = ServerInfo.address();
        }
    }

    private void drain() {
        String line;
        while ((line = pending.poll()) != null) {
            held.decrementAndGet();
            if (toChat.isOn()) {
                ChatUtil.message("§7" + line);
            }
            if (toFile.isOn()) {
                buffer.add(line);
            }
        }
        if (buffer.isEmpty()) {
            flushTimer = 0;
            return;
        }
        if (++flushTimer < flushSeconds.getInt() * SharedConstants.TICKS_PER_SECOND) {
            return;
        }
        flush();
    }

    private void flush() {
        flushTimer = 0;
        if (buffer.isEmpty()) {
            return;
        }
        write(buffer);
        buffer.clear();
    }

    private void write(List<String> lines) {
        Path folder = folder();
        try {
            if (file == null || written > maxFileSize.getInt() * (long) MEGABYTE) {
                file = nextFile(folder, "packets-" + LocalDate.now());
                written = 0;
                announce(file);
            }
            if (!DataFiles.appendLines(file, lines)) {
                return;
            }
            written = Files.size(file);
            prune(folder);
        } catch (IOException | UncheckedIOException error) {
            OfflineClient.LOG.error("Failed to write the packet log", error);
        }
    }

    // Says which file is being written. Clicking the line opens the folder.
    private static void announce(Path file) {
        ChatUtil.component(Component.literal(ChatUtil.from("PacketLogger", "is writing to "))
            .append(ChatUtil.fileLink(file)));
    }

    private static Path folder() {
        return DataFiles.path("packet-logs");
    }

    private static Path nextFile(Path folder, String stem) throws IOException {
        for (int i = 1; ; i++) {
            Path candidate = folder.resolve(stem + "-" + i + ".log");
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private void prune(Path folder) throws IOException {
        long allowance = maxTotalSize.getInt() * (long) MEGABYTE;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        long total = 0;
        for (Path each : files) {
            total += Files.size(each);
        }
        if (total <= allowance) {
            return;
        }
        files.sort(Comparator.comparing(each -> {
            try {
                return Files.getLastModifiedTime(each);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }));
        for (Path each : files) {
            if (total <= allowance || each.equals(file)) {
                continue;
            }
            total -= Files.size(each);
            Files.deleteIfExists(each);
        }
    }
}
