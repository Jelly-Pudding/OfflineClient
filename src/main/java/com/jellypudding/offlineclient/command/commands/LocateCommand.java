package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.jellypudding.offlineclient.worldgen.SeedWorld;
import com.jellypudding.offlineclient.worldgen.SeedWorlds;
import com.jellypudding.offlineclient.worldgen.StructureSearch;
import com.jellypudding.offlineclient.worldgen.StructureSearch.Find;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

// Answers from the seed alone. The game's own world generation runs on a worker for the saved seed
// and says where the plain game puts a structure or a biome. A server that changed its world
// generation puts them elsewhere and the client cannot know.
public final class LocateCommand extends Command {

    private static final String BIOME = "biome";
    private static final String MARK = "mark";

    // What the game's own biome search uses. How far it looks and the steps it takes across and up.
    private static final int BIOME_REACH = 6400;
    private static final int BIOME_STEP = 32;
    private static final int BIOME_HEIGHT_STEP = 64;

    private static final long SLOW_MILLIS = 2000;

    @FunctionalInterface
    private interface Question {
        Runnable answer(SeedWorld world, BooleanSupplier stopped);
    }

    private final ServerWatch told = new ServerWatch();
    // Only the latest search answers.
    private volatile int asked;

    public LocateCommand() {
        super("locate", "Finds the nearest structure or biome the plain game makes from the saved seed.",
            "locate <structure> [n] or locate biome <biome>");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length == 0) {
            usage();
            return;
        }
        String verb = args[0].toLowerCase(Locale.ROOT);
        if (verb.equals(MARK)) {
            mark(args);
            return;
        }
        if (!canAnswer(player)) {
            if (SeedWorlds.seed().isEmpty() && verb.equals("stronghold")) {
                ChatUtil.message("§7Without a seed §bEyeTracker §7finds the stronghold where the flights of two thrown "
                    + "eyes of ender cross.");
            }
            return;
        }
        if (verb.equals(BIOME)) {
            biome(player, args);
        } else {
            structure(player, args);
        }
    }

    private static boolean canAnswer(LocalPlayer player) {
        if (SeedWorlds.seed().isEmpty()) {
            String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
            ChatUtil.error("No seed is saved for this server. Save it with §f" + prefix + "seed set <seed>§c. §f"
                + prefix + "seed bedrock§c writes what a seed cracker needs to work it out.");
            return false;
        }
        if (!SeedWorlds.makes(player.level().dimension())) {
            ChatUtil.error("The plain game has no dimension like this one.");
            return false;
        }
        return true;
    }

    private void structure(LocalPlayer player, String[] args) {
        OptionalInt last = args.length > 1 ? count(args[args.length - 1]) : OptionalInt.empty();
        int nth = last.orElse(1);
        if (nth < 1) {
            usage("locate <structure> [n]");
            return;
        }
        String name = name(args, 0, last.isPresent() ? args.length - 1 : args.length);
        BlockPos origin = player.blockPosition();
        boolean roofed = player.level().dimensionType().hasCeiling();
        ask(player, (world, stopped) -> structure(world, name, nth, origin, roofed, stopped));
    }

    private void biome(LocalPlayer player, String[] args) {
        if (args.length == 1) {
            usage("locate biome <biome>");
            return;
        }
        String name = name(args, 1, args.length);
        BlockPos origin = player.blockPosition();
        LevelReader level = player.level();
        ask(player, (world, stopped) -> biome(world, name, origin, level));
    }

    private static OptionalInt count(String text) {
        try {
            return OptionalInt.of(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static String name(String[] args, int from, int to) {
        String typed = String.join("_", Arrays.copyOfRange(args, from, to)).toLowerCase(Locale.ROOT);
        return typed.substring(typed.indexOf(':') + 1);
    }

    private void ask(LocalPlayer player, Question question) {
        int ticket = ++asked;
        String place = ServerInfo.worldKey();
        Function<SeedWorld, Runnable> work = world -> world == null
            ? () -> ChatUtil.error("The world generation of the game could not be read. The game log says why.")
            : question.answer(world, () -> ticket != asked);
        CompletableFuture<Runnable> answer = SeedWorlds.of(player.level().dimension())
            .thenApplyAsync(work, SeedWorlds.worker())
            .exceptionally(problem -> {
                OfflineClient.LOG.error("Locate failed", problem);
                return () -> ChatUtil.error("The search failed. The game log says why.");
            });
        answer.thenAccept(lines -> ClientLines.inChatLater(() -> {
            if (current(ticket, place)) {
                lines.run();
            }
        }));
        CompletableFuture.delayedExecutor(SLOW_MILLIS, TimeUnit.MILLISECONDS).execute(
            () -> ClientLines.inChatLater(() -> {
                if (!answer.isDone() && current(ticket, place)) {
                    ChatUtil.message("§7Still searching. Something rare can take a while.");
                }
            }));
    }

    private boolean current(int ticket, String place) {
        return ticket == asked && place.equals(ServerInfo.worldKey());
    }

    private Runnable structure(SeedWorld world, String name, int nth, BlockPos origin, boolean roofed,
                               BooleanSupplier stopped) {
        String typed = name.replace('_', ' ');
        Set<Holder<Structure>> named = new HashSet<>();
        world.registries().lookupOrThrow(Registries.STRUCTURE).listElements()
            .filter(structure -> fits(structure.key().identifier().getPath(), name))
            .forEach(named::add);
        if (named.isEmpty()) {
            return () -> ChatUtil.error("No structure is called " + typed + ".");
        }
        Set<Holder<Structure>> here = new HashSet<>();
        for (Holder<StructureSet> set : world.structures().possibleStructureSets()) {
            here.addAll(startable(world, set.value()));
        }
        named.retainAll(here);
        if (named.isEmpty()) {
            return () -> ChatUtil.error("No " + typed + " starts in this dimension.");
        }
        StructureSearch search = new StructureSearch(world, named, origin, stopped);
        List<Find> finds = search.nearest(nth);
        if (finds.size() < nth) {
            String within = search.reach() == 0 ? "" : " within §f" + search.reach() + "§c blocks of you";
            return () -> ChatUtil.error("The plain game makes no §f" + (nth == 1 ? "" : ordinal(nth) + " nearest ")
                + typed + "§c" + within + ".");
        }
        Find find = finds.get(nth - 1);
        // Under a roof the top of the ground is the roof itself.
        BlockPos spot = find.spot().atY(roofed ? origin.getY()
            : world.surface(find.spot().getX(), find.spot().getZ()));
        String what = ChatUtil.words(find.structure().unwrapKey().orElseThrow().identifier());
        String away = away(origin, spot, find.distance());
        String next = "locate " + name + " " + (nth + 1);
        return () -> found(what, nth, find.certain(), spot, away, next);
    }

    // The game keeps a whole set for a dimension as soon as one of its structures accepts a biome
    // there.
    private static List<Holder<Structure>> startable(SeedWorld world, StructureSet set) {
        Set<Holder<Biome>> biomes = world.generator().getBiomeSource().possibleBiomes();
        List<Holder<Structure>> startable = new ArrayList<>();
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            if (entry.structure().value().biomes().stream().anyMatch(biomes::contains)) {
                startable.add(entry.structure());
            }
        }
        return startable;
    }

    private static boolean fits(String id, String typed) {
        return ("_" + id + "_").contains("_" + typed + "_");
    }

    private Runnable biome(SeedWorld world, String name, BlockPos origin, LevelReader level) {
        String typed = name.replace('_', ' ');
        BiomeSource source = world.generator().getBiomeSource();
        Holder<Biome> wanted = null;
        for (Holder<Biome> biome : source.possibleBiomes()) {
            if (path(biome).equals(name)) {
                wanted = biome;
            }
        }
        if (wanted == null) {
            return () -> ChatUtil.error("No biome of this dimension is called " + typed + ".");
        }
        Pair<BlockPos, Holder<Biome>> nearest = source.findClosestBiome3d(origin, BIOME_REACH, BIOME_STEP,
            BIOME_HEIGHT_STEP, wanted::equals, world.random(), level);
        if (nearest == null) {
            return () -> ChatUtil.error("The plain game makes no §f" + typed + "§c within §f" + BIOME_REACH
                + "§c blocks of you.");
        }
        BlockPos spot = nearest.getFirst();
        String away = away(origin, spot, Math.hypot(spot.getX() - origin.getX(), spot.getZ() - origin.getZ()));
        return () -> found(typed, 1, true, spot, away, null);
    }

    private static String path(Holder<?> entry) {
        return entry.unwrapKey().map(key -> key.identifier().getPath()).orElse("");
    }

    private static String away(BlockPos origin, BlockPos spot, double distance) {
        return Notice.away(distance, Vec3.atCenterOf(origin), Vec3.atCenterOf(spot));
    }

    private void found(String what, int nth, boolean certain, BlockPos spot, String away, String next) {
        MutableComponent line = Component.literal("§7The " + (nth == 1 ? "" : ordinal(nth) + " ") + "nearest §b"
                + what + " §7is " + (certain ? "" : "likely ") + "§f" + away + "§7 at ")
            .append(ChatUtil.walkLink(spot))
            .append("§7. ")
            .append(ChatUtil.commandLink("§8[§7waypoint§8]",
                "locate " + MARK + " " + spot.getX() + " " + spot.getY() + " " + spot.getZ() + " " + stem(what),
                "Click to save it as a waypoint"));
        if (next != null) {
            line.append(" ").append(ChatUtil.commandLink("§8[§7next§8]", next,
                "Click for the next nearest if this one is not there"));
        }
        ChatUtil.component(line);
        if (told.changed()) {
            ChatUtil.message("§8This is what the plain game makes from this seed. A server with world generation of"
                + " its own puts things elsewhere.");
        }
    }

    private static String ordinal(int number) {
        int tens = number % 100;
        if (tens >= 11 && tens <= 13) {
            return number + "th";
        }
        return number + switch (number % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    private static String stem(String words) {
        StringBuilder stem = new StringBuilder();
        for (String word : words.split(" ")) {
            stem.append(ChatUtil.capitalised(word));
        }
        return stem.toString();
    }

    private void mark(String[] args) {
        if (args.length != 5) {
            usage();
            return;
        }
        Vec3 spot = coordinates(args, 1, Vec3.ZERO);
        if (spot == null) {
            return;
        }
        String name = WaypointStore.get().markFreshFind(args[4], BlockPos.containing(spot), ServerInfo.dimension(),
            Modules.nextWaypointColour());
        ChatUtil.message("§7Saved the waypoint §b" + name + "§7.");
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || index > 2) {
            return List.of();
        }
        // Names come from the game's data. Asking here starts the read before the command is sent.
        SeedWorld world = SeedWorlds.of(player.level().dimension()).getNow(null);
        if (index == 1) {
            Set<String> names = world == null ? new TreeSet<>() : structureNames(world);
            names.add(BIOME);
            return CommandManager.filter(current, new ArrayList<>(names));
        }
        if (world == null || !tokens[1].equalsIgnoreCase(BIOME)) {
            return List.of();
        }
        List<String> biomes = new ArrayList<>();
        for (Holder<Biome> biome : world.generator().getBiomeSource().possibleBiomes()) {
            biomes.add(path(biome));
        }
        return CommandManager.filter(current, biomes);
    }

    private static Set<String> structureNames(SeedWorld world) {
        Set<String> names = new TreeSet<>();
        for (Holder<StructureSet> set : world.structures().possibleStructureSets()) {
            List<String> ids = new ArrayList<>();
            for (Holder<Structure> structure : startable(world, set.value())) {
                ids.add(path(structure));
            }
            names.addAll(ids);
            String shared = shared(ids);
            if (!shared.isEmpty()) {
                names.add(shared);
            }
        }
        return names;
    }

    private static String shared(List<String> ids) {
        String[] words = ids.getFirst().split("_");
        for (int count = words.length; count > 0; count--) {
            String stem = String.join("_", Arrays.copyOf(words, count));
            if (ids.stream().allMatch(id -> id.equals(stem) || id.startsWith(stem + "_"))) {
                return stem;
            }
        }
        return "";
    }
}
