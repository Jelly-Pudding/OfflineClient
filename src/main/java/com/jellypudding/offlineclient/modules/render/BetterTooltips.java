package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.render.ContainerPreview;
import com.jellypudding.offlineclient.render.MapPreview;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.List;

// GuiGraphicsExtractorMixin remembers the hovered stack and asks for the tooltip line.
public final class BetterTooltips extends Module {

    private static final int SHULKER_SLOTS = 27;
    private static final int ENDER_CHEST_SLOTS = 27;
    private static final int COLUMNS = 9;

    private final BoolSetting containers = new BoolSetting("Containers",
        "Preview what is inside a shulker box.", true);
    private final BoolSetting enderChest = new BoolSetting("Ender chest",
        "Preview your ender chest as you last saw it.", true);
    private final BoolSetting maps = new BoolSetting("Maps",
        "Preview filled maps.", true);
    private final NumberSetting mapSize = new NumberSetting("Map size",
        "How big the map preview is drawn.", 128, 64, 256, 8, " px").min(16).max(512)
        .under(maps);
    private final BoolSetting requireShift = new BoolSetting("Hold shift",
        "Only show the preview whilst the sneak key is down.", false);

    // What the ender chest held the last time it was open this session.
    private final List<ItemStack> remembered = new ArrayList<>();
    private ItemStack hovered = ItemStack.EMPTY;

    public BetterTooltips() {
        super("BetterTooltips", "Previews containers and maps in tooltips.", Category.RENDER);
        addSettings(containers, enderChest, maps, mapSize, requireShift);
        searchTags("tooltip", "shulker", "preview");
    }

    @Override
    protected void onDisable() {
        hovered = ItemStack.EMPTY;
    }

    // Called from the mixin whilst the game builds an item tooltip.
    public void setHovered(ItemStack stack) {
        hovered = stack;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level == null) {
            // The copy belongs to the server it was taken on.
            remembered.clear();
            return;
        }
        if (!enderChest.isOn() || !(mc.gui.screen() instanceof ContainerScreen screen)) {
            return;
        }
        if (!screen.getTitle().getString().equals(Blocks.ENDER_CHEST.getName().getString())) {
            return;
        }
        ChestMenu menu = screen.getMenu();
        Container container = menu.getContainer();
        if (container.getContainerSize() < ENDER_CHEST_SLOTS) {
            return;
        }
        remembered.clear();
        for (int i = 0; i < ENDER_CHEST_SLOTS; i++) {
            remembered.add(container.getItem(i).copy());
        }
    }

    // Null when there is nothing worth previewing.
    public ClientTooltipComponent buildPreview() {
        ItemStack stack = hovered;
        hovered = ItemStack.EMPTY;
        if (!isEnabled() || stack.isEmpty()) {
            return null;
        }
        if (requireShift.isOn() && !shiftHeld()) {
            return null;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (containers.isOn() && contents != null && contents.nonEmptyItems().iterator().hasNext()) {
            NonNullList<ItemStack> items = NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY);
            contents.copyInto(items);
            return new ContainerPreview(items, COLUMNS);
        }
        if (enderChest.isOn() && stack.getItem() == Items.ENDER_CHEST && !remembered.isEmpty()) {
            return new ContainerPreview(new ArrayList<>(remembered), COLUMNS);
        }
        if (maps.isOn()) {
            return mapPreview(stack);
        }
        return null;
    }

    // The game lets go of every key binding whilst a screen is open.
    private static boolean shiftHeld() {
        return InputUtil.physicallyHeld(mc.options.keyShift);
    }

    private ClientTooltipComponent mapPreview(ItemStack stack) {
        MapId id = stack.get(DataComponents.MAP_ID);
        if (id == null || mc.level == null) {
            return null;
        }
        MapItemSavedData data = MapItem.getSavedData(id, mc.level);
        if (data == null) {
            return null;
        }
        MapRenderState state = new MapRenderState();
        mc.getMapRenderer().extractRenderState(id, data, state);
        return new MapPreview(state, mapSize.getInt());
    }
}
