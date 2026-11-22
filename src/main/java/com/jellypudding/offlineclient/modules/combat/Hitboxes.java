package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.WeaponKinds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

// Melee swings apply one margin to every entity regardless of the kinds list.
// Arrows instead read the pick radius of the entity kinds the list names.
// The crosshair catches listed blocks by a box grown inside their own block space.
public final class Hitboxes extends Module {

    private final NumberSetting expand = new NumberSetting("Expand",
        "How far the hitbox of each listed entity grows on every side.", 0.25, 0.05, 1, 0.05, " blocks")
        .min(0);
    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>(
        "Entities", "Kinds of entity to grow. Arrows only take the bigger box on these kinds.",
        BuiltInRegistries.ENTITY_TYPE, List.of(EntityTypes.PLAYER));
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Leaves the hitboxes of friends alone.", true);
    private final BoolSetting onlyWithWeapon = new BoolSetting("Only with weapon",
        "Only grows entity hitboxes whilst one of the ticked weapons is in your main hand.", false);
    private final WeaponKinds weapons = new WeaponKinds(onlyWithWeapon);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Small blocks such as buttons and torches the crosshair catches by a bigger box. Click to pick them.",
        BuiltInRegistries.BLOCK, List.of());
    // A box can grow no further than the space of its own block or it would cover its neighbours.
    private final NumberSetting blockGrowth = new NumberSetting("Block growth",
        "How far the box of those blocks grows towards a whole block.", 100, 10, 100, 10, "%")
        .min(1).max(100)
        .under(blocks, () -> blocks.size() > 0);

    public Hitboxes() {
        super("Hitboxes", "Makes entities and small blocks easier to hit by growing their boxes.",
            Category.COMBAT);
        addSettings(expand, entities, ignoreFriends, onlyWithWeapon);
        addSettings(weapons.settings());
        addSettings(blocks, blockGrowth);
        searchTags("aim", "reach", "pick", "block hitbox");
    }

    @Override
    public String getSuffix() {
        return expand.getValueString();
    }

    // The copies the built in server holds keep their size for whatever it aims at them.
    public double expansionFor(Entity entity) {
        if (!isEnabled() || entity == mc.player || !entity.level().isClientSide() || !holdingWeapon()) {
            return 0;
        }
        if (ignoreFriends.isOn() && EntityUtil.isFriend(entity) || Modules.isBot(entity)) {
            return 0;
        }
        return entities.contains(entity.getType()) ? expand.getValue() : 0;
    }

    public float margin() {
        if (!isEnabled() || entities.resolved().isEmpty() || !holdingWeapon()) {
            return 0;
        }
        return expand.getFloat();
    }

    // A share of the way to the edge of the block's own space. Nought leaves it alone.
    public double aimGrowth(BlockState state) {
        return isEnabled() && blocks.contains(state.getBlock()) ? blockGrowth.getValue() / 100 : 0;
    }

    private boolean holdingWeapon() {
        return !onlyWithWeapon.isOn() || mc.player == null || weapons.matches(mc.player.getMainHandItem());
    }
}
