package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.render.MapPreview;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

// A map you carry drawn on the screen. The one in your hand comes first and then the first
// one in your bag that the server has sent.
public final class MapElement extends HudElement {

    // Half the size a map is drawn at in your hand before any stretch.
    private static final int SIZE = 64;
    // The pale paper of an empty map.
    private static final int SAMPLE_PAPER = 0x60D8C8A0;
    private static final int SAMPLE_EDGE = 0xC0A08A60;

    // Each search builds the picture of the map it finds.
    private final RowCache<MapRenderState> found = new RowCache<>(MapElement::find);

    public MapElement() {
        super("Map", "A map from your inventory drawn on the screen.", false, 100, 50);
    }

    @Override
    public boolean visible() {
        return isActive() && (map() != null || editing());
    }

    private MapRenderState map() {
        List<MapRenderState> maps = found.get();
        return maps.isEmpty() ? null : maps.getFirst();
    }

    private static List<MapRenderState> find() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return List.of();
        }
        for (ItemStack held : List.of(player.getMainHandItem(), player.getOffhandItem())) {
            MapRenderState state = MapPreview.stateOf(held);
            if (state != null) {
                return List.of(state);
            }
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < InventoryUtil.WHOLE_INVENTORY; slot++) {
            MapRenderState state = MapPreview.stateOf(inventory.getItem(slot));
            if (state != null) {
                return List.of(state);
            }
        }
        return List.of();
    }

    // A blank sheet the size of the map stands in whilst the editor is open and you carry none.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        MapRenderState state = map();
        if (state != null) {
            MapPreview.draw(context, state, 0, 0, SIZE);
            return;
        }
        context.fill(0, 0, SIZE, SIZE, SAMPLE_PAPER);
        context.outline(0, 0, SIZE, SIZE, SAMPLE_EDGE);
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
