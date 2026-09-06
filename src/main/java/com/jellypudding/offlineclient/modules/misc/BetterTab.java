package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

// PlayerTabOverlayMixin calls into this for the ping column and the names
// and the row limit and the column height.
public final class BetterTab extends Module {

    private static final long VANILLA_LIMIT = 80;
    private static final int VANILLA_COLUMN = 20;

    // Looked up once per row whilst the list is open.
    private static volatile BetterTab instance;

    private final BoolSetting ping = new BoolSetting("Ping",
        "Show the exact latency instead of the signal bars.", true);
    private final BoolSetting friends = new BoolSetting("Friends",
        "Paint people on your friend list in their own colour.", true);
    private final ColorSetting friendColor = new ColorSetting("Friend colour",
        "Colour for friends in the list.", 210, false)
        .under(friends);
    private final BoolSetting self = new BoolSetting("Highlight self",
        "Paint your own name in its own colour.", true);
    private final ColorSetting selfColor = new ColorSetting("Self colour",
        "Colour for your own name in the list.", 27, 0.88f, 0.98f, false)
        .under(self);
    private final BoolSetting gameMode = new BoolSetting("Game mode",
        "Show a letter after each name for survival or creative or adventure or spectator.", false);
    private final BoolSetting raiseLimit = new BoolSetting("Raise limit",
        "Let the list show more players than vanilla allows.", false);
    private final NumberSetting limit = new NumberSetting("Limit",
        "How many players the list may show.", 200, 80, 500, 10, " players")
        .min(1).max(1000)
        .under(raiseLimit);
    private final NumberSetting columnHeight = new NumberSetting("Column height",
        "How many players fill a column before the list starts a new one.", 20, 5, 100, 1, " players")
        .min(1).max(1000);

    public BetterTab() {
        super("BetterTab", "Ping numbers and friend colours in the player list.", Category.MISC);
        addSettings(ping, friends, friendColor, self, selfColor, gameMode, raiseLimit, limit, columnHeight);
        searchTags("tab list", "player list", "latency");
        instance = this;
    }

    // The registered module or null before the client has started.
    public static BetterTab get() {
        return instance;
    }

    public boolean showsPing() {
        return isEnabled() && ping.isOn();
    }

    public long playerLimit() {
        if (!isEnabled() || !raiseLimit.isOn()) {
            return VANILLA_LIMIT;
        }
        return limit.getInt();
    }

    public int columnHeight() {
        return isEnabled() ? columnHeight.getInt() : VANILLA_COLUMN;
    }

    // Drawn in the column vanilla fills with the signal bars.
    public void drawPing(GuiGraphicsExtractor context, int width, int x, int y, PlayerInfo info) {
        Font font = mc.font;
        int latency = info.getLatency();
        String text = latency < 0 ? "?" : String.valueOf(Math.min(latency, 9999));
        context.text(font, text, x + width - font.width(text) - 1, y, ColorUtil.ping(latency), false);
    }

    // Anyone who is not you or a friend keeps the server's styling.
    public Component decorate(Component original, PlayerInfo info) {
        if (!isEnabled() || original == null) {
            return original;
        }
        Component name = original;
        int color = highlightColor(info);
        if (color != 0) {
            // Server colour codes inside the name would fight the highlight so they go.
            String plain = ChatFormatting.stripFormatting(original.getString());
            name = Component.literal(plain).withStyle(original.getStyle().withColor(color & 0xFFFFFF));
        }
        if (gameMode.isOn()) {
            name = Component.empty().append(name).append(" [" + modeLetter(info.getGameMode()) + "]");
        }
        return name;
    }

    // Zero when the name keeps its server colour.
    private int highlightColor(PlayerInfo info) {
        if (self.isOn() && mc.player != null && info.getProfile().id().equals(mc.player.getUUID())) {
            return selfColor.getColor();
        }
        if (friends.isOn() && OfflineClient.INSTANCE.getFriendManager().isFriend(info.getProfile().name())) {
            return friendColor.getColor();
        }
        return 0;
    }

    private static String modeLetter(GameType mode) {
        if (mode == null) {
            return "?";
        }
        if (mode == GameType.SPECTATOR) {
            return "Sp";
        }
        if (mode == GameType.CREATIVE) {
            return "C";
        }
        return mode == GameType.ADVENTURE ? "A" : "S";
    }
}
