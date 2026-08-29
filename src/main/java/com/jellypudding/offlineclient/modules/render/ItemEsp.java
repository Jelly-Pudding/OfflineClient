package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
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

    private final BoolSetting boxes = new BoolSetting("Boxes",
        "Draw a box around every item.", true);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line to every item.", false);
    private final BoolSetting limitRange = new BoolSetting("Limit range",
        "Only shows items within a set distance.", false);
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest an item can be and still show.", 64, 8, 256, 8, " blocks").min(1)
        .under(limitRange);
    private final BoolSetting everything = new BoolSetting("Everything",
        "Show every dropped item.", true);
    private final RegistryListSetting<Item> items = new RegistryListSetting<Item>("Items",
        "The items to show.", BuiltInRegistries.ITEM,
        List.of(Items.DIAMOND, Items.NETHERITE_INGOT, Items.ENCHANTED_GOLDEN_APPLE,
            Items.ELYTRA, Items.TOTEM_OF_UNDYING, Items.SHULKER_BOX))
        .unless(everything);
    private final ColorSetting color = new ColorSetting("Colour",
        "Colour of the boxes and lines.", 48, false);

    private int count;

    public ItemEsp() {
        super("ItemESP", "See dropped items through walls.", Category.RENDER);
        addSettings(boxes, tracers, limitRange, range, everything, items, color);
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

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        boolean filter = !everything.isOn();
        int tint = color.getColor();
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
            AABB box = EntityUtil.lerpedBox(item, event.getPartialTicks());
            if (boxes.isOn()) {
                batch.outlineBox(box, tint, true);
            }
            if (tracers.isOn()) {
                batch.tracer(box.getCenter(), tint, true);
            }
        }
        count = found;
    }
}
