package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

// The behaviour lives in LocalPlayerMixin and AbstractClientPlayerMixin which read these settings.
public final class AntiBlind extends Module {

    // What Speed and Slowness hang on the walking pace. Each multiplies the whole of it.
    private static final List<Identifier> PACE_EFFECTS = List.of(Identifier.withDefaultNamespace("effect.speed"),
        Identifier.withDefaultNamespace("effect.slowness"));

    private final BoolSetting blindness = new BoolSetting("Blindness",
        "Ignores the blindness effect.", true);
    private final BoolSetting darkness = new BoolSetting("Darkness",
        "Ignores the darkness effect and its pulsing dim.", true);
    private final BoolSetting nausea = new BoolSetting("Nausea",
        "Stops the screen spinning from the nausea effect.", true);
    private final BoolSetting portal = new BoolSetting("Portal spin",
        "Stops the screen spinning whilst you stand in a portal.", true);
    private final BoolSetting zoom = new BoolSetting("Effect zoom",
        "Stops Speed from widening your view and Slowness from narrowing it. A sprint still widens it.", false);

    public AntiBlind() {
        super("AntiBlind", "Ignores effects that ruin your view.", Category.RENDER);
        addSettings(blindness, darkness, nausea, portal, zoom);
        searchTags("blindness", "darkness", "nausea", "no nausea", "portal", "fov", "field of view");
    }

    public boolean blocksBlindness() {
        return blindness.isOn();
    }

    public boolean blocksDarkness() {
        return darkness.isOn();
    }

    public boolean blocksNausea() {
        return nausea.isOn();
    }

    public boolean blocksPortal() {
        return portal.isOn();
    }

    // A Slowness strong enough to stop the player leaves nothing to divide by.
    public double paceForView(AbstractClientPlayer player, double pace) {
        AttributeInstance walking = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (!zoom.isOn() || walking == null) {
            return pace;
        }
        for (Identifier effect : PACE_EFFECTS) {
            AttributeModifier change = walking.getModifier(effect);
            if (change != null && change.amount() > -1) {
                pace /= 1 + change.amount();
            }
        }
        return pace;
    }
}
