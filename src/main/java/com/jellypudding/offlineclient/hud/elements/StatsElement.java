package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.Backdrop;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.ListMode;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RankSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.PlayerStats;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

// Your statistics on this server as the server itself counts them. The rows come in the
// order you drag them and each lines up with the side the element is anchored to.
public final class StatsElement extends HudElement {

    // The rows on offer with the item each shows beside it in the settings.
    public enum Row {
        PLAY_TIME(Items.CLOCK),
        DISTANCE(Items.LEATHER_BOOTS),
        ELYTRA_DISTANCE(Items.ELYTRA),
        BLOCKS_MINED(Items.IRON_PICKAXE),
        MOBS_KILLED(Items.ZOMBIE_HEAD),
        PLAYERS_KILLED(Items.PLAYER_HEAD),
        DEATHS(Items.SKELETON_SKULL),
        SINCE_DEATH(Items.TOTEM_OF_UNDYING),
        SINCE_SLEEP(Items.BED.red()),
        ITEMS_CRAFTED(Items.CRAFTING_TABLE),
        ITEMS_USED(Items.FLINT_AND_STEEL),
        ITEMS_PICKED_UP(Items.HOPPER),
        DAMAGE_DEALT(Items.IRON_SWORD),
        DAMAGE_TAKEN(Items.SHIELD),
        JUMPS(Items.RABBIT_FOOT);

        private final Item icon;

        Row(Item icon) {
            this.icon = icon;
        }
    }

    public enum Period { ALL_TIME, THIS_VISIT }

    // The rows are worked out afresh this often. The sums walk every statistic you have.
    private static final long REBUILD_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private static final double CM_PER_KM = 100_000;
    // Damage is counted in tenths of a health point and a heart holds two.
    private static final double TENTHS_PER_HEART = 20;
    private static final long TICKS_PER_HOUR = TimeUnit.HOURS.toSeconds(1) * SharedConstants.TICKS_PER_SECOND;
    // A rate from less than a minute of play swings too wildly to read.
    private static final long LEAST_RATE_TICKS = TimeUnit.MINUTES.toSeconds(1) * SharedConstants.TICKS_PER_SECOND;
    // Below this an hourly rate keeps a decimal.
    private static final double WHOLE_RATE = 10;

    // Every way of getting about the game counts towards distance.
    private static final List<Identifier> TRAVEL = List.of(Stats.WALK_ONE_CM, Stats.CROUCH_ONE_CM,
        Stats.SPRINT_ONE_CM, Stats.WALK_ON_WATER_ONE_CM, Stats.FALL_ONE_CM, Stats.CLIMB_ONE_CM, Stats.FLY_ONE_CM,
        Stats.WALK_UNDER_WATER_ONE_CM, Stats.MINECART_ONE_CM, Stats.BOAT_ONE_CM, Stats.PIG_ONE_CM,
        Stats.HAPPY_GHAST_ONE_CM, Stats.HORSE_ONE_CM, Stats.AVIATE_ONE_CM, Stats.SWIM_ONE_CM, Stats.STRIDER_ONE_CM,
        Stats.NAUTILUS_ONE_CM);

    private static final Set<Row> FIRST_SHOWN = EnumSet.of(Row.PLAY_TIME, Row.DISTANCE, Row.BLOCKS_MINED,
        Row.MOBS_KILLED, Row.PLAYERS_KILLED, Row.DEATHS, Row.SINCE_DEATH);

    // A picked list that narrows one row to some entries or leaves some out. An empty list
    // counts every entry. It only shows whilst its row does.
    private static final class Filter<T> {

        private final RegistryListSetting<T> list;
        private final EnumSetting<ListMode> mode;

        Filter(RankSetting<Row> rows, Row row, String noun, Registry<T> registry, Predicate<T> offered) {
            String rowName = EnumSetting.label(row);
            String rowWords = rowName.toLowerCase(Locale.ROOT);
            list = new RegistryListSetting<>(rowName + " list",
                "The " + noun + " that count towards " + rowWords + " or are left out of it. Click to pick them."
                    + " Empty counts every one.", registry, List.<T>of())
                .only(offered)
                .under(rows, () -> rows.ranked().contains(row));
            mode = ListMode.setting(rowName + " list mode", ListMode.WHITELIST,
                "Only the listed " + noun + " count.", "All " + noun + " count but the listed ones.")
                .under(list, () -> !list.resolved().isEmpty());
        }

        Setting<?>[] settings() {
            return new Setting<?>[] {list, mode};
        }

        boolean isEmpty() {
            return list.resolved().isEmpty();
        }

        boolean counts(T entry) {
            return isEmpty() || mode.getValue().admits(list.contains(entry));
        }

        // A row counting one entry alone is named after it. Null otherwise.
        String onlyName() {
            Set<T> picked = list.resolved();
            return mode.is(ListMode.WHITELIST) && picked.size() == 1 ? list.displayName(picked.iterator().next())
                : null;
        }
    }

    private final RankSetting<Row> rows = new RankSetting<>("Statistics rows",
        "The rows shown in the order you drag them. Click a row to show or hide it.", Row.class,
        row -> new ItemStack(row.icon), FIRST_SHOWN::contains);
    private final Filter<Block> mined = new Filter<>(rows, Row.BLOCKS_MINED, "blocks", BuiltInRegistries.BLOCK,
        block -> true);
    // Players have a row of their own and never count as mobs.
    private final Filter<EntityType<?>> killed = new Filter<>(rows, Row.MOBS_KILLED, "mobs",
        BuiltInRegistries.ENTITY_TYPE, type -> type != EntityTypes.PLAYER);
    private final Filter<Item> crafted = new Filter<>(rows, Row.ITEMS_CRAFTED, "items", BuiltInRegistries.ITEM,
        item -> true);
    private final Filter<Item> used = new Filter<>(rows, Row.ITEMS_USED, "items", BuiltInRegistries.ITEM,
        item -> true);
    private final Filter<Item> pickedUp = new Filter<>(rows, Row.ITEMS_PICKED_UP, "items", BuiltInRegistries.ITEM,
        item -> true);
    private final EnumSetting<Period> period = new EnumSetting<>("Statistics period",
        "Which part of your time on the server the rows count.", Period.ALL_TIME)
        .describe(Period.ALL_TIME, "Everything since you first joined this server.")
        .describe(Period.THIS_VISIT, "Only what happened on this visit since your statistics were first shown here"
            + " or on the game's statistics screen.");
    private final BoolSetting perHour = new BoolSetting("Statistics per hour",
        "Adds how many of each you gain in an hour of play over the same period.", false);
    private final NumberSetting refresh = new NumberSetting("Statistics refresh",
        "How often the server is asked for your statistics. Each ask also resets its idle kick timer.",
        5, 1, 60, 1, " seconds").min(1);
    private final ColorSetting color = new ColorSetting("Statistics colour",
        "Colour of the row names. The numbers stay white.", 190, 0.15f, 0.85f, false);
    private final BoolSetting shadow = new BoolSetting("Statistics shadow",
        "Draws a shadow behind the text.", true);
    private final Backdrop backdrop = new Backdrop("Statistics", "the rows", false);

    // Null until first built.
    private List<String> lines;
    private long builtAt;

    public StatsElement() {
        super("Statistics", "Your statistics on this server as the server keeps them such as play time and kills."
            + " They refresh whilst they show.", false, 0, 60);
        add(rows);
        add(mined.settings());
        add(killed.settings());
        add(crafted.settings());
        add(used.settings());
        add(pickedUp.settings());
        add(period, perHour, refresh, color, shadow);
        add(backdrop.settings());
    }

    @Override
    public boolean visible() {
        if (!isActive() || OfflineClient.MC.player == null) {
            return false;
        }
        PlayerStats.INSTANCE.want(refresh.getValue());
        return PlayerStats.INSTANCE.ready() && !lines().isEmpty();
    }

    private List<String> lines() {
        long now = System.nanoTime();
        if (lines == null || now - builtAt >= REBUILD_NANOS) {
            lines = build();
            builtAt = now;
        }
        return lines;
    }

    private List<String> build() {
        List<String> built = new ArrayList<>();
        if (OfflineClient.MC.player == null) {
            return built;
        }
        boolean visit = period.is(Period.THIS_VISIT);
        long elapsed = PlayerStats.INSTANCE.ticksSinceAnswer();
        long played = custom(Stats.PLAY_TIME, visit) + elapsed;
        for (Row row : rows.ranked()) {
            String line = line(row, visit, elapsed, played);
            if (line != null) {
                built.add(line);
            }
        }
        return built;
    }

    // Null for a row with nothing to say yet such as the time since your last death
    // before you have died.
    private String line(Row row, boolean visit, long elapsed, long played) {
        String label = EnumSetting.label(row);
        return switch (row) {
            case PLAY_TIME -> text(label, span(played), null);
            case DISTANCE -> distance(label, travelled(visit), played);
            case ELYTRA_DISTANCE -> distance(label, custom(Stats.AVIATE_ONE_CM, visit), played);
            case BLOCKS_MINED -> filtered(label, mined, " mined", sum(Stats.BLOCK_MINED, mined, visit), played);
            case MOBS_KILLED -> filtered(label, killed, " kills", mobKills(visit), played);
            case PLAYERS_KILLED -> count(label, custom(Stats.PLAYER_KILLS, visit), played);
            case DEATHS -> count(label, custom(Stats.DEATHS, visit), played);
            case SINCE_DEATH -> custom(Stats.DEATHS, false) > 0
                ? text(label, span(custom(Stats.TIME_SINCE_DEATH, false) + elapsed), null) : null;
            case SINCE_SLEEP -> text(label, span(custom(Stats.TIME_SINCE_REST, false) + elapsed), null);
            case ITEMS_CRAFTED -> filtered(label, crafted, " crafted", sum(Stats.ITEM_CRAFTED, crafted, visit), played);
            case ITEMS_USED -> filtered(label, used, " used", sum(Stats.ITEM_USED, used, visit), played);
            case ITEMS_PICKED_UP -> filtered(label, pickedUp, " picked up", sum(Stats.ITEM_PICKED_UP, pickedUp, visit),
                played);
            case DAMAGE_DEALT -> hearts(label, custom(Stats.DAMAGE_DEALT, visit), played);
            case DAMAGE_TAKEN -> hearts(label, custom(Stats.DAMAGE_TAKEN, visit), played);
            case JUMPS -> count(label, custom(Stats.JUMP, visit), played);
        };
    }

    private static long custom(Identifier id, boolean visit) {
        return PlayerStats.INSTANCE.custom(id, visit);
    }

    private static <T> long sum(StatType<T> type, Filter<T> filter, boolean visit) {
        return PlayerStats.INSTANCE.sum(type, filter::counts, visit);
    }

    private long travelled(boolean visit) {
        long total = 0;
        for (Identifier way : TRAVEL) {
            total += custom(way, visit);
        }
        return total;
    }

    // The game keeps its own total of mob kills. A list needs the kills of each kind added up.
    private long mobKills(boolean visit) {
        if (killed.isEmpty()) {
            return custom(Stats.MOB_KILLS, visit);
        }
        return PlayerStats.INSTANCE.sum(Stats.ENTITY_KILLED, type -> type != EntityTypes.PLAYER && killed.counts(type),
            visit);
    }

    // A row narrowed to one entry takes that entry's name such as Diamond Ore mined.
    private <T> String filtered(String label, Filter<T> filter, String suffix, long amount, long played) {
        String only = filter.onlyName();
        return count(only == null ? label : only + suffix, amount, played);
    }

    private String count(String label, long amount, long played) {
        return text(label, String.valueOf(amount), rate(amount, played, ""));
    }

    private String distance(String label, long centimetres, long played) {
        double km = centimetres / CM_PER_KM;
        return text(label, String.format(Locale.ROOT, "%.1f km", km), rate(km, played, " km"));
    }

    private String hearts(String label, long tenths, long played) {
        double hearts = tenths / TENTHS_PER_HEART;
        return text(label, String.format(Locale.ROOT, "%.0f hearts", hearts), rate(hearts, played, ""));
    }

    // How much of it comes in an hour of play. Null whilst the rate is off or too young to read.
    private String rate(double amount, long played, String unit) {
        if (!perHour.isOn() || played < LEAST_RATE_TICKS) {
            return null;
        }
        double hourly = amount * TICKS_PER_HOUR / played;
        String number = String.format(Locale.ROOT, hourly < WHOLE_RATE ? "%.1f" : "%.0f", hourly);
        return "(" + number + unit + "/h)";
    }

    // The name in the element's colour then the number in white and the rate in grey.
    private static String text(String label, String value, String rate) {
        return label + " §f" + value + (rate == null ? "" : " §7" + rate);
    }

    // Two units at most such as 3d 4h or 12m 5s.
    private static String span(long ticks) {
        Duration time = Duration.ofSeconds(ticks / SharedConstants.TICKS_PER_SECOND);
        if (time.toDays() > 0) {
            return time.toDays() + "d " + time.toHoursPart() + "h";
        }
        if (time.toHours() > 0) {
            return time.toHours() + "h " + time.toMinutesPart() + "m";
        }
        if (time.toMinutes() > 0) {
            return time.toMinutes() + "m " + time.toSecondsPart() + "s";
        }
        return time.toSeconds() + "s";
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<String> shown = lines();
        int widest = width(font);
        backdrop.around(context, widest, shown.size() * TextRow.LINE);
        int y = 0;
        for (String line : shown) {
            int x = (int) Math.round((widest - font.width(line)) * alignment());
            context.text(font, line, x, y, color.getColor(), shadow.isOn());
            y += TextRow.LINE;
        }
    }

    @Override
    public int width(Font font) {
        int widest = 1;
        for (String line : lines()) {
            widest = Math.max(widest, font.width(line));
        }
        return widest;
    }

    @Override
    public int height(Font font) {
        return Math.max(TextRow.LINE, lines().size() * TextRow.LINE);
    }
}
