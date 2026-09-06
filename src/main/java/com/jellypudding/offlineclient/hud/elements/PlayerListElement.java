package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// The players near you with how far away and how hurt they are.
public final class PlayerListElement extends HudElement {

    private static final int LINE = 10;

    // Hue zero is red and hue one hundred and twenty is green.
    private static final float GREEN_HUE = 120;

    private final NumberSetting range = new NumberSetting("Player list range",
        "How far away a player still shows.", 64, 8, 256, 8, " blocks").min(1);
    private final NumberSetting limit = new NumberSetting("Player list limit",
        "Most rows to draw.", 8, 1, 30, 1).min(1);
    private final BoolSetting health = new BoolSetting("Player list health",
        "Write how much health each one has left.", true);
    private final BoolSetting friendsFirst = new BoolSetting("Friends first",
        "Put friends at the top of the list.", true);

    public PlayerListElement() {
        super("Player list", "The players near you with their distance.", false, 100, 30);
        add(range, limit, health, friendsFirst);
    }

    @Override
    public boolean visible() {
        return isActive() && !nearby().isEmpty();
    }

    private List<AbstractClientPlayer> nearby() {
        LocalPlayer self = OfflineClient.MC.player;
        List<AbstractClientPlayer> found = new ArrayList<>();
        if (self == null || OfflineClient.MC.level == null) {
            return found;
        }
        for (AbstractClientPlayer player : OfflineClient.MC.level.players()) {
            if (player != self && self.distanceTo(player) <= range.getValue()) {
                found.add(player);
            }
        }
        Comparator<AbstractClientPlayer> byDistance =
            Comparator.comparingDouble(self::distanceTo);
        found.sort(friendsFirst.isOn()
            ? Comparator.comparing((AbstractClientPlayer player) -> !EntityUtil.isFriend(player))
                .thenComparing(byDistance)
            : byDistance);
        return found.size() > limit.getInt() ? found.subList(0, limit.getInt()) : found;
    }

    private String rowOf(AbstractClientPlayer player) {
        String row = EntityUtil.displayNameOf(player)
            + String.format(Locale.ROOT, " %.0fm", OfflineClient.MC.player.distanceTo(player));
        return health.isOn()
            ? row + String.format(Locale.ROOT, " %.0f", EntityUtil.totalHealth(player)) : row;
    }

    // A friend keeps the friend colour and everyone else fades from green to red.
    private static int colorOf(AbstractClientPlayer player) {
        if (EntityUtil.isFriend(player)) {
            return EntityUtil.FRIEND_COLOR;
        }
        float max = EntityUtil.totalMaxHealth(player);
        float share = max <= 0 ? 1 : Math.clamp(EntityUtil.totalHealth(player) / max, 0f, 1f);
        return ColorUtil.hsv(share * GREEN_HUE, 0.8f, 1f);
    }

    // Rows are right aligned so the block hugs the edge of the screen.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        int widest = width(font);
        int y = 0;
        for (AbstractClientPlayer player : nearby()) {
            String row = rowOf(player);
            context.text(font, row, widest - font.width(row), y, colorOf(player), true);
            y += LINE;
        }
    }

    @Override
    public int width(Font font) {
        int widest = 1;
        for (AbstractClientPlayer player : nearby()) {
            widest = Math.max(widest, font.width(rowOf(player)));
        }
        return widest;
    }

    @Override
    public int height(Font font) {
        return Math.max(LINE, nearby().size() * LINE);
    }
}
