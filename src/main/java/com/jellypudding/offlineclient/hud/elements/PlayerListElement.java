package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.TextRow;
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

// The players near you with how far away and how hurt they are. Each row lines up with the
// side the element is anchored to.
public final class PlayerListElement extends HudElement {

    private final NumberSetting range = new NumberSetting("Player list range",
        "How far away a player still shows.", 64, 8, 256, 8, " blocks").min(1);
    private final NumberSetting limit = new NumberSetting("Player list limit",
        "Most rows to draw.", 8, 1, 30, 1).min(1);
    private final BoolSetting friends = new BoolSetting("Player list friends",
        "Shows friends as well as everyone else.", true);
    private final BoolSetting friendsFirst = new BoolSetting("Player list friends first",
        "Puts friends at the top of the list.", true).under(friends);
    private final BoolSetting distance = new BoolSetting("Player list distance",
        "Writes how far away each one is.", true);
    private final BoolSetting health = new BoolSetting("Player list health",
        "Writes how much health each one has left.", true);
    // Each gathering measures every player in the world and sorts them.
    private final RowCache<TextRow> rows = new RowCache<>(this::build);

    public PlayerListElement() {
        super("Player list", "The players near you with their distance and health.", false, 100, 30);
        add(range, limit, friends, friendsFirst, distance, health);
    }

    @Override
    public boolean visible() {
        return isActive() && !rows().isEmpty();
    }

    // A friend at full health and a hurt stranger stand in whilst the editor is open and
    // nobody is near.
    private List<TextRow> rows() {
        List<TextRow> built = rows.get();
        if (!built.isEmpty() || !editing()) {
            return built;
        }
        Font font = OfflineClient.MC.font;
        List<TextRow> sample = new ArrayList<>(2);
        if (friends.isOn()) {
            sample.add(rowOf(font, "Steve", 12, 20, EntityUtil.FRIEND_COLOR));
        }
        sample.add(rowOf(font, "Alex", 31, 7, ColorUtil.redToGreen(7 / 20f)));
        return sample;
    }

    private List<TextRow> build() {
        LocalPlayer self = OfflineClient.MC.player;
        List<TextRow> built = new ArrayList<>();
        for (AbstractClientPlayer player : nearby()) {
            built.add(rowOf(OfflineClient.MC.font, EntityUtil.displayNameOf(player), self.distanceTo(player),
                EntityUtil.totalHealth(player), colorOf(player)));
        }
        return built;
    }

    private List<AbstractClientPlayer> nearby() {
        LocalPlayer self = OfflineClient.MC.player;
        List<AbstractClientPlayer> found = new ArrayList<>();
        if (self == null || OfflineClient.MC.level == null) {
            return found;
        }
        for (AbstractClientPlayer player : OfflineClient.MC.level.players()) {
            if (player != self && self.distanceTo(player) <= range.getValue()
                && (friends.isOn() || !EntityUtil.isFriend(player))) {
                found.add(player);
            }
        }
        Comparator<AbstractClientPlayer> byDistance =
            Comparator.comparingDouble(self::distanceTo);
        found.sort(friends.isOn() && friendsFirst.isOn()
            ? Comparator.comparing((AbstractClientPlayer player) -> !EntityUtil.isFriend(player))
                .thenComparing(byDistance)
            : byDistance);
        return found.size() > limit.getInt() ? found.subList(0, limit.getInt()) : found;
    }

    // The name then how far away and how much health is left whilst those are written.
    private TextRow rowOf(Font font, String name, double away, float healthLeft, int color) {
        String row = name;
        if (distance.isOn()) {
            row += String.format(Locale.ROOT, " %.0fm", away);
        }
        if (health.isOn()) {
            row += String.format(Locale.ROOT, " %.0f", healthLeft);
        }
        return TextRow.of(font, row, color);
    }

    // A friend keeps the friend colour and everyone else fades from green to red.
    private static int colorOf(AbstractClientPlayer player) {
        if (EntityUtil.isFriend(player)) {
            return EntityUtil.FRIEND_COLOR;
        }
        return ColorUtil.redToGreen(EntityUtil.healthShare(player));
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        TextRow.draw(context, font, rows(), alignment());
    }

    @Override
    public int width(Font font) {
        return TextRow.widest(rows());
    }

    @Override
    public int height(Font font) {
        return TextRow.height(font, rows().size());
    }
}
