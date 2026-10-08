package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
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

    // The rows on offer with the item each shows beside it in the settings. The editor shows a
    // made up amount for each in the unit of its statistic until the server first answers.
    public enum Row {
        PLAY_TIME(Items.CLOCK, SAMPLE_PLAYED),
        DISTANCE(Items.LEATHER_BOOTS, 4_270_000),
        ELYTRA_DISTANCE(Items.ELYTRA, 1_830_000),
        BLOCKS_MINED(Items.IRON_PICKAXE, 1532),
        MOBS_KILLED(Items.ZOMBIE_HEAD, 87),
        PLAYERS_KILLED(Items.PLAYER_HEAD, 3),
        DEATHS(Items.SKELETON_SKULL, 2),
        SINCE_DEATH(Items.TOTEM_OF_UNDYING, 56_600),
        SINCE_SLEEP(Items.BED.red(), 78_000),
        ITEMS_CRAFTED(Items.CRAFTING_TABLE, 412),
        ITEMS_USED(Items.FLINT_AND_STEEL, 2210),
        ITEMS_PICKED_UP(Items.HOPPER, 3904),
        DAMAGE_DEALT(Items.IRON_SWORD, 9600),
        DAMAGE_TAKEN(Items.SHIELD, 1900),
        JUMPS(Items.RABBIT_FOOT, 5120);

        private final Item icon;
        private final long sample;

        Row(Item icon, long sample) {
            this.icon = icon;
            this.sample = sample;
        }
    }

    public enum Period { ALL_TIME, THIS_VISIT }

    private static final double CM_PER_KM = 100_000;
    // Damage is counted in tenths of a health point and a heart holds two.
    private static final double TENTHS_PER_HEART = 20;
    private static final long TICKS_PER_HOUR = TimeUnit.HOURS.toSeconds(1) * SharedConstants.TICKS_PER_SECOND;
    // A rate from less than a minute of play swings too wildly to read.
    private static final long LEAST_RATE_TICKS = TimeUnit.MINUTES.toSeconds(1) * SharedConstants.TICKS_PER_SECOND;
    // Below this an hourly rate keeps a decimal.
    private static final double WHOLE_RATE = 10;
    // A row with nothing to say yet.
    private static final long NOTHING = Long.MIN_VALUE;
    // Three hours and twelve minutes of play in ticks behind the sample rows.
    private static final long SAMPLE_PLAYED = 230_400;

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
        "How often the server is asked for your statistics.", 5, 1, 60, 1, " seconds").min(1);
    private final ColorSetting color = labelColor("Statistics colour",
        "Colour of the row names. The numbers stay white.");
    // The sums walk every statistic you have.
    private final RowCache<String> lines = new RowCache<>(this::build);

    public StatsElement() {
        super("Statistics", "Your statistics on this server as the server keeps them such as play time and kills."
            + " They refresh whilst they show.", false, 0, 60);
        add(rows);
        add(mined.settings());
        add(killed.settings());
        add(crafted.settings());
        add(used.settings());
        add(pickedUp.settings());
        add(period, perHour, refresh, color);
    }

    @Override
    public boolean visible() {
        return isActive() && !lines().isEmpty();
    }

    // Until the server first answers the editor shows the chosen rows with made up numbers.
    private List<String> lines() {
        if (OfflineClient.MC.player == null) {
            return List.of();
        }
        PlayerStats.INSTANCE.want(refresh.getValue());
        if (PlayerStats.INSTANCE.ready()) {
            return lines.get();
        }
        if (!editing()) {
            return List.of();
        }
        List<String> sample = new ArrayList<>();
        for (Row row : rows.ranked()) {
            sample.add(line(row, row.sample, SAMPLE_PLAYED));
        }
        return sample;
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
            long amount = amount(row, visit, elapsed, played);
            if (amount != NOTHING) {
                built.add(line(row, amount, played));
            }
        }
        return built;
    }

    // What the statistic behind a row has counted. NOTHING for a row with nothing to say yet
    // such as the time since your last death before you have died.
    private long amount(Row row, boolean visit, long elapsed, long played) {
        return switch (row) {
            case PLAY_TIME -> played;
            case DISTANCE -> travelled(visit);
            case ELYTRA_DISTANCE -> custom(Stats.AVIATE_ONE_CM, visit);
            case BLOCKS_MINED -> sum(Stats.BLOCK_MINED, mined, visit);
            case MOBS_KILLED -> mobKills(visit);
            case PLAYERS_KILLED -> custom(Stats.PLAYER_KILLS, visit);
            case DEATHS -> custom(Stats.DEATHS, visit);
            case SINCE_DEATH -> custom(Stats.DEATHS, false) > 0
                ? custom(Stats.TIME_SINCE_DEATH, false) + elapsed : NOTHING;
            case SINCE_SLEEP -> custom(Stats.TIME_SINCE_REST, false) + elapsed;
            case ITEMS_CRAFTED -> sum(Stats.ITEM_CRAFTED, crafted, visit);
            case ITEMS_USED -> sum(Stats.ITEM_USED, used, visit);
            case ITEMS_PICKED_UP -> sum(Stats.ITEM_PICKED_UP, pickedUp, visit);
            case DAMAGE_DEALT -> custom(Stats.DAMAGE_DEALT, visit);
            case DAMAGE_TAKEN -> custom(Stats.DAMAGE_TAKEN, visit);
            case JUMPS -> custom(Stats.JUMP, visit);
        };
    }

    // How a row reads once its statistic has counted this much.
    private String line(Row row, long amount, long played) {
        String label = EnumSetting.label(row);
        return switch (row) {
            case PLAY_TIME, SINCE_DEATH, SINCE_SLEEP -> text(label, span(amount), null);
            case DISTANCE, ELYTRA_DISTANCE -> distance(label, amount, played);
            case BLOCKS_MINED -> filtered(label, mined, " mined", amount, played);
            case MOBS_KILLED -> filtered(label, killed, " kills", amount, played);
            case ITEMS_CRAFTED -> filtered(label, crafted, " crafted", amount, played);
            case ITEMS_USED -> filtered(label, used, " used", amount, played);
            case ITEMS_PICKED_UP -> filtered(label, pickedUp, " picked up", amount, played);
            case DAMAGE_DEALT, DAMAGE_TAKEN -> hearts(label, amount, played);
            case PLAYERS_KILLED, DEATHS, JUMPS -> count(label, amount, played);
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

    // The colour goes on as the rows are drawn. A rainbow keeps moving between rebuilds.
    private List<TextRow> rows(Font font) {
        return TextRow.lines(font, lines(), color);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        TextRow.draw(context, font, rows(font), alignment());
    }

    @Override
    public int width(Font font) {
        return TextRow.widest(rows(font));
    }

    @Override
    public int height(Font font) {
        return TextRow.height(font, lines().size());
    }
}
