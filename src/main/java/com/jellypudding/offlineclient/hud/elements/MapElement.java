package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.render.MapMarks;
import com.jellypudding.offlineclient.render.MapPreview;
import com.jellypudding.offlineclient.render.MapView;
import com.jellypudding.offlineclient.render.OwnMarker;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// The server moves your marker four times a second and turns it in sixteen steps. An arrow drawn
// here every frame stands in for it.
public final class MapElement extends HudElement {

    public enum Shows { CARRIED, IN_HAND, SLOT }

    // Whilst your marker cannot be told apart the view is null and the picture is as the server
    // sent it.
    private record Shown(MapRenderState state, MapView view) {
    }

    private static final int SIZE = 64;
    private static final int SAMPLE_PAPER = 0x60D8C8A0;
    private static final int SAMPLE_EDGE = 0xC0A08A60;

    private final EnumSetting<Shows> shows;
    private final NumberSetting slot;
    private final OwnMarker own = new OwnMarker();
    private final RowCache<Shown> found = new RowCache<>(this::find);

    public MapElement(int number) {
        super("Map" + ordinal(number), "A map from your inventory drawn on the screen.", false, 100, 50);
        String name = getName();
        shows = new EnumSetting<>(name + " shows", "Which map it draws.", Shows.CARRIED)
            .describe(Shows.CARRIED, "The map in your hand and failing that the first one in your bag.")
            .describe(Shows.IN_HAND, "Only a map you hold in either hand.")
            .describe(Shows.SLOT, "The map in one slot of your bag.");
        slot = new NumberSetting(name + " slot",
            "The slot whose map is drawn. One to nine is the hotbar from the left and the bag follows row by row.",
            1, 1, InventoryUtil.WHOLE_INVENTORY, 1).max(InventoryUtil.WHOLE_INVENTORY)
            .under(shows, Shows.SLOT);
        add(shows, slot);
    }

    @Override
    public boolean visible() {
        return isActive() && (map() != null || editing());
    }

    private Shown map() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null) {
            own.follow(player);
        }
        List<Shown> maps = found.get();
        return maps.isEmpty() ? null : maps.getFirst();
    }

    private List<Shown> find() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return List.of();
        }
        Inventory inventory = player.getInventory();
        List<ItemStack> looked = new ArrayList<>();
        if (shows.is(Shows.SLOT)) {
            looked.add(inventory.getItem(slot.getInt() - 1));
        } else {
            looked.add(player.getMainHandItem());
            looked.add(player.getOffhandItem());
        }
        if (shows.is(Shows.CARRIED)) {
            for (int index = 0; index < InventoryUtil.WHOLE_INVENTORY; index++) {
                looked.add(inventory.getItem(index));
            }
        }
        for (ItemStack stack : looked) {
            Shown shown = shown(stack);
            if (shown != null) {
                return List.of(shown);
            }
        }
        return List.of();
    }

    private Shown shown(ItemStack stack) {
        MapId id = stack.get(DataComponents.MAP_ID);
        MapItemSavedData data = MapPreview.dataOf(id);
        if (data == null) {
            return null;
        }
        MapRenderState state = MapPreview.stateOf(id, data);
        OwnMarker.Found mine = own.find(id, data);
        if (mine == null) {
            return new Shown(state, null);
        }
        state.decorations.remove(mine.index());
        return new Shown(state, mine.view(SIZE));
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        Shown shown = map();
        if (shown == null) {
            context.fill(0, 0, SIZE, SIZE, SAMPLE_PAPER);
            context.outline(0, 0, SIZE, SIZE, SAMPLE_EDGE);
            return;
        }
        MapPreview.draw(context, shown.state(), 0, 0, SIZE);
        LocalPlayer player = OfflineClient.MC.player;
        if (shown.view() != null && player != null) {
            Vec3 at = player.getPosition(OfflineClient.MC.getDeltaTracker().getGameTimeDeltaPartialTick(true));
            MapMarks.self(context, shown.view(), at.x, at.z, player.getYRot());
        }
    }

    @Override
    public int width(Font font) {
        return SIZE;
    }

    @Override
    public int height(Font font) {
        return SIZE;
    }
}
