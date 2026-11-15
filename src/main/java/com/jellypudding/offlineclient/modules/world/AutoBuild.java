package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.Blueprint;
import com.jellypudding.offlineclient.config.BuildFiles;
import com.jellypudding.offlineclient.config.BuildFrame;
import com.jellypudding.offlineclient.config.BuildPlacements;
import com.jellypudding.offlineclient.config.BuildPlacements.Placement;
import com.jellypudding.offlineclient.config.BuildTemplate;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.BuildGhost;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.GridSetting;
import com.jellypudding.offlineclient.setting.ListMode;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockLetters;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BuildSite;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.Ignition;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PortalLighter;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.SiteWorker;
import com.jellypudding.offlineclient.util.StatePlacer;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TakeFrom;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.UseBudget;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Builds a painted pattern or a saved template or text where you aim and prints build files put
// down in the world. One press of the use key starts a pattern or a template or text and the next
// stops it. A file stays where a press or the build command put it and is printed whenever you
// are near whilst the module is on. The placing itself is the work of SiteWorker.
public final class AutoBuild extends Module {

    public enum Source { PATTERN, TEMPLATE, TEXT, FILE }

    public enum Orientation { AUTO, UPRIGHT, FLAT }

    private static final String PATTERN_NAME = "the pattern";

    // Ticks with nothing placed before the player is told what the build is stuck on.
    private static final int STALL_TICKS = 100;
    // Ticks before the same trouble is told again.
    private static final int TOLD_TICKS = 600;

    private final EnumSetting<Source> source = new EnumSetting<>("Source", "What gets built.", Source.PATTERN)
        .describe(Source.PATTERN, "The pattern painted below built from the plain blocks in your hotbar. It"
            + " stands up as a wall or lies flat as a floor.")
        .describe(Source.TEMPLATE, "A saved template. Files live in offlineclient/templates.")
        .describe(Source.TEXT, "The text below written in the plain blocks in your hotbar.")
        .describe(Source.FILE, "A build file put down in the world. It stays where a press puts it until the"
            + " build command removes it. Files live in offlineclient/schematics.");
    private final GridSetting pattern = new GridSetting("Pattern",
        "The cells to fill. The dotted middle cell goes where you aim and a pattern that would cut into the"
            + " block you look at moves out to rest on it. Click the size to change it.",
        "#####", "#####", "#####", "#####", "#####")
        .under(source, Source.PATTERN);
    private final TextSetting text = new TextSetting("Text", "The words to build. Click to type it.", "HELLO")
        .under(source, Source.TEXT);
    private final NumberSetting letterHeight = new NumberSetting("Letter height",
        "How many blocks tall each letter stands.", 7, 7, 35, 1, " blocks").under(source, Source.TEXT);
    private final EnumSetting<Orientation> orientation = new EnumSetting<>("Orientation",
        "Whether the pattern or the text stands up or lies flat.", Orientation.AUTO)
        .describe(Orientation.AUTO, "Stands up whilst you look ahead and lies flat once you look far up or down.")
        .describe(Orientation.UPRIGHT, "Always stands up like a wall facing you.")
        .describe(Orientation.FLAT, "Always lies flat like a floor with the top row on the far side.")
        .under(source, Source.PATTERN, Source.TEXT);
    private final NumberSetting tilt = new NumberSetting("Tilt",
        "How far you look up or down before the pattern or the text lies flat.", 40, 10, 80, 5, " degrees")
        .min(0).max(90).under(orientation, Orientation.AUTO);
    private final ChoiceListSetting template = new ChoiceListSetting("Template",
        "Which template to build. The first one picked wins.", BuildTemplate::names)
        .under(source, Source.TEMPLATE);
    private final BoolSetting savedBlocks = new BoolSetting("Use saved blocks",
        "Places the blocks the template names the way they were saved. Off builds it from any plain block"
            + " in your hotbar.", true)
        .under(source, Source.TEMPLATE);
    private final ChoiceListSetting file = new ChoiceListSetting("File",
        "The files a press puts down where you aim. The first picked file that is not down in this world"
            + " yet goes next. Files ending in .schem and .litematic and .nbt are read.", BuildFiles::names)
        .under(source, Source.FILE);
    private final BoolSetting keepBuilding = new BoolSetting("Keep building",
        "Builds the shape again every time its blocks are gone. A golem walks off and the next one goes up."
            + " The next press stops it.", false)
        .under(source, Source.PATTERN, Source.TEMPLATE, Source.TEXT);
    private final EnumSetting<TakeFrom> takeFrom = TakeFrom.setting("blocks", TakeFrom.INVENTORY);
    private final EnumSetting<ListMode> listMode = ListMode.setting("List mode", ListMode.BLACKLIST,
        "Only the listed blocks are placed.", "Every block except the listed ones is placed.");
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks the list applies to. Click to pick them.", BuiltInRegistries.BLOCK, List.of());
    private final EnumSetting<SiteWorker.Order> order = new EnumSetting<>("Order",
        "Which block in reach goes down first.", SiteWorker.Order.NEAREST)
        .describe(SiteWorker.Order.NEAREST, "The block nearest to you goes down first.")
        .describe(SiteWorker.Order.FURTHEST, "The block furthest from you goes down first.")
        .describe(SiteWorker.Order.BOTTOM_UP, "The lowest layer goes down first. Right for sand and gravel.")
        .describe(SiteWorker.Order.TOP_DOWN, "The highest layer goes down first. Right for working under a roof.");
    private final BoolSetting strictOrder = new BoolSetting("Strict order",
        "Waits at a block that cannot go down yet and places nothing after it. Slower and tidier.", false);
    private final BoolSetting standIns = new BoolSetting("Stand ins",
        "Places dirt where the build wants grass or a path and plain stone where it wants an infested one"
            + " whenever you carry none of the real block.", false);
    private final BoolSetting fix = new BoolSetting("Fix mistakes",
        "Breaks a block marked as a mistake once you carry the right one and puts that down. Off only marks"
            + " mistakes red.", false);
    private final BoolSetting breakContainers = new BoolSetting("Break containers",
        "Also breaks a mistake that holds something such as a chest or a furnace or a sign. Off leaves those"
            + " standing.", false)
        .under(fix);
    private final BoolSetting wrongStates = new BoolSetting("Wrong states",
        "A right block that faces the wrong way or sits in the wrong half counts as a mistake.", true);
    private final BoolSetting spare = new BoolSetting("Spare blocks",
        "A block standing where a build file wants nothing counts as a mistake.", false);
    private final BoolSetting airPlace = new BoolSetting("Air place",
        "Clicks straight into an empty spot when no block beside it gives the wanted block. Off waits for a"
            + " block to lean on.", true);
    private final BoolSetting lightPortals = new BoolSetting("Light portals",
        "Lights a nether portal frame the build has finished whilst you carry something that starts a fire.",
        false);
    private final Ignition ignition = new Ignition().under(lightPortals);
    private final NumberSetting distance = new NumberSetting("Distance",
        "How far ahead of your eyes the build sits when you aim at open air.", 3, 1, 6, 0.5, " blocks");
    private final NumberSetting range = new NumberSetting("Range",
        "How far from your eyes a block may go. The server takes one block past your normal reach.",
        5.5, 1, 6, 0.1, " blocks");
    private final NumberSetting blocksPerTick = UseBudget.blocksPerTick();
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait after each batch of blocks.", 0, 0, 20, 1, " ticks");
    private final NumberSetting attempts = new NumberSetting("Attempts",
        "How many times a block the server turns down is tried before it is skipped.", 3, 1, 10, 1);
    private final BoolSetting lineOfSight = new BoolSetting("Line of sight",
        "Never places through a wall. Safer against anti cheats and slower.", false);
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SERVER);
    private final EnumSetting<SwingMode> swing = SwingMode.settingWithUses(SwingMode.PACKET);
    private final BoolSetting show = new BoolSetting("Show build",
        "Outlines where the build goes before you start and what is left whilst it runs. A mistake is"
            + " marked red.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.LINES, 120).under(show);

    // Where a press would put a shape right now.
    private record Aim(String name, Blueprint blueprint, BuildFrame frame) {
    }

    // A build being worked on or the one a press would start.
    private static final class Job {

        private final String name;
        private final BuildSite site;
        private final BuildGhost ghost = new BuildGhost();
        // Null for a build a press started.
        private final Placement placement;
        // Whether nothing was left to do at the last full look that could tell.
        private boolean whole;
        // How many blocks had landed when the player was last told it stands.
        private int toldLanded;

        private Job(String name, BuildSite site, Placement placement) {
            this.name = name;
            this.site = site;
            this.placement = placement;
        }

        private Job(Level level, Aim aim) {
            this(aim.name(), new BuildSite(level, aim.blueprint(), aim.frame()), null);
        }
    }

    private final SiteWorker worker = new SiteWorker();
    private final WorldWatch world = new WorldWatch();
    private final PortalLighter lighter = new PortalLighter(ignition);
    private final Cooldowns<String> told = new Cooldowns<>();

    private Blueprint loaded;
    // The loaded template with every block name taken off.
    private Blueprint loadedPlain;
    // A template that failed to load and why when it could be read but not held. It is read
    // again only for a new pick or a switch on or a use press.
    private String unreadable;
    private String templateTrouble;

    // The pattern as a shape and the cells and the lie it was boxed for.
    private Blueprint painted;
    private List<GridSetting.Cell> paintedFor;
    private boolean paintedFlat;
    // The text as upright cells with the words and height they were written for and the shape
    // boxed from them with the lie it was boxed for. The trouble says why it could not be boxed.
    private List<BuildTemplate.Entry> letters = List.of();
    private String lettersFor;
    private Blueprint written;
    private boolean writtenBoxed;
    private boolean writtenFlat;
    private String writtenTrouble;

    // The build a press started. Null whilst none runs.
    private Job quick;
    // What a press would build right now. Drawn whilst idle.
    private Job preview;
    // The files put down in this world as saved and the jobs of the shown ones by name.
    private List<Placement> saved = List.of();
    private int savedSeen = -1;
    private final Map<String, Job> placed = new LinkedHashMap<>();
    // Files of this world whose trouble has been told.
    private final Set<String> troubleTold = new HashSet<>();
    // The picks and placements the player was last told are all down.
    private String allDownTold;
    private int stalled;
    // True whilst the module switches itself on for a file. The words of a switch on by hand
    // stay unsaid then.
    private boolean quietStart;

    // The use key repeats a held press every few ticks. Only a fresh press starts or stops a build.
    private boolean useHeld;
    private boolean ownsHold;

    public AutoBuild() {
        super("AutoBuild", "Builds a pattern or a template or text where you aim and prints build files.",
            Category.WORLD);
        addSettings(source, pattern, text, letterHeight, orientation, tilt, template, savedBlocks, file,
            keepBuilding, takeFrom, listMode, blocks, order, strictOrder, standIns, fix, breakContainers,
            wrongStates, spare, airPlace, lightPortals);
        addSettings(ignition.settings());
        addSettings(distance, range, blocksPerTick, delay, attempts, lineOfSight, faceTarget, swing, show);
        addSettings(style.settings());
        searchTags("insta build", "template", "pattern", "grid", "wall", "floor", "structure", "text", "letters",
            "words", "printer", "schematic", "litematic", "schem", "golem", "portal");
    }

    @Override
    public String getSuffix() {
        Job shown = quick != null ? quick : nearestPlaced();
        if (shown == null) {
            return source.is(Source.TEMPLATE) && loaded != null ? loaded.name() : null;
        }
        BuildSite.Census census = shown.site.census();
        return suffix(shown.name, census == null ? null : census.percent() + "%");
    }

    private Job nearestPlaced() {
        if (!inGame()) {
            return null;
        }
        Job nearest = null;
        double best = Double.MAX_VALUE;
        for (Job job : placed.values()) {
            double away = job.site.box().getCenter().distSqr(mc.player.blockPosition());
            if (away < best) {
                best = away;
                nearest = job;
            }
        }
        return nearest;
    }

    @Override
    protected void onEnable() {
        stopQuick();
        forgetShapes();
        // A file changed on disk is read afresh.
        BuildFiles.forget();
        world.accept();
        if (!inGame() || quietStart) {
            return;
        }
        if (!saved().isEmpty()) {
            tell("draws and builds §f" + Tally.counted(saved().size(), "file")
                + "§7 put down in this world.");
            if (source.is(Source.FILE) && fileToPlace() == null) {
                return;
            }
        }
        String missing = missingShape();
        if (missing != null) {
            ChatUtil.error(missing);
            return;
        }
        tell("hold a block and press §f"
            + mc.options.keyUse.getTranslatedKeyMessage().getString() + "§7 to "
            + (source.is(Source.FILE) ? "put §f" + fileToPlace() + "§7 down" : "build §f" + shapeName() + "§7")
            + " where you aim.");
    }

    @Override
    protected void onDisable() {
        stopQuick();
        worker.forget();
        preview = null;
        placed.clear();
        savedSeen = -1;
        troubleTold.clear();
        allDownTold = null;
        told.clear();
        BuildFiles.forget();
    }

    private void stopQuick() {
        quick = null;
        stalled = 0;
        worker.stop();
    }

    private void forgetShapes() {
        loaded = null;
        loadedPlain = null;
        unreadable = null;
        templateTrouble = null;
    }

    private String shapeName() {
        return switch (source.getValue()) {
            case TEMPLATE -> loaded.name();
            case TEXT -> text.getValue().trim();
            case PATTERN -> PATTERN_NAME;
            case FILE -> fileToPlace();
        };
    }

    // Why there is nothing to build. Null once there is.
    private String missingShape() {
        return switch (source.getValue()) {
            case TEMPLATE -> loadChosen() ? null
                : templateTrouble != null ? templateTrouble : "Pick a template for AutoBuild first.";
            case TEXT -> letters().isEmpty() ? "Type some letters or digits for AutoBuild first."
                : writtenShape(liesFlat()) == null ? writtenTrouble : null;
            case PATTERN -> pattern.cellsOn().isEmpty()
                ? "Paint at least one cell of the AutoBuild pattern first." : null;
            case FILE -> missingFile();
        };
    }

    private String missingFile() {
        if (file.chosen().isEmpty()) {
            return BuildFiles.names().isEmpty()
                ? "Put a .schem or .litematic or .nbt file in offlineclient/schematics for AutoBuild first."
                : "Pick a file for AutoBuild first.";
        }
        String waiting = fileToPlace();
        return waiting == null ? null : BuildFiles.problem(waiting);
    }

    // The first picked file that has no placement in this world. A press puts it down. Null once
    // every picked file is down. More copies go down through the build command.
    private String fileToPlace() {
        for (String name : file.chosen()) {
            if (saved().stream().noneMatch(placement -> placement.file().equals(name))) {
                return name;
            }
        }
        return null;
    }

    // The files put down in this world. Read again only after the list changed or the world did.
    // A job whose placement moved or turned or went is dropped with the stale list.
    private List<Placement> saved() {
        BuildPlacements store = BuildPlacements.get();
        if (store.version() != savedSeen) {
            savedSeen = store.version();
            saved = store.here();
            placed.values().removeIf(job -> !saved.contains(job.placement));
        }
        return saved;
    }

    // Boxed again only once the painted cells or the lie change.
    private Blueprint paintedShape(boolean flat) {
        List<GridSetting.Cell> cells = pattern.cellsOn();
        if (!cells.equals(paintedFor) || flat != paintedFlat) {
            paintedFor = cells;
            paintedFlat = flat;
            int half = pattern.size() / 2;
            List<BuildTemplate.Entry> entries = new ArrayList<>();
            for (GridSetting.Cell cell : cells) {
                entries.add(new BuildTemplate.Entry(half - cell.column(), half - cell.row(), 0, null));
            }
            painted = boxed(PATTERN_NAME, flat ? laidFlat(entries) : entries).blueprint();
        }
        return painted;
    }

    // Written again only once the words or the height change. Empty for text with nothing to draw.
    private List<BuildTemplate.Entry> letters() {
        String wanted = letterHeight.getInt() + " " + text.getValue();
        if (!wanted.equals(lettersFor)) {
            lettersFor = wanted;
            letters = BlockLetters.cells(text.getValue(), letterHeight.getInt());
            writtenBoxed = false;
        }
        return letters;
    }

    // Boxed again only once the letters or the lie change. Null for text too big to be one build.
    private Blueprint writtenShape(boolean flat) {
        List<BuildTemplate.Entry> cells = letters();
        if (!writtenBoxed || flat != writtenFlat) {
            writtenBoxed = true;
            writtenFlat = flat;
            Boxed boxed = boxed(text.getValue().trim(), flat ? laidFlat(cells) : cells);
            written = boxed.blueprint();
            writtenTrouble = boxed.trouble();
        }
        return written;
    }

    // Loose cells as a build or else why they cannot be one.
    private record Boxed(Blueprint blueprint, String trouble) {
    }

    private static Boxed boxed(String name, List<BuildTemplate.Entry> entries) {
        try {
            return new Boxed(BuildTemplate.of(name, entries), null);
        } catch (BuildFiles.Unreadable e) {
            return new Boxed(null, name + " " + e.getMessage() + ".");
        }
    }

    // Laid flat a shape tips away from you. Its up runs ahead and its forward runs up.
    private static List<BuildTemplate.Entry> laidFlat(List<BuildTemplate.Entry> upright) {
        List<BuildTemplate.Entry> flat = new ArrayList<>(upright.size());
        for (BuildTemplate.Entry entry : upright) {
            flat.add(new BuildTemplate.Entry(entry.x(), entry.z(), entry.y(), entry.state()));
        }
        return flat;
    }

    // A fresh pick in the list swaps the template between builds.
    private boolean loadChosen() {
        Iterator<String> picked = template.chosen().iterator();
        if (!picked.hasNext()) {
            loaded = null;
            return false;
        }
        String name = picked.next();
        if ((loaded == null || !loaded.name().equals(name)) && !name.equals(unreadable)) {
            loadedPlain = null;
            templateTrouble = null;
            try {
                loaded = BuildTemplate.load(name);
            } catch (BuildFiles.Unreadable e) {
                loaded = null;
                templateTrouble = name + " " + e.getMessage() + ".";
            }
            unreadable = loaded == null ? name : null;
        }
        return loaded != null;
    }

    // The loaded template as it is built. With the saved blocks off every cell takes any block.
    private Blueprint templateShape() {
        if (savedBlocks.isOn()) {
            return loaded;
        }
        if (loadedPlain == null) {
            loadedPlain = loaded.plain();
        }
        return loadedPlain;
    }

    // Food and tools keep their use and a door or chest under the crosshair still opens.
    private boolean pressBuilds() {
        if (mc.player.isSpectator() || mc.player.isHandsBusy() || mc.hitResult instanceof EntityHitResult) {
            return false;
        }
        ItemStack held = mc.player.getMainHandItem();
        if (!held.isEmpty() && !(held.getItem() instanceof BlockItem)) {
            return false;
        }
        BlockHitResult aimed = BlockUtil.aimedBlock();
        return aimed == null || mc.player.isSecondaryUseActive()
            || !BlockUtil.opensOnClick(BlockUtil.state(aimed.getBlockPos()));
    }

    // Runs after Freecam and anything else that holds clicks back and before AirPlace.
    @Subscribe(priority = -5)
    private void onRightClick(RightClickEvent event) {
        if (!inGame()) {
            return;
        }
        boolean fresh = !useHeld;
        useHeld = true;
        if (!fresh) {
            if (ownsHold) {
                event.cancel();
            }
            return;
        }
        if (quick != null) {
            stopQuick();
            tell("stopped.");
            take(event);
            return;
        }
        if (!pressBuilds()) {
            return;
        }
        // Once every picked file is down the press belongs to the game again.
        if (source.is(Source.FILE) && !file.chosen().isEmpty() && fileToPlace() == null) {
            tellAllDown();
            return;
        }
        take(event);
        forgetShapes();
        String missing = missingShape();
        if (missing != null) {
            ChatUtil.error(missing);
            return;
        }
        Aim aim = aim();
        if (aim == null) {
            ChatUtil.error(source.is(Source.FILE) && BuildFiles.ready(fileToPlace()) == null
                ? "AutoBuild is still reading that file." : "AutoBuild cannot build outside the world.");
        } else if (source.is(Source.FILE)) {
            putDown(fileToPlace(), BuildFiles.loaded(fileToPlace()), aim.frame());
            ChatUtil.message("§7The §f" + OfflineClient.INSTANCE.getCommandManager().getPrefix()
                + "build§7 command moves and turns and removes it.");
        } else {
            stopQuick();
            quick = new Job(mc.level, aim);
        }
    }

    // The press is ours. Vanilla would drop a stray block at the crosshair.
    private void take(RightClickEvent event) {
        event.cancel();
        ownsHold = true;
        mc.rightClickDelay = InputUtil.USE_DELAY;
    }

    // Said once for each set of picks and placements. A press with nothing left to put down
    // would otherwise do nothing without a word.
    private void tellAllDown() {
        String state = file.chosen() + " " + savedSeen;
        if (!state.equals(allDownTold)) {
            allDownTold = state;
            tell("every picked file is down in this world. §f"
                + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "build place§7 puts down another copy.");
        }
    }

    // Puts a file that has been read down in this world and says what went down. The build
    // command comes through here as well. The module switches itself on for it. A file down in
    // the world with nothing to draw or build it would be no use.
    public void putDown(String fileName, BuildFiles.Loaded loaded, BuildFrame frame) {
        Blueprint blueprint = loaded.blueprint();
        Set<String> leftOut = loaded.leftOut();
        Placement placement = BuildPlacements.get().add(fileName, frame);
        Vec3i size = blueprint.size();
        ChatUtil.message("§7Put §b" + placement.name() + " §7down " + reach(frame, blueprint) + "§7. It is §f"
            + size.getX() + " §7by §f" + size.getY() + " §7by §f" + size.getZ() + " §7with §f"
            + Tally.counted(blueprint.blocks(), "block") + "§7.");
        if (!leftOut.isEmpty()) {
            ChatUtil.message("§7It names §f" + Tally.counted(leftOut.size(), "kind") + " §7of block this game does"
                + " not have. Those are left out. They are §f" + Tally.firstFew(leftOut) + "§7.");
        }
        if (!isEnabled()) {
            quietStart = true;
            setEnabled(true);
            quietStart = false;
            tell("is switched on to draw it and build it.");
        }
    }

    // The corners a build covers such as from 10 64 10 to 20 70 30.
    public static String reach(BuildFrame frame, Blueprint blueprint) {
        BoundingBox box = frame.box(blueprint);
        return "§7from §f" + box.minX() + " " + box.minY() + " " + box.minZ() + " §7to §f" + box.maxX() + " "
            + box.maxY() + " " + box.maxZ();
    }

    // Where a press would put the chosen shape now. Null outside the world and for a file that
    // is not read yet.
    private Aim aim() {
        BlockHitResult look = BlockUtil.look(Math.max(distance.getValue(), mc.player.blockInteractionRange()));
        boolean onBlock = look.getType() == HitResult.Type.BLOCK;
        BlockPos anchor = onBlock ? BlockUtil.placeSpot(look) : BlockUtil.airSpot(distance.getValue());
        if (anchor == null) {
            return null;
        }
        Direction front = mc.player.getDirection();
        if (source.is(Source.FILE)) {
            Blueprint blueprint = BuildFiles.ready(fileToPlace());
            return blueprint == null ? null : new Aim(blueprint.name(), blueprint, ahead(blueprint, anchor, front));
        }
        if (source.is(Source.TEMPLATE)) {
            return new Aim(loaded.name(), templateShape(), BuildFrame.facing(anchor, front));
        }
        boolean flat = liesFlat();
        Blueprint shape = source.is(Source.TEXT) ? writtenShape(flat) : paintedShape(flat);
        BuildFrame frame = BuildFrame.facing(anchor, front);
        if (onBlock) {
            frame = frame.movedTo(rested(shape, frame, look.getDirection(), flat ? Direction.Axis.Y : front.getAxis()));
        }
        return new Aim(shape.name(), shape, frame);
    }

    private boolean liesFlat() {
        return switch (orientation.getValue()) {
            case UPRIGHT -> false;
            case FLAT -> true;
            case AUTO -> Math.abs(mc.player.getXRot()) > tilt.getValue();
        };
    }

    // A file goes down with its lowest layer on the spot you aim at. It runs ahead of you from
    // there with as much of it to your left as to your right.
    private static BuildFrame ahead(Blueprint blueprint, BlockPos anchor, Direction front) {
        Vec3i min = blueprint.min();
        BlockPos offset = BuildFrame.facing(BlockPos.ZERO, front)
            .toWorld(min.getX() + blueprint.size().getX() / 2, min.getY(), min.getZ());
        return BuildFrame.facing(anchor.subtract(offset), front);
    }

    // A face that runs across the shape would cut it in two. The shape moves out of the block
    // until its nearest cell rests against the face. The axis is the one the shape is thin along.
    private static BlockPos rested(Blueprint shape, BuildFrame frame, Direction face, Direction.Axis thin) {
        BlockPos anchor = frame.origin();
        if (face.getAxis() == thin) {
            return anchor;
        }
        int[] inside = {0};
        shape.forEach((x, y, z, state) -> {
            BlockPos offset = frame.toWorld(x, y, z).subtract(anchor);
            int out = offset.getX() * face.getStepX() + offset.getY() * face.getStepY()
                + offset.getZ() * face.getStepZ();
            inside[0] = Math.max(inside[0], -out);
        });
        return anchor.relative(face, inside[0]);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        useHeld = mc.options.keyUse.isDown();
        ownsHold &= useHeld;
        told.tick();
        if (world.changed() || mc.player.isDeadOrDying()) {
            leaveWork();
            return;
        }
        syncPlaced();
        BuildSite.Rules rules = siteRules();
        int answerTicks = ServerInfo.answerTicks();
        for (Job job : jobs()) {
            job.site.tick(rules, answerTicks, job == quick && keepBuilding.isOn());
            if (loadedNear(job.site)) {
                job.site.count(rules);
            }
        }
        // A build that is done ends before another block goes into it.
        finish();
        List<BuildSite> sites = new ArrayList<>();
        for (Job job : jobs()) {
            if (loadedNear(job.site)) {
                sites.add(job.site);
            }
        }
        if (quick == null) {
            preview = show.isOn() && pressBuilds() && !(source.is(Source.FILE) && fileToPlace() == null)
                && missingShape() == null ? previewOf(aim(), rules) : null;
        } else {
            preview = null;
        }
        for (Job job : jobs()) {
            job.ghost.tick(job.site, rules, spare.isOn());
        }
        if (sites.isEmpty() || mc.player.isSpectator()) {
            worker.stop();
        } else {
            work(sites, rules);
        }
    }

    // One tick of work unless something else needs the hand. A print runs for as long as the
    // module is on and a meal or a fight in that time has to get its turn.
    private void work(List<BuildSite> sites, BuildSite.Rules rules) {
        if (handTaken()) {
            worker.standAside();
            stalled = 0;
            return;
        }
        tell(worker.work(sites, new SiteWorker.Rules(rules, clickRules(), takeFrom.getValue(), this::mayPlace,
            order.getValue(), strictOrder.isOn(), fix.isOn(), spare.isOn(), breakContainers.isOn(),
            blocksPerTick.getInt(), delay.getInt())));
        tellLeft(worker.takeLeft());
        lightPortal(sites, rules);
    }

    // True whilst a module eats or drinks or throws and whilst a combat module has something to
    // fight. The same goes whilst the player uses what the main hand holds and whilst
    // ShulkerPacker keeps count of every stack. A slot picked for a block would end the meal and
    // restart the swing.
    private boolean handTaken() {
        return Hands.slotsTaken() || Modules.fighting()
            || mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND;
    }

    // A new world or a death ends the build a press started. Both hand out a fresh inventory and
    // a stack borrowed in the last one is nothing to give back. The files of the world left
    // behind go and the ones of this world are read in on the next tick.
    private void leaveWork() {
        if (quick != null) {
            tell("stopped.");
        }
        worker.forget();
        stopQuick();
        preview = null;
        placed.clear();
        savedSeen = -1;
        troubleTold.clear();
    }

    // True for a build with a block inside the chunks the client holds. One far off holds nothing
    // to count or to work on.
    private boolean loadedNear(BuildSite site) {
        int view = (Modules.renderDistance() + 1) * SectionPos.SECTION_SIZE;
        BlockPos feet = mc.player.blockPosition();
        return site.box().intersects(feet.getX() - view, feet.getZ() - view, feet.getX() + view, feet.getZ() + view);
    }

    private List<Job> jobs() {
        List<Job> jobs = new ArrayList<>(placed.values());
        if (quick != null) {
            jobs.addFirst(quick);
        }
        return jobs;
    }

    // The same aim keeps its ghost. A new one is looked over at once.
    private Job previewOf(Aim aim, BuildSite.Rules rules) {
        if (aim == null) {
            return null;
        }
        if (preview != null && preview.site.blueprint() == aim.blueprint()
            && preview.site.frame().equals(aim.frame())) {
            preview.ghost.tick(preview.site, rules, false);
            return preview;
        }
        Job fresh = new Job(mc.level, aim);
        fresh.ghost.rebuild(fresh.site, rules, false);
        return fresh;
    }

    // Keeps a job for every file put down in this world that is shown. A placement that moved
    // or turned gets a fresh one. A file that cannot be read is told about once.
    private void syncPlaced() {
        for (Placement placement : saved()) {
            if (placement.hidden() || placed.containsKey(placement.name())) {
                continue;
            }
            Blueprint blueprint = BuildFiles.ready(placement.file());
            if (blueprint != null) {
                placed.put(placement.name(), new Job(placement.name(),
                    new BuildSite(mc.level, blueprint, placement.frame()), placement));
            } else if (BuildFiles.problem(placement.file()) != null && troubleTold.add(placement.file())) {
                ChatUtil.error(BuildFiles.problem(placement.file()));
            }
        }
    }

    private BuildSite.Rules siteRules() {
        return new BuildSite.Rules(this::mayPlace, standIns.isOn(), wrongStates.isOn(), attempts.getInt());
    }

    private StatePlacer.Rules clickRules() {
        return new StatePlacer.Rules(range.getValue(), lineOfSight.isOn(), airPlace.isOn(), faceTarget.getValue(),
            swing.getValue());
    }

    private boolean mayPlace(Block block) {
        return listMode.getValue().admits(blocks.contains(block));
    }

    // Says what holds the build up. A block the player lacks is told at once. Anything else
    // only once nothing has gone down for a while.
    private void tell(boolean busy) {
        SiteWorker.HoldUp holdUp = worker.holdUp();
        if (busy || holdUp == null) {
            stalled = 0;
            return;
        }
        if (holdUp.trouble() != SiteWorker.Trouble.LACKING && ++stalled < STALL_TICKS) {
            return;
        }
        tellOnce(words(holdUp));
    }

    // The same trouble is not told again for a while.
    private void tellOnce(String words) {
        if (!told.contains(words)) {
            told.put(words, TOLD_TICKS);
            ChatUtil.error(words);
        }
    }

    private String words(SiteWorker.HoldUp holdUp) {
        boolean plain = holdUp.block() == null;
        String block = plain ? "plain blocks" : BlockUtil.blockName(holdUp.block());
        TakeFrom from = plain ? SiteWorker.plainFrom(takeFrom.getValue()) : takeFrom.getValue();
        return switch (holdUp.trouble()) {
            case LACKING -> "AutoBuild needs " + block + switch (from) {
                case HANDS -> " in your hands.";
                case HOTBAR -> " in the hotbar.";
                case INVENTORY -> " in the inventory.";
            };
            case NO_CLICK -> "AutoBuild has nothing in reach to click for " + block + "."
                + (airPlace.isOn() ? "" : " Air place is off and it waits for a block beside the spot.");
            case NO_STATE -> "AutoBuild cannot put " + block + " down the way the build wants it from here."
                + (faceTarget.is(FaceMode.OFF) ? " Face target is off and you have to look the right way yourself."
                : "");
            case NEEDS_GROUND -> "AutoBuild holds " + block + " back until a block stands under it.";
            case NEEDS_BODY -> "AutoBuild holds " + block + " back until the blocks beneath it are in.";
        };
    }

    // A mistake the worker left standing is told once with the reason.
    private void tellLeft(SiteWorker.Left left) {
        if (left == null) {
            return;
        }
        String start = ChatUtil.from(getName(), "left the §f" + BlockUtil.blockName(left.block()) + "§7 at §f"
            + BlockUtil.text(left.pos()) + "§7 standing. ");
        ChatUtil.message(start + switch (left.why()) {
            case CONTAINER -> "It may hold something. Break containers lets Fix mistakes take such blocks.";
            case SLOW -> "It would take too long to break with what you have at hand.";
        });
    }

    // A frame of obsidian the build has standing gets a fire in the bottom of it. The look for
    // one runs every tick and the last block of a frame is still fresh when it is lit.
    private void lightPortal(List<BuildSite> sites, BuildSite.Rules rules) {
        if (lightPortals.isOn() && lighter.light(sites, rules, range.getValue(), !faceTarget.is(FaceMode.OFF))
            == PortalLighter.Outcome.NO_LIGHTER) {
            tellOnce(Ignition.NO_LIGHTER);
        }
    }

    private boolean stands(Job job) {
        return job.site.stands(fix.isOn(), spare.isOn());
    }

    // A file put down in the world is news once it stands and blocks went down since it was last
    // said to. A walk past it or a login beside it is not. The build a press started ends here
    // and with Keep building on it gets fresh attempts for the next round.
    private void finish() {
        for (Job job : placed.values()) {
            BuildSite.Census census = job.site.census();
            // Cells in chunks that are not loaded say nothing either way.
            if (census == null || census.unknown() > 0) {
                continue;
            }
            boolean stands = stands(job);
            job.whole = stands && census.skipped() == 0;
            if (stands && job.site.landed() > job.toldLanded) {
                job.toldLanded = job.site.landed();
                ChatUtil.message(finishedWords(job));
            }
        }
        if (quick == null || !stands(quick)) {
            return;
        }
        if (keepBuilding.isOn()) {
            quick.site.forgetClicks();
        } else {
            ChatUtil.message(finishedWords(quick));
            stopQuick();
        }
    }

    // Every block still missing at the end ran out of attempts.
    private static String finishedWords(Job job) {
        int skipped = job.site.census().skipped();
        String done = ChatUtil.from("AutoBuild", "finished §f" + job.name + "§7.");
        return skipped == 0 ? done : done + " Skipped §f" + skipped + "§7 blocks the server kept turning down.";
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!show.isOn()) {
            return;
        }
        if (preview != null) {
            draw(event.getBatch(), preview);
        }
        for (Job job : jobs()) {
            draw(event.getBatch(), job);
        }
    }

    // The cells near the camera and the outline of the whole build. The outline shows how far a
    // build reaches past the cells that are drawn. A file with nothing left to do needs neither.
    private void draw(DrawBatch batch, Job job) {
        job.ghost.draw(batch, style);
        if (!job.whole) {
            BuildGhost.drawBox(batch, style, job.site.box());
        }
    }
}
