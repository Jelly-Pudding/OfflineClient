package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class EntityOwner extends Module {

    private static final int BACKGROUND = 0x90000000;

    private record Label(String name, int color, Vec3 screen, double distance) {
    }

    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1);
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest entity to label.", 64, 8, 256, 8, " blocks").min(1);
    private final BoolSetting projectiles = new BoolSetting("Projectiles",
        "Label pearls and arrows and anything else thrown.", true);
    private final BoolSetting pets = new BoolSetting("Pets",
        "Label tamed animals.", true);
    private final BoolSetting self = new BoolSetting("Own",
        "Also label what you threw yourself.", false);

    public EntityOwner() {
        super("EntityOwner", "Shows who owns the projectiles and pets around you.", Category.RENDER);
        addSettings(scale, range, projectiles, pets, self);
        searchTags("pearl", "owner", "tamed");
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || !WorldToScreen.update()) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        Vec3 camera = WorldToScreen.cameraPos();
        double maxDistance = range.getValue();
        List<Label> labels = new ArrayList<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            String owner = ownerOf(entity);
            if (owner == null) {
                continue;
            }
            Vec3 feet = entity.getPosition(partialTicks);
            double distance = camera.distanceTo(feet);
            if (distance > maxDistance) {
                continue;
            }
            Vec3 head = feet.add(0, entity.getBbHeight() + 0.5, 0);
            Vec3 screen = WorldToScreen.project(head);
            if (screen != null) {
                labels.add(new Label(owner, colorFor(owner), screen, distance));
            }
        }

        // Far labels draw first.
        labels.sort((a, b) -> Double.compare(b.distance(), a.distance()));
        for (Label label : labels) {
            draw(event.getContext(), label);
        }
    }

    private String ownerOf(Entity entity) {
        if (entity instanceof Projectile projectile) {
            if (!projectiles.isOn()) {
                return null;
            }
            Entity owner = projectile.getOwner();
            if (owner == null) {
                return null;
            }
            if (owner == mc.player && !self.isOn()) {
                return null;
            }
            return owner.getName().getString();
        }
        if (entity instanceof OwnableEntity ownable) {
            if (!pets.isOn()) {
                return null;
            }
            EntityReference<LivingEntity> reference = ownable.getOwnerReference();
            if (reference == null) {
                return null;
            }
            LivingEntity owner = EntityReference.getLivingEntity(reference, mc.level);
            if (owner != null) {
                if (owner == mc.player && !self.isOn()) {
                    return null;
                }
                return owner.getName().getString();
            }
            return nameFromTabList(reference.getUUID());
        }
        return null;
    }

    private String nameFromTabList(UUID uuid) {
        if (uuid == null || mc.getConnection() == null) {
            return null;
        }
        if (uuid.equals(mc.player.getUUID()) && !self.isOn()) {
            return null;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
        if (info != null) {
            return info.getProfile().name();
        }
        return uuid.toString().substring(0, 8);
    }

    private static int colorFor(String name) {
        if (OfflineClient.INSTANCE.getFriendManager().isFriend(name)) {
            return 0xFF4C9BFF;
        }
        return 0xFFFFD060;
    }

    private void draw(GuiGraphicsExtractor context, Label label) {
        Font font = mc.font;
        int width = font.width(label.name());
        float factor = (float) (scale.getValue() * Math.clamp(1 - label.distance() / 80.0, 0.5, 1));

        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate((float) label.screen().x, (float) label.screen().y);
        pose.scale(factor, factor);

        int half = width / 2 + 2;
        context.fill(-half, -font.lineHeight - 2, half, 1, BACKGROUND);
        context.guiRenderState.up();
        context.text(font, label.name(), -width / 2, -font.lineHeight, label.color(), true);
        pose.popMatrix();
    }
}
