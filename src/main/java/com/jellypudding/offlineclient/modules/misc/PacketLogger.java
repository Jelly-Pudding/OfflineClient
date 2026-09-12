package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.PacketNames;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

// Prints or files every packet of the kinds you pick.
public final class PacketLogger extends Module {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final int MEGABYTE = 1024 * 1024;

    // Lines held whilst the game thread is busy. Anything past this is dropped.
    private static final int MAX_PENDING = 8192;

    private final ChoiceListSetting incoming = new ChoiceListSetting("Incoming",
        "Kinds of packet from the server to log. Click to pick them.", PacketNames::incoming);
    private final ChoiceListSetting outgoing = new ChoiceListSetting("Outgoing",
        "Kinds of packet to the server to log. Click to pick them.", PacketNames::outgoing);
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
        "Writes each line into offlineclient/packet-logs inside your game folder.", false);
    private final NumberSetting flushSeconds = new NumberSetting("Flush interval",
        "Seconds between one write to the file and the next.", 1, 1, 10, 1, "s").min(1).max(60)
        .under(toFile);
    private final NumberSetting maxFileSize = new NumberSetting("Max file size",
        "A new file is started once this one gets bigger.", 10, 1, 100, 1, " MB").min(1).max(1000)
        .under(toFile);
    private final NumberSetting maxTotalSize = new NumberSetting("Max total size",
        "The oldest files are deleted once the folder gets bigger.", 50, 1, 500, 1, " MB")
        .min(1).max(10000)
        .under(toFile);

    // Filled from the netty thread and drained on the main thread.
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger held = new AtomicInteger();
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    private final List<String> buffer = new ArrayList<>();
    private Path file;
    private long written;
    private int flushTimer;

    public PacketLogger() {
        super("PacketLogger", "Writes the packets you pick to chat or to a file.", Category.MISC);
        addSettings(incoming, outgoing, timestamp, showData, showCount, showSummary, toChat,
            toFile, flushSeconds, maxFileSize, maxTotalSize);
        searchTags("packet log", "network log", "sniffer");
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
        pending.clear();
        held.set(0);
        counts.clear();
        buffer.clear();
        file = null;
        written = 0;
        flushTimer = 0;
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
    }

    private void summarise() {
        List<Map.Entry<String, AtomicInteger>> rows = new ArrayList<>(counts.entrySet());
        rows.sort(Comparator.comparingInt((Map.Entry<String, AtomicInteger> row)
            -> row.getValue().get()).reversed());
        for (Map.Entry<String, AtomicInteger> row : rows) {
            ChatUtil.message("§b" + row.getValue().get() + " §7" + row.getKey());
        }
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        record(event.getPacket(), incoming, "in");
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        record(event.getPacket(), outgoing, "out");
    }

    // Fired on the netty thread. Only the line is built here.
    private void record(Packet<?> packet, ChoiceListSetting picked, String direction) {
        String name = packet.type().id().toString();
        if (!picked.contains(name)) {
            return;
        }
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

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        drain();
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
        if (++flushTimer < flushSeconds.getInt() * 20) {
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
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append(System.lineSeparator());
        }
        try {
            Path folder = folder();
            Files.createDirectories(folder);
            if (file == null || written > maxFileSize.getInt() * (long) MEGABYTE) {
                file = nextFile(folder);
                written = 0;
                announce(file);
            }
            byte[] bytes = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            written += bytes.length;
            prune(folder);
        } catch (IOException | UncheckedIOException error) {
            OfflineClient.LOG.error("Failed to write the packet log", error);
        }
    }

    // Says which file is being written. Clicking the line opens the folder.
    private static void announce(Path file) {
        Path game = OfflineClient.MC.gameDirectory.toPath();
        Component where = Component.literal(game.relativize(file).toString().replace('\\', '/'))
            .withStyle(style -> style.withColor(ChatFormatting.WHITE)
                .withClickEvent(new ClickEvent.OpenFile(file.getParent())));
        ChatUtil.component(Component.literal("§bPacketLogger §7is writing to ").append(where));
    }

    private static Path folder() {
        return OfflineClient.MC.gameDirectory.toPath().resolve("offlineclient")
            .resolve("packet-logs");
    }

    private static Path nextFile(Path folder) throws IOException {
        String stem = "packets-" + LocalDate.now();
        for (int i = 1; ; i++) {
            Path candidate = folder.resolve(stem + "-" + i + ".log");
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    // The oldest files go once the folder outgrows the allowance.
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
