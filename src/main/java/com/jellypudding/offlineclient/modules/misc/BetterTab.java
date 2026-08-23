package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/**
 * PlayerTabOverlayMixin calls into this for the ping column and the names
 * and the row limit.
 */
public final class BetterTab extends Module {

    private static final long VANILLA_LIMIT = 80;

    // Looked up once per row whilst the list is open.
    private static volatile BetterTab instance;

    private final BoolSetting ping = new BoolSetting("Ping",
        "Show the exact latency instead of the signal bars.", true);
    private final BoolSetting friends = new BoolSetting("Friends",
        "Paint people on your friend list in their own colour.", true);
    private final ColorSetting friendColor = new ColorSetting("Friend color",
        "Colour for friends in the list.", 210, false)
        .visibleWhen(friends::isOn);
    private final BoolSetting raiseLimit = new BoolSetting("Raise limit",
        "Let the list show more players than vanilla allows.", false);
    private final NumberSetting limit = new NumberSetting("Limit",
        "How many players the list may show.", 200, 80, 500, 10, " players")
        .min(1).max(1000)
        .visibleWhen(raiseLimit::isOn);

    public BetterTab() {
        super("BetterTab", "Ping numbers and friend colours in the player list.", Category.MISC);
        addSettings(ping, friends, raiseLimit, limit, friendColor);
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
        return (long) Math.max(1, limit.getValue());
    }

    // Drawn in the column vanilla fills with the signal bars.
    public void drawPing(GuiGraphicsExtractor context, int width, int x, int y, PlayerInfo info) {
        Font font = mc.font;
        int latency = info.getLatency();
        String text = latency < 0 ? "?" : String.valueOf(Math.min(latency, 9999));
        int color = latency < 0 ? 0xFF909090
            : latency < 100 ? 0xFF50FF50
            : latency < 250 ? 0xFFFFD040 : 0xFFFF5050;
        context.text(font, text, x + width - font.width(text) - 1, y, color, false);
    }

    // Anyone who is not a friend keeps the server's styling.
    public Component decorate(Component original, PlayerInfo info) {
        if (!isEnabled() || !friends.isOn() || original == null) {
            return original;
        }
        if (!OfflineClient.INSTANCE.getFriendManager().isFriend(info.getProfile().name())) {
            return original;
        }
        int color = friendColor.getColor() & 0xFFFFFF;
        return original.copy().withStyle(style -> style.withColor(color));
    }
}
