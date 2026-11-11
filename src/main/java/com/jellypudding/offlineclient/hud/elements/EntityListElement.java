package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.CountRows;
import com.jellypudding.offlineclient.hud.CountRows.Counted;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.RowsElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Every kind of entity the server has sent you with how many of each there are. Dropped items
// and minecarts and arrows count too and a sudden pile of them gives a base away.
public final class EntityListElement extends RowsElement {

    // Stand in whilst the editor is open and nothing is about.
    private static final List<Counted> SAMPLE = List.of(new Counted("Item", 14), new Counted("Zombie", 3),
        new Counted("Chest minecart", 1));

    private final NumberSetting range = new NumberSetting("Entity list range",
        "How far away an entity still counts.", 128, 16, 256, 16, " blocks").min(1);
    private final NumberSetting limit = new NumberSetting("Entity list rows",
        "Most kinds to list. The kinds there are most of come first.", 10, 1, 30, 1);
    private final BoolSetting players = new BoolSetting("Entity list players",
        "Counts other players as a kind.", true);
    private final ColorSetting color = labelColor("Entity list colour",
        "Colour of the kinds. The counts stay white.");
    // Each gathering walks every entity the client holds.
    private final RowCache<Counted> counts = new RowCache<>(this::build);

    public EntityListElement() {
        super("Entity list", "Every kind of entity round you with how many of each there are.", false, 100, 40);
        add(range, limit, players, color);
    }

    @Override
    public boolean visible() {
        return isActive() && !shown().isEmpty();
    }

    private List<Counted> shown() {
        List<Counted> built = counts.get();
        return built.isEmpty() && editing() ? SAMPLE : built;
    }

    // Yourself and a body only this client has are left out. Each kind goes by the words of its
    // id whatever language the game is in and however a resource pack names it.
    private List<Counted> build() {
        LocalPlayer self = OfflineClient.MC.player;
        ClientLevel level = OfflineClient.MC.level;
        if (self == null || level == null) {
            return List.of();
        }
        double reach = range.getValue();
        Map<EntityType<?>, Integer> found = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == self || Modules.isLocalBody(entity)
                || !players.isOn() && entity.getType() == EntityTypes.PLAYER) {
                continue;
            }
            if (self.distanceToSqr(entity) <= reach * reach) {
                found.merge(entity.getType(), 1, Integer::sum);
            }
        }
        Map<String, Integer> named = new HashMap<>();
        found.forEach((type, count) -> named.merge(ChatUtil.capitalised(ChatUtil.words(type)), count, Integer::sum));
        List<Counted> ranked = CountRows.ranked(named);
        return ranked.size() > limit.getInt() ? ranked.subList(0, limit.getInt()) : ranked;
    }

    @Override
    protected List<TextRow> rows(Font font) {
        return CountRows.rows(font, shown(), color);
    }
}
