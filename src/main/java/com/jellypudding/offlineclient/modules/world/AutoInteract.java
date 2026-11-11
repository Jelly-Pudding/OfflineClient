package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.EntityUseAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;

import java.util.List;

// Right clicks the mobs you pick with whatever you hold. What that does is up to the item. A
// bucket milks and a dye colours and a lead ties and a water bucket catches a fish.
public final class AutoInteract extends EntityUseAura {

    // Long enough that a mob used once never comes round again whilst the module stays on.
    private static final int ONCE = Integer.MAX_VALUE / 2;

    private final RegistryListSetting<EntityType<?>> mobs = new RegistryListSetting<>("Mobs",
        "The mobs the item is used on. Click to pick them.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.COW, EntityTypes.MOOSHROOM, EntityTypes.GOAT));
    private final BoolSetting frames = new BoolSetting("Fill item frames",
        "Puts the item you hold into every empty item frame in reach.", false);
    private final BoolSetting repeat = new BoolSetting("Again and again",
        "Uses the item on the same mob over and over. Off uses it on each mob once.", false);
    private final NumberSetting interval = new NumberSetting("Interval",
        "Ticks before the same mob gets the item again.", 20, 1, 200, 1, " ticks").under(repeat);

    public AutoInteract() {
        super("AutoInteract", "Uses the item you hold on the mobs you pick as they come near.");
        addSettings(mobs, frames, range, hand, repeat, interval, rotate);
        searchTags("milk", "dye", "leash", "lead", "bucket", "item frame", "entity interact", "use on mobs");
    }

    @Override
    public String getSuffix() {
        return count(count(), "used");
    }

    // A frame shows its item only once the server answers. A second click before that would
    // turn the item it was given.
    @Override
    protected int restTicks(Entity entity) {
        return repeat.isOn() && !(entity instanceof ItemFrame) ? interval.getInt() : ONCE;
    }

    // A click on a frame that holds something would turn the item in it.
    @Override
    protected boolean wanted(Entity entity, ItemStack held) {
        if (entity instanceof ItemFrame frame) {
            return frames.isOn() && frame.getItem().isEmpty();
        }
        return mobs.contains(entity.getType());
    }
}
