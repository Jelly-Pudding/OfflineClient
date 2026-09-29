package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// A throw or a bow or trident let go that a module keeps back whilst a hop speeds it up.
// The server adds the move of the last position packet before a shot to its speed. A
// crossbow never takes it. One guard covers every module and a shot one of them lets go
// is never caught again. Only the player's own shots are taken. A throw has to come from
// a right click and a bow or trident must not point at the sky.
public final class HeldShot {

    // A bow drawn this little throws the arrow away.
    private static final float LEAST_POWER = 0.1f;

    // An arrow let go almost straight up is meant to come back down on its shooter.
    // PotionArrows fires its tipped arrows that way.
    private static final float SKYWARD = -80;

    private static boolean firing;

    private final Packet<?> packet;
    private final Item item;
    // Blocks a tick a bow or trident shot leaves at. Nothing for a throw.
    private final double speed;
    // Throws of the same item made before the hop. They all go out together.
    private final List<Packet<?>> gathered = new ArrayList<>();
    private boolean fired;

    private HeldShot(Packet<?> packet, Item item, double speed) {
        this.packet = packet;
        this.item = item;
        this.speed = speed;
    }

    // Every item whose shot takes on the speed of the one who fires it.
    public static boolean boosts(Item item) {
        return item instanceof BowItem || item instanceof TridentItem || throwable(item);
    }

    private static boolean throwable(Item item) {
        return item instanceof EnderpearlItem || item instanceof SnowballItem || item instanceof EggItem
            || item instanceof ThrowablePotionItem || item instanceof ExperienceBottleItem
            || item instanceof WindChargeItem;
    }

    // A pick of those items to act on.
    public static RegistryListSetting<Item> items(String description, List<Item> defaults) {
        return new RegistryListSetting<>("Items", description, BuiltInRegistries.ITEM, defaults)
            .only(HeldShot::boosts);
    }

    // The shot the packet fires when its item is picked. Null for anything else and whilst
    // a held shot goes out.
    public static HeldShot of(Packet<?> packet, RegistryListSetting<Item> items) {
        LocalPlayer player = OfflineClient.MC.player;
        if (firing || player == null) {
            return null;
        }
        HeldShot shot = switch (packet) {
            case ServerboundUseItemPacket use -> thrown(use);
            case ServerboundPlayerActionPacket action -> released(action, player);
            default -> null;
        };
        return shot != null && items.contains(shot.item) ? shot : null;
    }

    private static HeldShot thrown(ServerboundUseItemPacket use) {
        Item item = UseClick.heldAtClick(use.hand());
        return item != null && throwable(item) ? new HeldShot(use, item, 0) : null;
    }

    // The release packet leaves before the client stops using the item. A bow drawn too
    // little and a trident too soon or with riptide fire nothing.
    private static HeldShot released(ServerboundPlayerActionPacket action, LocalPlayer player) {
        if (action.getAction() != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
            || RotationManager.serverPitch() <= SKYWARD) {
            return null;
        }
        ItemStack using = player.getUseItem();
        int ticks = player.getTicksUsingItem();
        if (using.getItem() instanceof BowItem) {
            float power = BowItem.getPowerForTime(ticks);
            return power < LEAST_POWER ? null
                : new HeldShot(action, using.getItem(), power * ProjectileUtil.BOW_SPEED);
        }
        if (using.getItem() instanceof TridentItem && ticks >= TridentItem.THROW_THRESHOLD_TIME
            && EnchantmentHelper.getTridentSpinAttackStrength(using, player) <= 0) {
            return new HeldShot(action, using.getItem(), TridentItem.PROJECTILE_SHOOT_POWER);
        }
        return null;
    }

    public boolean thrown() {
        return packet instanceof ServerboundUseItemPacket;
    }

    // Takes another throw of the same item made before the shot goes. True when it was taken.
    public boolean gather(Packet<?> next) {
        if (fired || !(packet instanceof ServerboundUseItemPacket use)
            || !(next instanceof ServerboundUseItemPacket more) || more.hand() != use.hand()
            || UseClick.heldAtClick(more.hand()) != item) {
            return false;
        }
        gathered.add(more);
        return true;
    }

    // The way the shot flies. A throw carries its own aim and a bow or trident fires along
    // the view the server last heard.
    public Vec3 aim() {
        if (packet instanceof ServerboundUseItemPacket use) {
            return Vec3.directionFromRotation(use.xRot(), use.yRot());
        }
        return Vec3.directionFromRotation(RotationManager.serverPitch(), RotationManager.serverYaw());
    }

    // How fast and which way a bow or trident shot leaves before the hop adds its own.
    public Vec3 releaseVelocity() {
        return aim().scale(speed);
    }

    // Lets the shot and every throw gathered with it go as they were.
    public void fire() {
        fired = true;
        send(packet);
        gathered.forEach(HeldShot::send);
    }

    // Lets the shot go as it was when its trip ended before it could.
    public void finish() {
        if (!fired) {
            fire();
        }
    }

    // Lets every throw go aimed afresh and each of them the given number of times as far
    // as the server takes them. Paper drops use packets past nine a moment.
    public void fireAimed(float yRot, float xRot, int each) {
        if (!(packet instanceof ServerboundUseItemPacket use)) {
            fire();
            return;
        }
        fired = true;
        int made = gathered.size() + 1;
        int total = Math.max(made, Math.min(made * each, UseBudget.remaining()));
        for (int i = 0; i < total; i++) {
            send(new ServerboundUseItemPacket(use.hand(), use.sequence(), yRot, xRot));
        }
    }

    private static void send(Packet<?> packet) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        firing = true;
        try {
            player.connection.send(packet);
        } finally {
            firing = false;
        }
    }
}
