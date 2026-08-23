package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

// Drawn on the HUD at the projected head position.
public final class Nametags extends Module {

    private static final int BACKGROUND = 0x90000000;
    private static final int ITEM_SIZE = 16;
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private record Tag(Player player, Vec3 screen, double distance) {
    }

    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the tags.", 1, 0.5, 3, 0.1).min(0.1);
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest player to tag.", 128, 16, 256, 8, " blocks").min(1);
    private final BoolSetting health = new BoolSetting("Health",
        "Show hearts left including absorption.", true);
    private final BoolSetting ping = new BoolSetting("Ping",
        "Show the player's latency.", true);
    private final BoolSetting distance = new BoolSetting("Distance",
        "Show how far away the player is.", false);
    private final BoolSetting items = new BoolSetting("Items",
        "Show what they hold in each hand.", true);
    private final BoolSetting armor = new BoolSetting("Armor",
        "Show their armour next to the held items.", true);
    private final BoolSetting durability = new BoolSetting("Durability",
        "Show the durability bar on each item.", true);
    private final BoolSetting self = new BoolSetting("Self",
        "Tag yourself whilst in third person or Freecam.", true);

    public Nametags() {
        super("Nametags", "Shows health and ping and gear above players.", Category.RENDER);
        addSettings(scale, range, health, ping, distance, items, armor, durability, self);
        searchTags("name tags", "player info");
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || !WorldToScreen.update()) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        Vec3 camera = WorldToScreen.cameraPos();
        double maxDistance = range.getValue();
        List<Tag> tags = new ArrayList<>();

        for (Player player : mc.level.players()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (player == mc.player && !showSelf()) {
                continue;
            }
            Vec3 feet = player.getPosition(partialTicks);
            double dist = camera.distanceTo(feet);
            if (dist > maxDistance) {
                continue;
            }
            Vec3 head = feet.add(0, player.getBbHeight() + 0.5, 0);
            Vec3 screen = WorldToScreen.project(head);
            if (screen != null) {
                tags.add(new Tag(player, screen, dist));
            }
        }

        // Far tags draw first.
        tags.sort((a, b) -> Double.compare(b.distance(), a.distance()));
        for (Tag tag : tags) {
            drawTag(event.getContext(), tag);
        }
    }

    private boolean showSelf() {
        if (!self.isOn()) {
            return false;
        }
        if (Modules.enabled(Freecam.class)) {
            return true;
        }
        return !mc.options.getCameraType().isFirstPerson();
    }

    private void drawTag(GuiGraphicsExtractor context, Tag tag) {
        Player player = tag.player();
        Font font = mc.font;

        String name = player.getGameProfile().name();
        boolean friend = OfflineClient.INSTANCE.getFriendManager().isFriend(name);
        int nameColor = friend ? 0xFF4C9BFF : player == mc.player ? 0xFFB0FFB0 : 0xFFFFFFFF;

        List<String> parts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        parts.add(name);
        colors.add(nameColor);

        if (health.isOn()) {
            float hp = player.getHealth() + player.getAbsorptionAmount();
            float max = player.getMaxHealth() + player.getAbsorptionAmount();
            float fraction = max <= 0 ? 0 : hp / max;
            int color = fraction > 0.66f ? 0xFF50FF50 : fraction > 0.33f ? 0xFFFFD040 : 0xFFFF5050;
            parts.add(String.format(" %.0f", hp));
            colors.add(color);
        }
        if (ping.isOn()) {
            int latency = latencyOf(player);
            if (latency >= 0) {
                int color = latency < 100 ? 0xFF50FF50 : latency < 250 ? 0xFFFFD040 : 0xFFFF5050;
                parts.add(" " + latency + "ms");
                colors.add(color);
            }
        }
        if (distance.isOn() && player != mc.player) {
            parts.add(String.format(" %.1fm", tag.distance()));
            colors.add(0xFFB0B0C0);
        }

        int textWidth = 0;
        for (String part : parts) {
            textWidth += font.width(part);
        }
        int textHeight = font.lineHeight;

        List<ItemStack> gear = gearOf(player);
        int gearWidth = gear.size() * ITEM_SIZE;

        // Shrink with distance down to half size.
        float factor = (float) (scale.getValue() * Math.clamp(1 - tag.distance() / 100.0, 0.5, 1));

        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate((float) tag.screen().x, (float) tag.screen().y);
        pose.scale(factor, factor);

        int halfWidth = textWidth / 2 + 2;
        context.fill(-halfWidth, -textHeight - 2, halfWidth, 1, BACKGROUND);
        context.guiRenderState.up();
        int x = -textWidth / 2;
        for (int i = 0; i < parts.size(); i++) {
            context.text(font, parts.get(i), x, -textHeight, colors.get(i), true);
            x += font.width(parts.get(i));
        }

        if (!gear.isEmpty()) {
            context.guiRenderState.up();
            int gx = -gearWidth / 2;
            int gy = -textHeight - 4 - ITEM_SIZE;
            for (ItemStack stack : gear) {
                context.item(stack, gx, gy);
                if (durability.isOn()) {
                    context.itemDecorations(font, stack, gx, gy);
                }
                gx += ITEM_SIZE;
            }
        }
        pose.popMatrix();
    }

    // Held items first and then armour from head to feet.
    private List<ItemStack> gearOf(Player player) {
        List<ItemStack> gear = new ArrayList<>();
        if (items.isOn()) {
            addIfPresent(gear, player.getMainHandItem());
            addIfPresent(gear, player.getOffhandItem());
        }
        if (armor.isOn()) {
            for (EquipmentSlot slot : ARMOR) {
                addIfPresent(gear, player.getItemBySlot(slot));
            }
        }
        return gear;
    }

    private static void addIfPresent(List<ItemStack> list, ItemStack stack) {
        if (!stack.isEmpty()) {
            list.add(stack);
        }
    }

    // Ping from the tab list or -1 if the player is not on it.
    private int latencyOf(Player player) {
        if (mc.getConnection() == null) {
            return -1;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
        return info == null ? -1 : info.getLatency();
    }
}
