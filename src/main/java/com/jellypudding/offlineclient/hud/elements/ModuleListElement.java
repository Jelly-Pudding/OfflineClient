package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModuleListElement extends HudElement {

    public enum Shows { LIST, COUNT, LIST_THAT_FITS }

    public enum Sort { LONGEST, SHORTEST, ALPHABETICAL }

    private static final double GLIDE_RATE = 20;

    private static final double SLIDE_SECONDS = 0.25;

    // A frame after a long pause moves the rows at most this many seconds on.
    private static final double LONGEST_FRAME = 1;

    private static final int BAR = 1;
    private static final int BAR_GAP = 2;

    private record Entry(String name, String key, String suffix) {

        // What the order measures. A suffix that changes never moves a row.
        String lead() {
            return key == null ? name : name + " " + key;
        }
    }

    // A row whose module went off stays until it has slid out.
    private static final class Slot {

        private Entry entry;
        private double y;
        private double out;

        private Slot(double y, double out) {
            this.y = y;
            this.out = out;
        }
    }

    private final EnumSetting<Shows> shows = new EnumSetting<>("List shows",
        "What the piece writes.", Shows.LIST)
        .describe(Shows.LIST, "Each module on a line of its own.")
        .describe(Shows.COUNT, "Only how many modules the list would hold.")
        .describe(Shows.LIST_THAT_FITS,
            "The list whilst it is no taller than the screen and only how many modules it holds once it is.");
    private final EnumSetting<Sort> sort = new EnumSetting<>("List order",
        "How the rows are ordered.", Sort.LONGEST)
        .describe(Sort.LONGEST, "Longest name first. A row keeps its place when the word in brackets changes.")
        .describe(Sort.SHORTEST, "Shortest name first.")
        .describe(Sort.ALPHABETICAL, "By name from A to Z.")
        .under(shows, Shows.LIST, Shows.LIST_THAT_FITS);
    private final ColorSetting color = new ColorSetting("List colour",
        "Colour of the module names and of the count.", 200, false);
    private final BoolSetting keybinds = new BoolSetting("List keybinds",
        "Writes the key bound to each module after its name.", false)
        .under(shows, Shows.LIST, Shows.LIST_THAT_FITS);
    private final ColorSetting keyColor = new ColorSetting("List keybind colour",
        "Colour of those keys.", 0, 0, 0.67f, false).under(keybinds);
    private final BoolSetting suffixes = new BoolSetting("List suffixes",
        "Writes the extra word after a name such as the mode it is in.", true)
        .under(shows, Shows.LIST, Shows.LIST_THAT_FITS);
    private final ColorSetting suffixColor = new ColorSetting("List suffix colour",
        "Colour of that word.", 0, 0, 0.67f, false).under(suffixes);
    private final ColorSetting bracketColor = new ColorSetting("List bracket colour",
        "Colour of the brackets round that word.", 0, 0, 0.67f, false).under(suffixes);
    private final BoolSetting slide = new BoolSetting("List slide",
        "Slides a row in and out as its module turns on and off.", false)
        .under(shows, Shows.LIST, Shows.LIST_THAT_FITS);
    private final BoolSetting sideBar = new BoolSetting("List side bar",
        "Draws a bar in the list colour on the side the rows line up with and on both sides of a centred list.",
        false).under(shows, Shows.LIST, Shows.LIST_THAT_FITS);
    private final RowCache<Entry> entries = new RowCache<>(this::build);

    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private long lastFrame;
    private boolean sampled;

    public ModuleListElement() {
        super("Module list", "The modules that are on one to a line. A module with In module list switched off"
            + " stays out.", true, 100, 0, 0.5);
        add(shows, sort, keybinds, keyColor, suffixes, suffixColor, bracketColor, slide, sideBar, color);
    }

    // The list gives way to the count once it stands taller than the screen at its present stretch.
    private boolean counting() {
        if (shows.is(Shows.LIST_THAT_FITS)) {
            Font font = ClientFonts.hud();
            return TextRow.height(font, shown().size()) * scaleY()
                > OfflineClient.MC.getWindow().getGuiScaledHeight();
        }
        return shows.is(Shows.COUNT);
    }

    private TextRow countRow(Font font) {
        return TextRow.of(font, Tally.counted(shown().size(), "module") + " on", color.rowColor(0));
    }

    @Override
    public boolean visible() {
        return isActive() && !drawn().isEmpty();
    }

    private List<Entry> shown() {
        List<Entry> on = entries.get();
        boolean sample = on.isEmpty() && editing();
        if (sampled && !sample) {
            slots.clear();
        }
        sampled = sample;
        return sample ? sample() : on;
    }

    private List<Entry> sample() {
        List<Entry> sample = new ArrayList<>(List.of(entryOf("AutoTotem", null, null),
            entryOf("KillAura", "R", "4.2"), entryOf("Sprint", null, "Strict")));
        sample.sort(order());
        return sample;
    }

    private List<Entry> build() {
        List<Entry> built = new ArrayList<>();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getEnabled()) {
            if (!module.isListed()) {
                continue;
            }
            KeybindSetting bind = module.getKeybind();
            built.add(entryOf(module.getName(), bind.isBound() ? bind.getValueString() : null, module.getSuffix()));
        }
        built.sort(order());
        return built;
    }

    private Entry entryOf(String name, String key, String suffix) {
        return new Entry(name, keybinds.isOn() ? key : null, suffixes.isOn() ? suffix : null);
    }

    private Comparator<Entry> order() {
        Comparator<Entry> byName = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
        if (sort.is(Sort.ALPHABETICAL)) {
            return byName;
        }
        Font font = ClientFonts.hud();
        Comparator<Entry> byWidth = Comparator.comparingInt(entry -> font.width(entry.lead()));
        return (sort.is(Sort.LONGEST) ? byWidth.reversed() : byWidth).thenComparing(byName);
    }

    private List<Entry> drawn() {
        List<Entry> drawn = new ArrayList<>(shown());
        if (!slide.isOn()) {
            return drawn;
        }
        int next = 0;
        for (Slot slot : slots.values()) {
            int at = indexOf(drawn, slot.entry.name());
            if (at < 0) {
                at = next;
                drawn.add(at, slot.entry);
            }
            next = at + 1;
        }
        return drawn;
    }

    private static int indexOf(List<Entry> entries, String name) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private List<TextRow> rows(Font font, List<Entry> entries) {
        List<TextRow> rows = new ArrayList<>(entries.size());
        for (int row = 0; row < entries.size(); row++) {
            rows.add(rowOf(font, entries.get(row), row));
        }
        return rows;
    }

    private TextRow rowOf(Font font, Entry entry, int row) {
        List<String> parts = new ArrayList<>(List.of(entry.name()));
        List<Integer> colors = new ArrayList<>(List.of(color.rowColor(row)));
        if (entry.key() != null) {
            parts.add(" " + entry.key());
            colors.add(keyColor.rowColor(row));
        }
        if (entry.suffix() != null) {
            int bracket = bracketColor.rowColor(row);
            // The space keeps the vanilla gap before the bracket.
            parts.addAll(List.of(" [", entry.suffix(), "]"));
            colors.addAll(List.of(bracket, suffixColor.rowColor(row), bracket));
        }
        return TextRow.of(font, parts, colors);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        if (counting()) {
            slots.clear();
            countRow(font).draw(context, font, 0, 0);
            return;
        }
        List<Entry> drawn = drawn();
        Set<String> on = new HashSet<>();
        for (Entry entry : shown()) {
            on.add(entry.name());
        }
        List<TextRow> rows = rows(font, drawn);
        int widest = TextRow.widest(rows);
        double seconds = frameSeconds();
        double keep = Math.exp(-GLIDE_RATE * seconds);
        double step = seconds / SLIDE_SECONDS;
        Map<String, Slot> kept = new LinkedHashMap<>();
        for (int row = 0; row < drawn.size(); row++) {
            Entry entry = drawn.get(row);
            boolean leaving = !on.contains(entry.name());
            int target = row * TextRow.LINE;
            Slot slot = slots.computeIfAbsent(entry.name(), name -> new Slot(target, slide.isOn() ? 1 : 0));
            slot.entry = entry;
            slot.y = target + (slot.y - target) * keep;
            slot.out = Math.clamp(slot.out + (leaving ? step : -step), 0, 1);
            if (leaving && slot.out >= 1) {
                continue;
            }
            kept.put(entry.name(), slot);
            drawRow(context, font, rows.get(row), slot, widest, row, row == drawn.size() - 1);
        }
        slots.clear();
        slots.putAll(kept);
    }

    private void drawRow(GuiGraphicsExtractor context, Font font, TextRow row, Slot slot, int widest, int index,
                         boolean last) {
        double align = alignment();
        int room = barRoom();
        double away = (align * 2 - 1) * slot.out * slot.out * (row.width() + room);
        int left = (barLeft(align) ? room : 0) + row.leftIn(widest, align) + (int) Math.round(away);
        int top = (int) Math.round(slot.y);
        float strength = (float) (1 - slot.out);
        faded(row, strength).draw(context, font, left, top);
        if (!sideBar.isOn()) {
            return;
        }
        int bottom = top + (last ? font.lineHeight : TextRow.LINE);
        int tint = ColorUtil.fade(color.rowColor(index), strength);
        if (barLeft(align)) {
            context.fill(left - BAR_GAP - BAR, top, left - BAR_GAP, bottom, tint);
        }
        if (barRight(align)) {
            int right = left + row.width() + BAR_GAP;
            context.fill(right, top, right + BAR, bottom, tint);
        }
    }

    private static TextRow faded(TextRow row, float strength) {
        if (strength >= 1) {
            return row;
        }
        List<Integer> colors = new ArrayList<>(row.colors().size());
        for (int color : row.colors()) {
            colors.add(ColorUtil.fade(color, strength));
        }
        return new TextRow(row.parts(), colors, row.width());
    }

    private int barRoom() {
        return sideBar.isOn() ? BAR + BAR_GAP : 0;
    }

    private static boolean barLeft(double align) {
        return align < 1;
    }

    private static boolean barRight(double align) {
        return align > 0;
    }

    private double frameSeconds() {
        long now = System.nanoTime();
        double seconds = slots.isEmpty() ? 0 : Math.min((now - lastFrame) / 1.0E9, LONGEST_FRAME);
        lastFrame = now;
        return seconds;
    }

    @Override
    public int width(Font font) {
        if (counting()) {
            return countRow(font).width();
        }
        double align = alignment();
        int room = barRoom();
        return TextRow.widest(rows(font, drawn())) + (barLeft(align) ? room : 0) + (barRight(align) ? room : 0);
    }

    @Override
    public int height(Font font) {
        return counting() ? font.lineHeight : TextRow.height(font, drawn().size());
    }
}
