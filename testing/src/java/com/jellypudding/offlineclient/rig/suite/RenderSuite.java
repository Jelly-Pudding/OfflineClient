package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.gui.ClickGuiScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Case;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.Stage;
import com.jellypudding.offlineclient.rig.core.World;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

// One case for each module that draws into the world. The same still scene is looked at before
// the module goes on and whilst it is on and after it went off. The module must change the
// picture and must leave nothing behind. The game's own interface is hidden for the pictures
// and what a module adds to the module list cannot count as a change. A module that draws on
// the interface itself is looked at with the game's interface shown and the client's overlay off.
// A module that only draws once something happens has a case of its own.
public final class RenderSuite implements Suite {

    private static final String NAME = "Render";
    private static final WorldCreationUiState.SelectedGameMode CREATIVE =
        WorldCreationUiState.SelectedGameMode.CREATIVE;
    private static final int USE = InputConstants.MOUSE_BUTTON_RIGHT;
    private static final int FORWARD = InputConstants.KEY_W;
    private static final int BACK = InputConstants.KEY_S;
    private static final String SURVIVAL = "survival";
    private static final String ALL_MOBS = "all";
    private static final String MOBS = "Mobs";
    private static final String ON = "on";
    private static final String GROWN_STILL = "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b}";

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    // The mobs of the case before die in a puff of smoke that hangs about for a few seconds.
    private static final int STILL_TICKS = 80;
    // Long enough for a fade or a zoom to finish.
    private static final int SHOWN_TICKS = 30;
    private static final int STILL_GAP = 20;
    // Pictures of the scene at rest before a module goes on.
    private static final int STILLS = 3;
    private static final float NORTH = 180;
    private static final float PIT_PITCH = 40;
    // Every tick is held up this long and the game draws well under twenty frames a second.
    private static final long STALL_MILLIS = 60;
    // SteadyFrames lets a world settle for ten seconds before it reads the frame rate.
    private static final int STALLED_TICKS = 600;
    private static final int WALK_TICKS = 30;
    private static final int VIGNETTE_TICKS = 500;
    // The view tilts for ten ticks after a blow.
    private static final int HURT_TICKS = 3;
    // A number rises over the mob it came from for a second.
    private static final int NUMBER_TICKS = 6;
    // Three hundredths of a per cent of the picture. Fewer changed pixels than this is no
    // drawing. A picture taken twice differs by a third of that at most.
    private static final double LEAST = 0.0003;
    private static final double PER_CENT = 100;
    // Modules that draw on the interface or on the hands. The interface hides both.
    private static final Set<String> ON_THE_INTERFACE = Set.of("Nametags", "ClearView", "BlockSelection",
        "NoShieldOverlay", "NoBackground", "Blur", "HandView", "ItemHighlight", "EntityOwner");
    // Modules that are a piece of the client's own overlay. Their name is kept out of the module
    // list or the list alone would count as a change.
    private static final Set<String> HUD_PIECES = Set.of("Radar", "Minimap");
    private static final String IN_LIST = "In module list";
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");

    // One module to look at. The scene may get something more for it before the first picture and
    // settings go on in pairs of a name and a value.
    private record Sight(String module, Consumer<Rig> arrange, String... settings) {
    }

    private BlockPos home;

    @Override
    public String name() {
        return NAME;
    }

    private List<Sight> sights() {
        Consumer<Rig> nothing = rig -> { };
        return List.of(
            new Sight("ESP", nothing, MOBS, ALL_MOBS),
            new Sight("Chams", nothing, MOBS, ALL_MOBS),
            new Sight("Skeletons", nothing, MOBS, ALL_MOBS),
            new Sight("Tracers", nothing, MOBS, ALL_MOBS),
            new Sight("Nametags", nothing, MOBS, ALL_MOBS),
            new Sight("ItemESP", nothing),
            new Sight("ChestESP", nothing),
            new Sight("BlockESP", nothing),
            new Sight("HoleESP", nothing),
            new Sight("XRay", nothing),
            new Sight("WallHack", nothing),
            new Sight("ChunkBorders", nothing),
            new Sight("Highways", nothing),
            new Sight("FarmRanges", nothing),
            new Sight("SculkRange", rig -> rig.world().setBlock(home.offset(-1, 0, -5), "sculk_sensor")),
            new Sight("Fullbright", rig -> rig.world().command("time set midnight")),
            new Sight("TimeChanger", nothing, "Time", "18000"),
            new Sight("Ambience", nothing, "Custom sky colour", ON),
            new Sight("Zoom", nothing),
            new Sight("TrueSight", rig -> rig.world().command(
                "effect give @e[type=husk] invisibility infinite 0 true")),
            new Sight("AntiBlind", rig -> rig.world().command(
                "effect give " + rig.world().player() + " blindness 600 0 true")),
            new Sight("Trajectories", rig -> {
                Props.hold(rig, 0, "bow");
                Props.pack(rig, 0, "arrow 16");
                rig.controls().holdMouse(USE);
            }),
            new Sight("SpawnESP", rig -> rig.world().command("time set midnight")),
            new Sight("VoidESP", this::voidPit),
            new Sight("Portals", this::portal),
            new Sight("GearedMobs", rig -> Props.summon(rig, "husk", home.offset(-5, 0, -5),
                "{NoAI:1b,PersistenceRequired:1b,IsBaby:0b,equipment:{chest:{id:\"minecraft:elytra\",count:1}}}")),
            new Sight("Collectibles", rig -> Props.summon(rig, "item", home.offset(4, 0, -4),
                "{Item:{id:\"minecraft:dragon_egg\",count:1},PickupDelay:32767,Age:-32768}")),
            new Sight("SignReader", rig -> rig.world().command("setblock " + Props.at(home.offset(-4, 0, -3))
                + " oak_sign[rotation=0]{front_text:{messages:[\"rig was here\",\"\",\"\",\"\"]}}")),
            new Sight("CameraTweaks", rig -> rig.act(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK))),
            new Sight("ClearView", rig -> Props.wear(rig, "armor.head", "carved_pumpkin")),
            new Sight("BlockSelection", rig -> rig.controls().aimAtBlock(home.offset(0, -1, -2), Direction.UP)),
            new Sight("NoShieldOverlay", rig -> Props.wear(rig, "weapon.offhand", "shield")),
            new Sight("Radar", nothing),
            new Sight("Minimap", nothing),
            new Sight("Waypoints", this::waypoint),
            new Sight("NoBackground", RenderSuite::openInventory),
            new Sight("NoRender", rig -> rig.world().command("summon falling_block " + (home.getX() + 0.5) + " "
                + (home.getY() + 1) + " " + (home.getZ() - 2.5) + " {BlockState:\"minecraft:sand\",NoGravity:1b,"
                + "Time:-2147483648}"), "Falling blocks", ON),
            new Sight("ItemPhysics", nothing),
            new Sight("HandView", rig -> Props.hold(rig, 0, "diamond_sword"), "Adjust main hand", ON,
                "Main hand scale x", "2"),
            new Sight("ItemHighlight", rig -> Props.hold(rig, 0, "totem_of_undying")),
            new Sight("EntityOwner", this::pet, "Own", ON),
            // A vault only wakes for a player within four blocks. This one stands still.
            new Sight("TrialESP", rig -> rig.world().setBlock(home.offset(-3, 0, -6), "vault")),
            // The game lists what a mob could do over its head even when it stands still.
            new Sight("DebugDrawings", nothing, "Mob paths", "off", "Mob goals", ON));
    }

    private Map<String, Case.Body> plays() {
        Map<String, Case.Body> plays = new LinkedHashMap<>();
        plays.put("Breadcrumbs draws the way you came", this::breadcrumbs);
        plays.put("Trail leaves particles where you walk", this::trail);
        plays.put("NoHurtCam keeps the view level when you are hurt", this::noHurtCam);
        plays.put("DamageNumbers draws a number over a mob that is hurt", this::damageNumbers);
        plays.put("Marker draws the box you add with its key", this::marker);
        plays.put("SteadyFrames draws fewer chunks whilst frames are slow and all of them again after",
            this::steadyFrames);
        plays.put("Blur blurs the world behind the inventory", this::blur);
        plays.put("Blur keeps to one blur a frame behind every kind of screen", this::blurScreens);
        plays.put("Every render module at once changes the picture and leaves it clean", this::allAtOnce);
        return plays;
    }

    @Override
    public void run(Rig rig) {
        List<Case> cases = new ArrayList<>();
        for (Sight sight : sights()) {
            cases.add(Case.of(sight.module() + " changes the picture and leaves it clean", each -> look(each, sight)));
        }
        plays().forEach((name, play) -> cases.add(Case.of(name, play)));
        List<Case> wanted = rig.wanted(NAME, cases);
        if (wanted.isEmpty()) {
            return;
        }
        int[] size = rig.ask(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        rig.openWorld(CREATIVE);
        rig.frames().drawAt(WIDTH, HEIGHT);
        try {
            Stage stage = new Stage(rig);
            home = stage.home();
            // The game darkens the corners of the screen in a fresh world and eases that off for a
            // long while. No picture is taken before it has come to rest.
            rig.ticks(VIGNETTE_TICKS);
            for (Case each : wanted) {
                stage.reset(false);
                rig.world().gameMode(SURVIVAL);
                rig.run(NAME, each);
            }
        } finally {
            rig.frames().drawAt(size[0], size[1]);
            rig.closeWorld();
        }
    }

    private void look(Rig rig, Sight sight) {
        scene(rig);
        sight.arrange().accept(rig);
        boolean piece = HUD_PIECES.contains(sight.module());
        boolean overlay = piece || ON_THE_INTERFACE.contains(sight.module());
        rig.client().disable(sight.module());
        if (piece) {
            rig.client().set(sight.module(), IN_LIST, "off");
        } else if (overlay) {
            rig.client().disable("HUD");
        }
        hideInterface(rig, !overlay);
        try {
            for (int pair = 0; pair + 1 < sight.settings().length; pair += 2) {
                rig.client().set(sight.module(), sight.settings()[pair], sight.settings()[pair + 1]);
            }
            // A line in chat fades after a while and that would count as a change.
            rig.act(mc -> mc.gui.hud.getChat().clearMessages(true));
            rig.ticks(STILL_TICKS);
            Path before = rig.frames().shot(sight.module() + " before");
            // Long enough apart for a slow flicker such as a portal's to show as one.
            List<Path> stills = new ArrayList<>();
            for (int still = 0; still < STILLS; still++) {
                rig.ticks(STILL_GAP);
                stills.add(rig.frames().shot(sight.module() + " still " + still));
            }
            // What the scene does by itself whilst the module is still off. The last still is held
            // against the first picture the way the picture with the module on will be.
            double idle = Pictures.changed(before, stills.getLast(), stills.subList(0, STILLS - 1));
            rig.client().enable(sight.module());
            rig.ticks(SHOWN_TICKS);
            Path on = rig.frames().shot(sight.module() + " on");
            rig.client().disable(sight.module());
            rig.ticks(SHOWN_TICKS);
            Path off = rig.frames().shot(sight.module() + " off");
            // And what it does once the module is off again. The sparks of a portal come and go.
            rig.ticks(SHOWN_TICKS);
            Path later = rig.frames().shot(sight.module() + " later");
            double shown = Pictures.changed(before, on, stills);
            double left = Pictures.changed(before, off, stills);
            double drift = Pictures.changed(off, later, stills);
            rig.note(String.format(Locale.ROOT, "%.3f%% of the picture changed and %.3f%% was still changed once it "
                + "went off. The scene alone changed %.3f%% before and %.3f%% after", shown * PER_CENT, left * PER_CENT,
                idle * PER_CENT, drift * PER_CENT));
            rig.expect(shown >= LEAST + 2 * idle, sight.module() + " changed no more of the picture than the scene"
                + " does by itself");
            rig.expect(left <= Math.max(LEAST, shown / 4) + drift, sight.module() + " left its mark on the picture");
        } finally {
            hideInterface(rig, false);
            rig.act(mc -> {
                mc.gui.setScreen(null);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            });
            rig.world().attempt("time set noon");
        }
    }

    // The whole category goes on over the still scene and off again. What two of them do to the
    // same frame must come undone as well as what one does.
    private void allAtOnce(Rig rig) {
        scene(rig);
        // An item turns as it lies. Over this many ticks it never looks the same twice.
        rig.world().attempt("kill @e[type=item]");
        rig.client().disable("HUD");
        List<Module> crowd = rig.client().modules().stream().filter(module -> module.getCategory() == Category.RENDER
            && module.isTogglable() && SmokeSuite.leftOut(module.getName(), false) == null
            && !rig.ask(mc -> module.isEnabled())).toList();
        rig.act(mc -> mc.gui.hud.getChat().clearMessages(true));
        rig.ticks(STILL_TICKS);
        Path before = rig.frames().shot("all before");
        rig.ticks(STILL_GAP);
        Path again = rig.frames().shot("all still");
        try {
            crowd.forEach(module -> rig.client().switchTo(module, true));
            rig.ticks(SHOWN_TICKS);
            Path on = rig.frames().shot("all on");
            crowd.forEach(module -> rig.client().switchTo(module, false));
            rig.act(mc -> {
                mc.gui.setScreen(null);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.gui.hud.getChat().clearMessages(true);
            });
            // Every chunk is meshed again and the sky finds its own colour.
            rig.ticks(SHOWN_TICKS * 4);
            Path off = rig.frames().shot("all off");
            double shown = Pictures.changed(before, on, again);
            double left = Pictures.changed(before, off, again);
            rig.note(String.format(Locale.ROOT, "%d modules changed %.3f%% of the picture and %.3f%% was still "
                + "changed once they were off", crowd.size(), shown * PER_CENT, left * PER_CENT));
            rig.expect(shown >= LEAST, "The whole category changed nothing in the picture");
            rig.expect(left <= LEAST, "The category left its mark on the picture");
        } finally {
            rig.world().attempt("time set noon");
        }
    }

    private static void openInventory(Rig rig) {
        rig.act(mc -> mc.gui.setScreen(new InventoryScreen(mc.player)));
    }

    // A lit portal in a frame of obsidian off to the left with a wall before it. The module
    // shows a portal through a wall. The swirl and the sparks of the portal stay behind it and
    // the picture holds nothing that moves by itself.
    private void portal(Rig rig) {
        World world = rig.world();
        world.fill(home.offset(-6, 0, -6), home.offset(-3, 4, -6), "obsidian");
        world.fill(home.offset(-5, 1, -6), home.offset(-4, 3, -6), "nether_portal[axis=x]");
        world.fill(home.offset(-6, 0, -5), home.offset(-3, 4, -5), "stone");
    }

    // A waypoint saved six blocks ahead of where the pictures are taken from.
    private void waypoint(Rig rig) {
        World world = rig.world();
        world.teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5 - 6, 180, 0);
        rig.client().command("waypoint add rigpoint");
        world.teleport(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 180, 0);
    }

    // What the modules have to draw on. Two mobs and a dropped item and a chest and an ore and
    // a hole a player could stand in. Nothing in it moves.
    private void scene(Rig rig) {
        World world = rig.world();
        Props.summon(rig, "husk", home.north(5), GROWN_STILL);
        Props.summon(rig, "cow", home.offset(-3, 0, -6), Props.STILL_MOB);
        Props.summon(rig, "item", home.offset(2, 0, -4),
            "{Item:{id:\"minecraft:diamond\",count:1},PickupDelay:32767,Age:-32768}");
        world.setBlock(home.offset(-2, 0, -4), "chest");
        world.setBlock(home.offset(3, 0, -6), "diamond_ore");
        BlockPos hole = home.offset(1, -1, -3);
        for (BlockPos wall : List.of(hole.north(), hole.east(), hole.south(), hole.west(), hole.below())) {
            world.setBlock(wall, "obsidian");
        }
        world.setBlock(hole, "air");
    }

    private static void hideInterface(Rig rig, boolean hidden) {
        rig.act(mc -> {
            if (mc.gui.hud.isHidden() != hidden) {
                mc.gui.hud.toggle();
            }
        });
    }

    // A sitting wolf that belongs to the player.
    private void pet(Rig rig) {
        Props.summon(rig, "wolf", home.offset(-1, 0, -3), "{NoAI:1b,PersistenceRequired:1b,Sitting:1b}");
        rig.world().command("execute as @e[type=wolf] run data modify entity @s Owner set from entity "
            + rig.world().player() + " UUID");
    }

    // The trail lies ahead of a player who walked backwards.
    private void breadcrumbs(Rig rig) {
        hideInterface(rig, true);
        try {
            rig.client().enable("Breadcrumbs");
            Motion.hold(rig, WALK_TICKS, BACK);
            rig.ticks(STILL_TICKS);
            Path on = rig.frames().shot("Breadcrumbs on");
            rig.ticks(STILL_GAP);
            Path again = rig.frames().shot("Breadcrumbs still");
            rig.client().disable("Breadcrumbs");
            rig.ticks(SHOWN_TICKS);
            Path off = rig.frames().shot("Breadcrumbs off");
            double shown = Pictures.changed(on, off, again);
            rig.note(String.format(Locale.ROOT, "%.3f%% of the picture was trail", shown * PER_CENT));
            rig.expect(shown >= LEAST, "No trail was drawn where the player walked");
        } finally {
            hideInterface(rig, false);
        }
    }

    private void trail(Rig rig) {
        int plain = particlesAfter(rig, FORWARD);
        rig.client().enable("Trail");
        int trailed = particlesAfter(rig, BACK);
        rig.note("A walk left " + plain + " particles and one with Trail left " + trailed);
        rig.expect(trailed > plain + WALK_TICKS / 2, "A walk with Trail on left no more particles than one without");
    }

    // The particles in the world straight after a walk that way. The game counts them by kind in
    // one line of words and numbers.
    private static int particlesAfter(Rig rig, int key) {
        Motion.hold(rig, WALK_TICKS, key);
        String counts = rig.ask(mc -> mc.particleEngine.countParticles());
        return NUMBER.matcher(counts).results().mapToInt(found -> Integer.parseInt(found.group())).sum();
    }

    private void noHurtCam(Rig rig) {
        hideInterface(rig, true);
        try {
            rig.ticks(STILL_TICKS);
            Path calm = rig.frames().shot("NoHurtCam calm");
            rig.ticks(STILL_GAP);
            Path again = rig.frames().shot("NoHurtCam still");
            double tilted = Pictures.changed(calm, hurtView(rig, "tilted"), again);
            rig.ticks(SHOWN_TICKS);
            rig.client().enable("NoHurtCam");
            double level = Pictures.changed(calm, hurtView(rig, "level"), again);
            rig.note(String.format(Locale.ROOT, "%.3f%% of the picture changed with a blow and %.3f%% with NoHurtCam "
                + "on", tilted * PER_CENT, level * PER_CENT));
            rig.expect(tilted >= LEAST, "The view did not tilt without NoHurtCam. That proves nothing");
            rig.expect(level < LEAST, "The view still tilts when the player is hurt");
        } finally {
            hideInterface(rig, false);
        }
    }

    // The picture a few ticks after the server hurt the player.
    private static Path hurtView(Rig rig, String name) {
        rig.world().command("damage " + rig.world().player() + " 1");
        rig.ticks(HURT_TICKS);
        return rig.frames().shot("NoHurtCam " + name);
    }

    // The mob flashes red when it is hurt whatever is on. Every picture is taken the same few
    // ticks after a blow and only the number is between them. The number is drawn on the
    // interface.
    private void damageNumbers(Rig rig) {
        Props.summon(rig, "husk", home.north(4), GROWN_STILL);
        rig.client().disable("HUD");
        rig.ticks(STILL_TICKS);
        Path plain = hurtMob(rig, "plain");
        rig.ticks(STILL_TICKS);
        Path same = hurtMob(rig, "same");
        rig.ticks(STILL_TICKS);
        rig.client().enable("DamageNumbers");
        Path numbered = hurtMob(rig, "numbered");
        double shown = Pictures.changed(plain, numbered, same);
        rig.note(String.format(Locale.ROOT, "%.3f%% of the picture was the number", shown * PER_CENT));
        rig.expect(shown >= LEAST, "No number was drawn over the mob");
    }

    private static Path hurtMob(Rig rig, String name) {
        rig.world().command("damage @e[type=husk,limit=1] 2");
        rig.ticks(NUMBER_TICKS);
        return rig.frames().shot("DamageNumbers " + name);
    }

    // The game takes one blur a frame and throws on a second. Every kind of screen Blur covers
    // comes up in turn. An inventory and one of the client's own menus and the chat.
    private void blurScreens(Rig rig) {
        scene(rig);
        rig.client().disable("HUD");
        rig.client().set("Blur", "Chat", ON);
        int sharp = edgesBehindChat(rig, "sharp");
        rig.client().enable("Blur");
        show(rig, mc -> new InventoryScreen(mc.player));
        show(rig, mc -> new ClickGuiScreen());
        int blurred = edgesBehindChat(rig, "blurred");
        rig.note("The world behind the chat showed " + sharp + " sharp edges and " + blurred + " with Blur on");
        rig.expect(rig.inWorld(), "The game left the world with Blur on");
        // No dark veil lies over the world behind the chat and its hardest edges outlast the blur.
        rig.expect(blurred * 4 < sharp * 3, "The world behind the chat stayed sharp");
    }

    private static int edgesBehindChat(Rig rig, String name) {
        rig.act(mc -> mc.gui.setScreen(new ChatScreen("", false)));
        try {
            rig.ticks(SHOWN_TICKS / 2);
            return Pictures.edges(rig.frames().shot("Blur chat " + name));
        } finally {
            rig.act(mc -> mc.gui.setScreen(null));
            rig.ticks(2);
        }
    }

    private static void show(Rig rig, Function<Minecraft, Screen> screen) {
        rig.act(mc -> mc.gui.setScreen(screen.apply(mc)));
        rig.ticks(SHOWN_TICKS / 2);
        rig.act(mc -> mc.gui.setScreen(null));
        rig.ticks(2);
    }

    // A pit down to the bottom of the world with the bedrock gone under its far end. The holes are
    // only drawn where they can be seen and the view tips down into the pit.
    private void voidPit(Rig rig) {
        World world = rig.world();
        world.attempt("fill " + Props.at(home.offset(-1, -3, -6)) + " " + Props.at(home.offset(1, -1, -2)) + " air");
        world.attempt("fill " + Props.at(home.offset(-1, -4, -6)) + " " + Props.at(home.offset(1, -4, -4)) + " air");
        rig.controls().look(NORTH, PIT_PITCH);
    }

    // A blur behind the dark veil of a menu moves no pixel far. It shows in how few sharp edges
    // are left beside the menu.
    private void blur(Rig rig) {
        scene(rig);
        rig.client().disable("HUD");
        openInventory(rig);
        try {
            rig.ticks(STILL_TICKS);
            int sharp = Pictures.edges(rig.frames().shot("Blur before"));
            rig.client().enable("Blur");
            rig.ticks(SHOWN_TICKS);
            int blurred = Pictures.edges(rig.frames().shot("Blur on"));
            rig.client().disable("Blur");
            rig.ticks(SHOWN_TICKS);
            int after = Pictures.edges(rig.frames().shot("Blur off"));
            rig.note("The world beside the inventory showed " + sharp + " sharp edges and " + blurred
                + " with Blur on and " + after + " once it went off");
            rig.expect(blurred * 2 < sharp, "The world behind the inventory stayed sharp");
            rig.expect(after * 10 > sharp * 9, "The world stayed blurred after Blur went off");
        } finally {
            rig.act(mc -> mc.gui.setScreen(null));
        }
    }

    private void marker(Rig rig) {
        hideInterface(rig, true);
        try {
            rig.client().enable("Marker");
            rig.controls().aimAtBlock(home.offset(0, -1, -3), Direction.UP);
            rig.ticks(SHOWN_TICKS);
            rig.controls().press(InputConstants.KEY_BACKSLASH);
            rig.ticks(STILL_TICKS);
            Path on = rig.frames().shot("Marker on");
            rig.ticks(STILL_GAP);
            Path again = rig.frames().shot("Marker still");
            rig.client().disable("Marker");
            rig.ticks(SHOWN_TICKS);
            Path off = rig.frames().shot("Marker off");
            double shown = Pictures.changed(on, off, again);
            rig.note(String.format(Locale.ROOT, "%.3f%% of the picture was the marker", shown * PER_CENT));
            rig.expect(shown >= LEAST, "No marker was drawn where the key was pressed");
        } finally {
            hideInterface(rig, false);
        }
    }

    // The game is slowed by hand until the module trims the render distance. Once the stalls stop
    // and the module goes off every chunk is drawn again.
    private void steadyFrames(Rig rig) {
        rig.client().set("SteadyFrames", "Least distance", "2");
        int own = rig.ask(mc -> mc.options.getEffectiveRenderDistance());
        rig.client().enable("SteadyFrames");
        int trimmed = own;
        for (int tick = 0; tick < STALLED_TICKS && trimmed >= own; tick++) {
            rig.act(mc -> {
                try {
                    Thread.sleep(STALL_MILLIS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            });
            rig.ticks(1);
            trimmed = rig.ask(mc -> mc.options.getEffectiveRenderDistance());
        }
        rig.client().disable("SteadyFrames");
        rig.ticks(SHOWN_TICKS);
        int after = rig.ask(mc -> mc.options.getEffectiveRenderDistance());
        rig.note("The game drew " + own + " chunks and " + trimmed + " whilst it was slow and " + after
            + " afterwards");
        rig.expect(trimmed < own, "The render distance never came down whilst the frames were slow");
        rig.expect(after == own, "The render distance stayed down after SteadyFrames went off");
    }
}
