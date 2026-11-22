package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.Inspector;
import com.jellypudding.offlineclient.modules.render.RemoteView;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EffectText;
import com.jellypudding.offlineclient.util.EntityKind;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.SearchRank;
import com.jellypudding.offlineclient.util.SeenEffects;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// Facts the server never sends stay out. Another player's effects only show through their swirls
// and carry no time.
public final class InspectorScreen extends ListWindow {

    private enum Tab {
        ABOUT("About"),
        EFFECTS("Effects"),
        ATTRIBUTES("Attributes"),
        GEAR("Gear"),
        MESSAGES("Messages");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private enum Action implements RowList.Button {
        GO("go", "Walks there with the pathfinder and closes this window."),
        COPY("copy", "Puts it on the clipboard."),
        WRITE("write", "Opens the chat box with this line written in. Nothing is sent until you press enter.");

        private final String label;
        private final String help;

        Action(String label, String help) {
            this.label = label;
            this.help = help;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String help() {
            return help;
        }

        @Override
        public boolean warns() {
            return false;
        }
    }

    private record Use(String copied, BlockPos spot, String message) {

        private static final Use NONE = new Use(null, null, null);

        private static Use copy(String text) {
            return new Use(text, null, null);
        }
    }

    private static final int EYES_WIDTH = 44;
    private static final int MARK_WIDTH = 56;
    private static final int BUTTON_GAP = 4;
    private static final long REFRESH_MS = 500;
    private static final String SEPARATOR = " · ";
    private static final int DECIMALS = 3;
    // Names and item titles come from the server and are cut here before anything measures them.
    private static final int LONGEST_TEXT = 200;
    private static final String HINT = "Eyes looks out through its eyes. Mark puts a glow on it that stays until you"
        + " take it off. A message line opens the chat box and sends nothing until you press enter.";

    private final Inspector inspector;
    private final Entity entity;
    private final RowList<Use> rows = new RowList<>();
    // An entity that has gone keeps them.
    private final Map<Tab, List<RowList.Row<Use>>> known = new EnumMap<>(Tab.class);
    private Tab tab = Tab.ABOUT;
    private boolean stale = true;
    private long refreshedAt;

    private InspectorScreen(Screen parent, Inspector inspector, Entity entity) {
        super("Inspector", parent);
        this.inspector = inspector;
        this.entity = entity;
    }

    public static void open(Inspector inspector, Entity entity) {
        openOver(parent -> new InspectorScreen(parent, inspector, entity));
    }

    @Override
    protected void changed() {
        stale = true;
    }

    private boolean present() {
        return !entity.isRemoved() && entity.level() == OfflineClient.MC.level;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (!stale && now - refreshedAt < REFRESH_MS) {
            return;
        }
        stale = false;
        refreshedAt = now;
        if (present()) {
            known.put(Tab.ABOUT, about());
            known.put(Tab.EFFECTS, effects());
            known.put(Tab.ATTRIBUTES, attributes());
            known.put(Tab.GEAR, gear());
            known.put(Tab.MESSAGES, messages());
        }
        String query = search.get().trim();
        List<RowList.Row<Use>> shown = new ArrayList<>();
        for (RowList.Row<Use> row : known.getOrDefault(tab, List.of())) {
            if (query.isEmpty() || SearchRank.best(query, row.text(), row.detail()) != SearchRank.NO_MATCH) {
                shown.add(row);
            }
        }
        rows.show(shown);
    }

    private static RowList.Row<Use> row(String text, String detail, Use use, List<Action> buttons) {
        String shown = cut(text);
        String under = cut(detail);
        return new RowList.Row<>(use, shown, GuiTheme.text(), under, buttons, shown + SEPARATOR + under);
    }

    private static String cut(String text) {
        return text.length() > LONGEST_TEXT ? text.substring(0, LONGEST_TEXT) : text;
    }

    private List<RowList.Row<Use>> about() {
        List<RowList.Row<Use>> about = new ArrayList<>();
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        about.add(row(entity.getType().getDescription().getString(), id + SEPARATOR + kindWords(entity),
            Use.copy(id), List.of(Action.COPY)));
        String name = EntityUtil.displayNameOf(entity);
        about.add(row(name, entity instanceof Player ? "player name"
            : entity.hasCustomName() ? "the name on its tag" : "named after its kind", Use.copy(name),
            List.of(Action.COPY)));
        String uuid = entity.getUUID().toString();
        about.add(row(uuid, "unique id", Use.copy(uuid), List.of(Action.COPY)));
        String number = String.valueOf(entity.getId());
        about.add(row(number, "the number the server knows it by this session", Use.copy(number),
            List.of(Action.COPY)));
        if (entity instanceof LivingEntity living) {
            String absorbed = living.getAbsorptionAmount() > 0
                ? " and " + ChatUtil.decimal(living.getAbsorptionAmount(), DECIMALS) + " absorption" : "";
            about.add(row(ChatUtil.decimal(living.getHealth(), DECIMALS) + " of "
                + ChatUtil.decimal(living.getMaxHealth(), DECIMALS) + " health" + absorbed,
                EntityUtil.hearts(EntityUtil.totalHealth(living)), Use.NONE, List.of()));
            about.add(row("Armour " + living.getArmorValue(),
                "toughness " + ChatUtil.decimal(living.getAttributeValue(Attributes.ARMOR_TOUGHNESS), DECIMALS),
                Use.NONE, List.of()));
        }
        String grown = entity instanceof LivingEntity living && living.isBaby() ? SEPARATOR + "a baby" : "";
        about.add(row("Seen for " + ChatUtil.duration(entity.tickCount / (double) OfflineClient.MC.level
            .tickRateManager().tickrate()), "since it came into view" + grown, Use.NONE, List.of()));
        BlockPos pos = entity.blockPosition();
        about.add(row(BlockUtil.text(pos), Notice.away(OfflineClient.MC.player.position(), pos),
            new Use(BlockUtil.text(pos), pos, null), List.of(Action.GO, Action.COPY)));
        about.add(row(ChatUtil.decimal(entity.getBbWidth(), DECIMALS) + " by "
            + ChatUtil.decimal(entity.getBbHeight(), DECIMALS) + " blocks",
            "hitbox wide by tall", Use.NONE, List.of()));
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            about.add(row("Riding " + EntityUtil.displayNameOf(vehicle), "its vehicle", Use.NONE, List.of()));
        }
        if (entity instanceof Player player) {
            ClientPacketListener connection = OfflineClient.MC.getConnection();
            PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
            if (info != null) {
                about.add(row("Ping " + info.getLatency() + " ms", "game mode "
                    + info.getGameMode().getName().replace('_', ' '), Use.NONE, List.of()));
            }
        }
        return about;
    }

    private static String kindWords(Entity entity) {
        return switch (EntityKind.of(entity)) {
            case PLAYER -> "player";
            case HOSTILE -> "hostile";
            case NEUTRAL -> "neutral";
            case PASSIVE -> "peaceful";
            case WATER -> "water life";
            case AMBIENT -> "ambient";
            case ITEM -> "dropped item";
            case OTHER -> "other";
        };
    }

    private List<RowList.Row<Use>> effects() {
        List<RowList.Row<Use>> effects = new ArrayList<>();
        if (!(entity instanceof LivingEntity living)) {
            return effects;
        }
        List<Holder<MobEffect>> timed = new ArrayList<>();
        for (MobEffectInstance effect : living.getActiveEffects()) {
            timed.add(effect.getEffect());
            effects.add(row(EffectText.name(effect), EffectText.timeLeft(effect) + " left", Use.NONE, List.of()));
        }
        for (Holder<MobEffect> effect : SeenEffects.of(living)) {
            if (!timed.contains(effect)) {
                effects.add(row(effect.value().getDisplayName().getString(),
                    "seen by its swirls and the server sends no time", Use.NONE, List.of()));
            }
        }
        return effects;
    }

    // The attributes the server shares with every player who can see the entity.
    private List<RowList.Row<Use>> attributes() {
        List<RowList.Row<Use>> attributes = new ArrayList<>();
        if (!(entity instanceof LivingEntity living)) {
            return attributes;
        }
        List<AttributeInstance> shared = new ArrayList<>(living.getAttributes().getSyncableAttributes());
        shared.sort(Comparator.comparing(InspectorScreen::nameOf));
        for (AttributeInstance each : shared) {
            double value = each.getValue();
            double base = each.getBaseValue();
            attributes.add(row(nameOf(each) + " " + ChatUtil.decimal(value, DECIMALS),
                value == base ? "unchanged" : "base " + ChatUtil.decimal(base, DECIMALS), Use.NONE, List.of()));
        }
        return attributes;
    }

    private static String nameOf(AttributeInstance attribute) {
        return Component.translatable(attribute.getAttribute().value().getDescriptionId()).getString();
    }

    private List<RowList.Row<Use>> gear() {
        List<RowList.Row<Use>> gear = new ArrayList<>();
        if (!(entity instanceof LivingEntity living)) {
            return gear;
        }
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack stack = living.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String count = stack.getCount() > 1 ? " " + GuiTheme.CROSS + stack.getCount() : "";
            List<String> detail = new ArrayList<>();
            detail.add(ItemUtil.slotWords(slot));
            List<String> enchantments = new ArrayList<>();
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
                enchantments.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
            }
            if (!enchantments.isEmpty()) {
                detail.add(String.join(" and ", enchantments));
            }
            if (stack.isDamageableItem()) {
                detail.add(ItemUtil.usesLeft(stack) + " of " + stack.getMaxDamage() + " left");
            }
            gear.add(row(stack.getHoverName().getString() + count, String.join(SEPARATOR, detail), Use.NONE,
                List.of()));
        }
        return gear;
    }

    private List<RowList.Row<Use>> messages() {
        List<RowList.Row<Use>> messages = new ArrayList<>();
        String name = cut(entity instanceof Player player ? EntityUtil.nameOf(player) : entity.getName().getString());
        for (String line : inspector.messageLines()) {
            String written = Inspector.fillIn(line, name);
            messages.add(new RowList.Row<>(new Use(null, null, written), cut(written), GuiTheme.text(), cut(line),
                List.of(Action.WRITE), "Click to open the chat box with this line written in."));
        }
        return messages;
    }

    private int markX() {
        return windowX() + windowWidth() - MARGIN - MARK_WIDTH;
    }

    private int eyesX() {
        return markX() - BUTTON_GAP - EYES_WIDTH;
    }

    private boolean hasEyes() {
        return entity instanceof LivingEntity;
    }

    @Override
    protected void renderView(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        refresh();
        Font font = ClientFonts.gui();
        footer.begin();
        if (!present()) {
            footer.show("It has gone out of sight. The rows show what was known last.", GuiTheme.RED_TEXT);
        }
        footer.showStatus();
        int wx = windowX();
        int wy = windowY();
        int ww = windowWidth();
        int wh = windowHeight();
        WindowParts.frame(context, wx, wy, ww, wh);
        String right = present() ? Math.round(OfflineClient.MC.player.distanceTo(entity)) + " blocks away" : "gone";
        int room = ww - WindowParts.TITLE_INSET * 3 - font.width("Inspector") - font.width(right)
            - WindowParts.TITLE_GAP;
        WindowParts.title(context, font, wx, wy, ww, "Inspector",
            SettingWidget.trimEnd(font, cut(EntityUtil.displayNameOf(entity)), room), right);
        renderToolbar(context, font, wx, mouseX, mouseY);
        List<String> names = new ArrayList<>();
        for (Tab each : Tab.values()) {
            names.add(each.label);
        }
        WindowParts.tabs(context, font, wx + MARGIN, contentTop(), WindowParts.tabWidth(), contentHeight(), names,
            index -> String.valueOf(known.getOrDefault(Tab.values()[index], List.of()).size()), tab.ordinal(),
            mouseX, mouseY);
        rows.render(context, font, listX(), contentTop(), listWidth(), contentHeight(), mouseX, mouseY, footer,
            emptyText());
        footer.render(context, font, wx, wy, ww, wh, HINT);
    }

    private String emptyText() {
        if (!search.isEmpty()) {
            return "no matches";
        }
        return switch (tab) {
            case ABOUT -> "nothing known";
            case EFFECTS -> "no effects seen";
            case ATTRIBUTES -> "the server shares no attributes for this kind";
            case GEAR -> "nothing worn or held";
            case MESSAGES -> "no message lines yet. Add them in the Inspector settings";
        };
    }

    private void renderToolbar(GuiGraphicsExtractor context, Font font, int wx, int mouseX, int mouseY) {
        int y = toolbarY();
        int searchX = wx + MARGIN;
        int searchRight = (hasEyes() ? eyesX() : markX()) - BUTTON_GAP;
        search.render(context, font, searchX, y, searchRight - searchX, mouseX, mouseY, null);
        boolean live = present();
        if (hasEyes()) {
            boolean overEyes = SettingWidget.isOver(mouseX, mouseY, eyesX(), y, EYES_WIDTH, SearchField.HEIGHT);
            GuiTheme.button(context, font, eyesX(), y, EYES_WIDTH, SearchField.HEIGHT, "eyes", overEyes && live, live);
            if (overEyes) {
                footer.show("Looks out through its eyes with RemoteView and closes this window.", GuiTheme.text());
            }
        }
        boolean marked = inspector.isMarked(entity);
        boolean overMark = SettingWidget.isOver(mouseX, mouseY, markX(), y, MARK_WIDTH, SearchField.HEIGHT);
        GuiTheme.button(context, font, markX(), y, MARK_WIDTH, SearchField.HEIGHT, marked ? "unmark" : "mark",
            overMark && live, live);
        if (overMark) {
            footer.show(marked ? "Takes the glow off it."
                : "Puts a glow on it that shows through walls until you take it off.", GuiTheme.text());
        }
    }

    @Override
    protected boolean click(double mx, double my) {
        if (search.click(mx, my) != SearchField.Click.MISSED) {
            return true;
        }
        int y = toolbarY();
        if (hasEyes() && SettingWidget.isOver(mx, my, eyesX(), y, EYES_WIDTH, SearchField.HEIGHT)) {
            lookThrough();
            return true;
        }
        if (SettingWidget.isOver(mx, my, markX(), y, MARK_WIDTH, SearchField.HEIGHT)) {
            toggleMark();
            return true;
        }
        int index = WindowParts.tabAt(mx, my, windowX() + MARGIN, contentTop(), WindowParts.tabWidth(),
            contentHeight(), Tab.values().length);
        if (index >= 0) {
            tab = Tab.values()[index];
            rows.toTop();
            changed();
            return true;
        }
        return rows.click(mx, my, this::press);
    }

    private void lookThrough() {
        RemoteView remoteView = Modules.get(RemoteView.class);
        if (!present() || remoteView == null) {
            return;
        }
        onClose();
        remoteView.view(entity);
    }

    private void toggleMark() {
        if (!present()) {
            return;
        }
        String name = cut(EntityUtil.displayNameOf(entity));
        footer.say(inspector.toggleMark(entity) ? "Marked " + name + " with a glow" : "Took the glow off " + name);
    }

    // The chat box gets a space after the line for whatever comes next and the game trims it off a
    // line sent as it is.
    private void press(Use use, RowList.Button button) {
        if (use.message() != null && (button == null || button == Action.WRITE)) {
            OfflineClient.MC.gui.setScreen(new ChatScreen(use.message() + " ", false));
            return;
        }
        if (button == Action.GO && use.spot() != null) {
            walkTo(use.spot());
        } else if (button == Action.COPY && use.copied() != null) {
            OfflineClient.MC.keyboardHandler.setClipboard(use.copied());
            footer.say("Copied " + use.copied());
        }
    }

    @Override
    protected void releaseLists() {
        rows.release();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double mx = toView(mouseX);
        double my = toView(mouseY);
        return rows.scroll(mx, my, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
