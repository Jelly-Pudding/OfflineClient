package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;

public final class ItemEsp extends Module {

    public enum Size { ACCURATE, FANCY }

    // How much a fancy box grows and how far it lifts off the ground.
    private static final double FANCY_GROW = 0.1;
    private static final double FANCY_LIFT = 0.05;

    private final BoolSetting boxes = new BoolSetting("Boxes",
        "Draw a box around every item.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.LINES, 48).under(boxes);
    private final EnumSetting<Size> size = new EnumSetting<>("Box size",
        "How big the box around an item is.", Size.FANCY)
        .describe(Size.ACCURATE, "The hitbox of the item itself.")
        .describe(Size.FANCY, "A little larger and lifted off the floor so it is easier to see.")
        .under(boxes);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line to every item.", false);
    private final ColorSetting tracerColor = new ColorSetting("Tracer colour",
        "Colour of the lines.", 48, false).under(tracers);
    private final BoolSetting steadyView = new BoolSetting("Steady view",
        "Stops the view bobbing whilst the tracers are on so the lines do not wobble.", true)
        .under(tracers);
    private final BoolSetting limitRange = new BoolSetting("Limit range",
        "Only shows items within a set distance.", false);
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest an item can be and still show.", 64, 8, 256, 8, " blocks").min(1)
        .under(limitRange);
    private final BoolSetting everything = new BoolSetting("Everything",
        "Show every dropped item.", true);
    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The items to show.", BuiltInRegistries.ITEM,
        List.of(Items.DIAMOND, Items.NETHERITE_INGOT, Items.ENCHANTED_GOLDEN_APPLE,
            Items.ELYTRA, Items.TOTEM_OF_UNDYING, Items.SHULKER_BOX))
        .unless(everything);

    private int count;

    public ItemEsp() {
        super("ItemESP", "See dropped items through walls.", Category.RENDER);
        addSettings(boxes);
        addSettings(style.settings());
        addSettings(size, tracers, tracerColor, steadyView, limitRange, range, everything, items);
        searchTags("item tracers", "drops");
    }

    @Override
    public String getSuffix() {
        return count == 0 ? null : String.valueOf(count);
    }

    @Override
    protected void onDisable() {
        count = 0;
    }

    // Read by GameRendererMixin.
    public boolean holdsViewStill() {
        return isEnabled() && tracers.isOn() && steadyView.isOn();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        boolean filter = !everything.isOn();
        int found = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item)) {
                continue;
            }
            if (filter && !items.contains(item.getItem().getItem())) {
                continue;
            }
            if (limitRange.isOn() && mc.player.distanceTo(item) > range.getValue()) {
                continue;
            }
            found++;
            AABB box = shown(EntityUtil.lerpedBox(item, event.getPartialTicks()));
            if (boxes.isOn()) {
                style.draw(batch, box, true);
            }
            if (tracers.isOn()) {
                batch.tracer(box.getCenter(), tracerColor.getColor(), true);
            }
        }
        count = found;
    }

    // An item hitbox is tiny so the fancy box is grown and lifted clear of the floor.
    private AABB shown(AABB box) {
        return size.is(Size.ACCURATE) ? box : box.inflate(FANCY_GROW).move(0, FANCY_LIFT, 0);
    }
}
