package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.mixin.MultiPlayerGameModeAccessor;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.movement.Autopilot;
import com.jellypudding.offlineclient.modules.render.Weather;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.time.DateTimeException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

// Words in braces such as {fps} that turn into live values. A word can carry an argument after
// a space such as {count ender_pearl} and a number can be told its decimals such as {speed 2}.
// Game thread only.
public final class LiveText {

    // What follows the words You can use in the description of a text that takes the words.
    public static final String WORDS = "live words in braces such as {fps} and {count ender_pearl}. Type .words in"
        + " chat to see every one";

    private static final Minecraft MC = OfflineClient.MC;

    private static final char OPEN = '{';
    private static final char CLOSE = '}';

    // Day starts at six.
    private static final long DAY_TICKS = 24000;
    private static final long HOUR_TICKS = 1000;
    private static final long SIX_AM = 6 * HOUR_TICKS;
    private static final long MINUTES_PER_HOUR = 60;

    private static final double PERCENT = 100;

    // A number holds about this many digits. Decimals past them are noise.
    private static final int MOST_DECIMALS = 15;

    private static final String CLOCK_FORM = "HH:mm";
    private static final String DATE_FORM = "d MMM yyyy";
    private static final int MOST_FORMS = 32;

    // Words that read as text are cut to this many characters. A server names items and mobs and
    // dimensions and its resource pack names blocks and biomes. Nothing stops either sending a book.
    private static final int LONGEST_TEXT = 64;

    private static final double SLOWEST_WALK = 0.5;

    // The number is what a rule compares and is not a number for plain text.
    public record Reading(String text, double number) {

        private static Reading text(String text) {
            return new Reading(text, Double.NaN);
        }

        private static Reading whole(int count) {
            return new Reading(String.valueOf(count), count);
        }

        public boolean hasNumber() {
            return !Double.isNaN(number);
        }

        public boolean unreadable() {
            return this == UNREADABLE;
        }
    }

    private static final Reading NOTHING = Reading.text("");
    private static final Reading UNREADABLE = Reading.text("?");

    // Null when the word has nothing to say. The argument is null when none was typed.
    private interface Reader {
        Reading read(LocalPlayer player, String argument);
    }

    // Takes is what the help calls the argument of the word and null for a word without one
    // of its own. A figure takes its decimals in that place.
    private record Word(String name, String takes, String says, boolean figure, Reader reads) {

        private String pattern() {
            return OPEN + (takes == null ? name : name + " " + takes) + CLOSE;
        }
    }

    private record Group(String name, List<Word> words) {
    }

    public record Help(String group, String name, String pattern, String says, boolean figure) {
    }

    private static final List<Group> GROUPS = List.of(you(), place(), movement(), items(), crosshair(), world(),
        server(), client());
    private static final Map<String, Word> BY_NAME = byName();

    private static final Map<String, Optional<DateTimeFormatter>> FORMS = new BoundedMap<>(MOST_FORMS);
    // Counts kept for the tick they were made in. The tick is told by your entity id and your
    // tick count. Holding the player itself would hold on to a world that was left.
    private static final Map<String, Reading> KEPT = new HashMap<>();
    private static long keptTick = Long.MIN_VALUE;

    private LiveText() {
    }

    private static Group you() {
        return new Group("You", List.of(
            text("username", "The name of your account.", player -> player.getGameProfile().name()),
            figure("health", "Your health with absorption added.", 0, EntityUtil::totalHealth),
            figure("absorption", "Your absorption alone.", 0, LocalPlayer::getAbsorptionAmount),
            figure("hunger", "Your food points out of twenty.", 0, player -> player.getFoodData().getFoodLevel()),
            figure("saturation", "The saturation that drains before your hunger does.", 1,
                player -> player.getFoodData().getSaturationLevel()),
            figure("level", "Your experience level.", 0, player -> player.experienceLevel),
            figure("xp", "How far along you are to the next level.", 0, "%",
                player -> player.experienceProgress * PERCENT),
            figure("xptotal", "Every experience point you hold.", 0, player -> player.totalExperience),
            text("gamemode", "Your game mode.", player -> gameMode()),
            asked("effect", "name", "The time an effect on you has left such as {effect strength}.",
                LiveText::effectTime),
            asked("effectlevel", "name", "The level of an effect on you and nought without it.",
                LiveText::effectLevel)));
    }

    private static Group place() {
        return new Group("Place", List.of(
            along("x", "The block you stand in from west to east.", Entity::position, Direction.Axis.X),
            along("y", "The height of the block you stand in.", Entity::position, Direction.Axis.Y),
            along("z", "The block you stand in from north to south.", Entity::position, Direction.Axis.Z),
            bare("otherx", "The x of the matching block in the other dimension.",
                player -> otherSide(player, Direction.Axis.X)),
            bare("otherz", "The z of the matching block in the other dimension.",
                player -> otherSide(player, Direction.Axis.Z)),
            along("camerax", "Where the camera is from west to east.", player -> camera(), Direction.Axis.X),
            along("cameray", "The height of the camera.", player -> camera(), Direction.Axis.Y),
            along("cameraz", "Where the camera is from north to south.", player -> camera(), Direction.Axis.Z),
            text("dimension", "The dimension you are in.",
                player -> player.level().dimension().identifier().getPath()),
            text("biome", "The biome you stand in.", LiveText::biome),
            text("direction", "The way you face.", player -> player.getDirection().getName()),
            figure("yaw", "How far you are turned.", 1, player -> Mth.wrapDegrees(player.getYRot())),
            figure("pitch", "How far you look up or down.", 1, player -> Mth.wrapDegrees(player.getXRot()))));
    }

    private static Group movement() {
        return new Group("Movement", List.of(
            figure("speed", "Your speed along the ground in blocks a second.", 1, player -> EntityUtil.ownSpeed()),
            figure("speedx", "Your speed from west to east in blocks a second.", 1,
                player -> EntityUtil.ownVelocity().x),
            figure("speedy", "How fast you rise or fall in blocks a second.", 1,
                player -> EntityUtil.ownVelocity().y),
            figure("speedz", "Your speed from north to south in blocks a second.", 1,
                player -> EntityUtil.ownVelocity().z),
            bare("eta", "How long a goto walk or an autopilot flight still takes.", player -> timeLeft())));
    }

    private static Group items() {
        return new Group("Items", List.of(
            text("held", "The name of the item in your hand.", player -> nameOf(player.getMainHandItem())),
            figure("heldcount", "How many of it that hand holds.", 0, player -> player.getMainHandItem().getCount()),
            figure("helduses", "How many uses the item in your hand has left.", 0,
                player -> usesLeft(player.getMainHandItem())),
            figure("durability", "The share of its durability the item in your hand has left.", 0, "%",
                player -> durability(player.getMainHandItem())),
            text("offhand", "The name of the item in your other hand.", player -> nameOf(player.getOffhandItem())),
            figure("offhandcount", "How many of it that hand holds.", 0,
                player -> player.getOffhandItem().getCount()),
            figure("offhanduses", "How many uses the item in your other hand has left.", 0,
                player -> usesLeft(player.getOffhandItem())),
            figure("offhanddurability", "The share of its durability the item in your other hand has left.", 0, "%",
                player -> durability(player.getOffhandItem())),
            bare("totems", "The totems you carry.",
                player -> kept(player, "totems", () -> Reading.whole(Totems.carried()))),
            asked("count", "item", "How many of an item you carry such as {count ender_pearl}.",
                LiveText::carried)));
    }

    private static Group crosshair() {
        return new Group("Crosshair", List.of(
            text("lookingat", "The name of the block or entity under your crosshair.", player -> lookingAt()),
            along("lookingx", "Where that block or entity is from west to east.", player -> lookedAt(),
                Direction.Axis.X),
            along("lookingy", "The height of that block or entity.", player -> lookedAt(), Direction.Axis.Y),
            along("lookingz", "Where that block or entity is from north to south.", player -> lookedAt(),
                Direction.Axis.Z),
            figure("lookinghealth", "The health of the mob or player under your crosshair.", 0,
                player -> lookedAtHealth()),
            figure("breaking", "How far along you are with the block you are mining.", 0, "%",
                player -> breaking())));
    }

    private static Group world() {
        return new Group("World", List.of(
            text("time", "The time of day the server keeps whatever TimeChanger shows.", LiveText::dayTime),
            text("weather", "The weather as clear or rain or snow or thunder.", LiveText::weather),
            text("difficulty", "The difficulty of the world.",
                player -> player.level().getDifficulty().getDisplayName().getString())));
    }

    private static Group server() {
        return new Group("Server", List.of(
            text("server", "The address of the server.",
                player -> Objects.requireNonNullElse(ServerInfo.address(), "singleplayer")),
            figure("online", "How many players the tab list shows.", 0, player -> ServerInfo.online()),
            figure("ping", "Your ping in milliseconds.", 0, player -> ServerInfo.ping()),
            figure("tps", "The tick rate of the server.", 1, player -> TickRate.INSTANCE.tps())));
    }

    private static Group client() {
        return new Group("Client", List.of(
            figure("fps", "The frames a second the game draws.", 0, player -> MC.getFps()),
            figure("cps", "Your left clicks a second.", 0,
                player -> ClickRate.perSecond(InputConstants.MOUSE_BUTTON_LEFT)),
            figure("rightcps", "Your right clicks a second.", 0,
                player -> ClickRate.perSecond(InputConstants.MOUSE_BUTTON_RIGHT)),
            new Word("clock", "form", "The time on your computer. A form such as {clock h:mm a} changes how it"
                + " reads.", false, (player, argument) -> dated(argument, CLOCK_FORM)),
            new Word("date", "form", "The date today. A form such as {date dd/MM/yyyy} changes how it reads.",
                false, (player, argument) -> dated(argument, DATE_FORM)),
            text("version", "The version of the game.", player -> SharedConstants.getCurrentVersion().name()),
            asked("module", "name", "Whether a module is on or off such as {module killaura}.",
                LiveText::moduleState),
            asked("setting", "module name", "What a setting of a module holds such as {setting killaura range}."
                + " Typed text is never shown.", LiveText::settingValue)));
    }

    private static Map<String, Word> byName() {
        Map<String, Word> words = new HashMap<>();
        for (Group group : GROUPS) {
            for (Word word : group.words()) {
                words.put(word.name(), word);
            }
        }
        return words;
    }

    // A word that takes no argument. One typed after it cannot be read.
    private static Word bare(String name, String says, Function<LocalPlayer, Reading> read) {
        return new Word(name, null, says, false,
            (player, argument) -> argument == null ? read.apply(player) : UNREADABLE);
    }

    private static Word text(String name, String says, Function<LocalPlayer, String> read) {
        return bare(name, says, player -> Reading.text(bounded(read.apply(player))));
    }

    // Cut short and without braces. A line that is filled again never finds a word inside a value.
    private static String bounded(String text) {
        String cut = StringUtil.truncateStringIfNecessary(text, LONGEST_TEXT, true);
        return cut.replace(String.valueOf(OPEN), "").replace(String.valueOf(CLOSE), "");
    }

    private static Word figure(String name, String says, int decimals, ToDoubleFunction<LocalPlayer> read) {
        return figure(name, says, decimals, "", read);
    }

    // A number shown to its usual decimals unless the argument asks for others. The unit is
    // written straight after it. A reading that is not a number says nothing.
    private static Word figure(String name, String says, int decimals, String unit,
                               ToDoubleFunction<LocalPlayer> read) {
        return new Word(name, null, says, true, (player, argument) -> {
            int places = argument == null ? decimals : decimals(argument);
            if (places < 0) {
                return UNREADABLE;
            }
            double value = read.applyAsDouble(player);
            return Double.isNaN(value) ? null : new Reading(format(value, places) + unit, value);
        });
    }

    // One coordinate of a spot. It reads as the block the spot is in until decimals are asked
    // for. A null spot says nothing.
    private static Word along(String name, String says, Function<LocalPlayer, Vec3> spot, Direction.Axis axis) {
        return new Word(name, null, says, true, (player, argument) -> {
            int places = argument == null ? 0 : decimals(argument);
            if (places < 0) {
                return UNREADABLE;
            }
            Vec3 at = spot.apply(player);
            if (at == null) {
                return null;
            }
            double exact = at.get(axis);
            return new Reading(argument == null ? String.valueOf(Mth.floor(exact)) : format(exact, places), exact);
        });
    }

    // A word that needs an argument of its own. Without one it cannot be read.
    private static Word asked(String name, String takes, String says, Reader read) {
        return new Word(name, takes, says, false,
            (player, argument) -> argument == null ? UNREADABLE : read.read(player, argument));
    }

    private static int decimals(String argument) {
        try {
            int places = Integer.parseInt(argument);
            return places < 0 ? -1 : Math.min(places, MOST_DECIMALS);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String format(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static Reading kept(LocalPlayer player, String key, Supplier<Reading> work) {
        long tick = (long) player.getId() << Integer.SIZE | Integer.toUnsignedLong(player.tickCount);
        if (tick != keptTick) {
            KEPT.clear();
            keptTick = tick;
        }
        return KEPT.computeIfAbsent(key, asked -> work.get());
    }

    public static List<Help> help() {
        List<Help> help = new ArrayList<>();
        for (Group group : GROUPS) {
            for (Word word : group.words()) {
                help.add(new Help(group.name(), word.name(), word.pattern(), word.says(), word.figure()));
            }
        }
        return help;
    }

    // Braces that hold no word stay as they were typed. Each character is looked at once and a
    // value is never read again.
    public static String fill(String text) {
        if (text.indexOf(OPEN) < 0 || text.indexOf(CLOSE) < 0) {
            return text;
        }
        LocalPlayer player = MC.player;
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        // The nearest brace since the last closing one opens the word. Minus one for none.
        int open = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == OPEN) {
                open = i;
            } else if (c == CLOSE) {
                Reading held = open < 0 ? null : held(player, text.substring(open + 1, i));
                if (held != null) {
                    out.append(text, at, open).append(held.text());
                    at = i + 1;
                }
                open = -1;
            }
        }
        return out.append(text, at, text.length()).toString();
    }

    // The text cut into lines at each typed backslash and n and then filled. A value never
    // starts a line of its own.
    public static List<String> lines(String text) {
        return TextMarkup.LINE_BREAK.splitAsStream(text).map(LiveText::fill).toList();
    }

    // Empty for a word it does not know.
    public static String value(String key) {
        Reading held = held(MC.player, key);
        return held == null ? "" : held.text();
    }

    // Null for a word it does not know.
    public static Reading read(String key) {
        return held(MC.player, key);
    }

    private static Word wordOf(String key) {
        int space = key.indexOf(' ');
        return BY_NAME.get((space < 0 ? key : key.substring(0, space)).toLowerCase(Locale.ROOT));
    }

    // Outside a world every word says nothing.
    private static Reading held(LocalPlayer player, String typed) {
        String key = typed.trim();
        Word word = wordOf(key);
        if (word == null) {
            return null;
        }
        if (player == null) {
            return NOTHING;
        }
        int space = key.indexOf(' ');
        Reading held = word.reads().read(player, space < 0 ? null : key.substring(space + 1).trim());
        return held == null ? NOTHING : held;
    }

    // Spaces read as the underscores of an id.
    private static Identifier idOf(String argument) {
        return Identifier.tryParse(argument.toLowerCase(Locale.ROOT).replace(' ', '_'));
    }

    private static Reading carried(LocalPlayer player, String argument) {
        Identifier id = idOf(argument);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return UNREADABLE;
        }
        return kept(player, "count " + id, () -> Reading.whole(
            InventoryUtil.countWithOffhand(BuiltInRegistries.ITEM.getValue(id), InventoryUtil.WHOLE_INVENTORY)));
    }

    private static Holder<MobEffect> effectNamed(String argument) {
        Identifier id = idOf(argument);
        return id == null ? null : BuiltInRegistries.MOB_EFFECT.get(id).orElse(null);
    }

    // An effect that never ends compares as longer than any number. One you do not have says
    // nothing.
    private static Reading effectTime(LocalPlayer player, String argument) {
        Holder<MobEffect> effect = effectNamed(argument);
        if (effect == null) {
            return UNREADABLE;
        }
        MobEffectInstance on = player.getEffect(effect);
        if (on == null) {
            return null;
        }
        if (on.isInfiniteDuration()) {
            return new Reading(bounded(Component.translatable("effect.duration.infinite").getString()),
                Double.POSITIVE_INFINITY);
        }
        float rate = player.level().tickRateManager().tickrate();
        return new Reading(StringUtil.formatTickDuration(on.getDuration(), rate), on.getDuration() / rate);
    }

    private static Reading effectLevel(LocalPlayer player, String argument) {
        Holder<MobEffect> effect = effectNamed(argument);
        if (effect == null) {
            return UNREADABLE;
        }
        MobEffectInstance on = player.getEffect(effect);
        return Reading.whole(on == null ? 0 : on.getAmplifier() + 1);
    }

    private static Reading moduleState(LocalPlayer player, String argument) {
        Module module = OfflineClient.INSTANCE.getModuleManager().get(argument);
        return module == null ? UNREADABLE : Reading.text(module.isEnabled() ? "on" : "off");
    }

    // The module is the first word and the setting the rest. Text a player typed into a setting
    // may be a password or an address and a line with this word in it may be sent to the server.
    // A long list is cut short.
    private static Reading settingValue(LocalPlayer player, String argument) {
        int space = argument.indexOf(' ');
        Module module = space < 0 ? null : OfflineClient.INSTANCE.getModuleManager().get(argument.substring(0, space));
        Setting<?> setting = module == null ? null : module.getSetting(argument.substring(space + 1));
        if (setting == null || setting instanceof TextSetting || setting instanceof ActionSetting) {
            return UNREADABLE;
        }
        return new Reading(bounded(setting.getValueString()),
            setting instanceof NumberSetting number ? number.getValue() : Double.NaN);
    }

    // An autopilot flight comes before a goto walk.
    private static Reading timeLeft() {
        Autopilot autopilot = Modules.active(Autopilot.class);
        OptionalDouble flight = autopilot == null ? OptionalDouble.empty() : autopilot.secondsLeft();
        double speed = EntityUtil.ownSpeed();
        double seconds;
        if (flight.isPresent()) {
            seconds = flight.getAsDouble();
        } else if (Travel.goal() != null && speed >= SLOWEST_WALK) {
            seconds = Travel.distanceLeft() / speed;
        } else {
            return null;
        }
        return new Reading(ChatUtil.shortAge(Math.round(seconds * TimeUnit.SECONDS.toMillis(1))), seconds);
    }

    // A form the clock cannot read gives a question mark.
    private static Reading dated(String argument, String usual) {
        String typed = argument == null ? usual : argument;
        Optional<DateTimeFormatter> form = FORMS.computeIfAbsent(typed, LiveText::formOf);
        if (form.isEmpty()) {
            return UNREADABLE;
        }
        try {
            return Reading.text(form.get().format(ZonedDateTime.now()));
        } catch (DateTimeException e) {
            return UNREADABLE;
        }
    }

    private static Optional<DateTimeFormatter> formOf(String typed) {
        try {
            return Optional.of(DateTimeFormatter.ofPattern(typed, Locale.UK));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Reading otherSide(LocalPlayer player, Direction.Axis axis) {
        BlockPos other = Dimension.acrossPortal(player.position(), ServerInfo.dimension());
        return other == null ? null : Reading.whole(other.get(axis));
    }

    private static Vec3 camera() {
        return MC.gameRenderer.mainCamera().position();
    }

    private static String biome(LocalPlayer player) {
        return player.level().getBiome(player.blockPosition()).unwrapKey()
            .map(key -> Component.translatableWithFallback(Util.makeDescriptionId("biome", key.identifier()),
                ChatUtil.words(key.identifier())).getString())
            .orElse("");
    }

    private static String nameOf(ItemStack stack) {
        return stack.isEmpty() ? "" : stack.getHoverName().getString();
    }

    // Not a number for an item that never wears.
    private static double usesLeft(ItemStack stack) {
        return stack.isDamageableItem() ? ItemUtil.usesLeft(stack) : Double.NaN;
    }

    private static double durability(ItemStack stack) {
        return stack.isDamageableItem() ? ItemUtil.durabilityPercent(stack) : Double.NaN;
    }

    private static String gameMode() {
        MultiPlayerGameMode mode = MC.gameMode;
        return mode == null ? "" : mode.getPlayerMode().getShortDisplayName().getString();
    }

    private static String lookingAt() {
        HitResult hit = MC.hitResult;
        if (hit instanceof EntityHitResult entityHit) {
            return EntityUtil.displayNameOf(entityHit.getEntity());
        }
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return MC.level.getBlockState(blockHit.getBlockPos()).getBlock().getName().getString();
        }
        return "";
    }

    private static Vec3 lookedAt() {
        HitResult hit = MC.hitResult;
        if (hit instanceof EntityHitResult entityHit) {
            return entityHit.getEntity().position();
        }
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return Vec3.atLowerCornerOf(blockHit.getBlockPos());
        }
        return null;
    }

    private static double lookedAtHealth() {
        return MC.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity living
            ? EntityUtil.totalHealth(living) : Double.NaN;
    }

    // Not a number whilst you mine nothing.
    private static double breaking() {
        MultiPlayerGameMode mode = MC.gameMode;
        if (mode == null || !mode.isDestroying()) {
            return Double.NaN;
        }
        return Math.clamp(((MultiPlayerGameModeAccessor) mode).offlineclient$destroyProgress(), 0, 1) * PERCENT;
    }

    // The time the server keeps whatever TimeChanger shows. It reads as a twenty four hour clock
    // and nought ticks is six in the morning.
    private static String dayTime(LocalPlayer player) {
        long ticks = Math.floorMod(Weather.real(player.level()::getOverworldClockTime) + SIX_AM, DAY_TICKS);
        return String.format(Locale.ROOT, "%02d:%02d", ticks / HOUR_TICKS,
            ticks % HOUR_TICKS * MINUTES_PER_HOUR / HOUR_TICKS);
    }

    // The weather the server runs whatever Weather shows. Rain falls as snow where the biome you
    // stand in is cold enough.
    private static String weather(LocalPlayer player) {
        Level level = player.level();
        if (Weather.real(level::isThundering)) {
            return "thunder";
        }
        if (!Weather.real(level::isRaining)) {
            return "clear";
        }
        BlockPos feet = player.blockPosition();
        Biome.Precipitation falls = level.getBiome(feet).value().getPrecipitationAt(feet, level.getSeaLevel());
        return falls == Biome.Precipitation.SNOW ? "snow" : "rain";
    }
}
