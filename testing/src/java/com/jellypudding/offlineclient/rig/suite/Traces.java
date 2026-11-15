package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

// What a module has to give back once it is off. Every option of the game and every key it may
// hold and what the player may do and what the server still believes of them. Where the player
// stands and what they carry is theirs to change and is left out.
final class Traces {

    private static final Map<String, Holder<Attribute>> ATTRIBUTES = attributes();
    private static final List<String> SERVER_FACTS = List.of("sneaking", "flying", "gliding");
    private static final double APART = 0.3;
    private static final String APART_KEY = "server and game apart";
    private static final int POLL_TICKS = 5;

    private Traces() {
    }

    private static Map<String, Holder<Attribute>> attributes() {
        Map<String, Holder<Attribute>> named = new LinkedHashMap<>();
        named.put("movement speed", Attributes.MOVEMENT_SPEED);
        named.put("step height", Attributes.STEP_HEIGHT);
        named.put("jump strength", Attributes.JUMP_STRENGTH);
        named.put("gravity", Attributes.GRAVITY);
        named.put("safe fall", Attributes.SAFE_FALL_DISTANCE);
        named.put("block reach", Attributes.BLOCK_INTERACTION_RANGE);
        named.put("entity reach", Attributes.ENTITY_INTERACTION_RANGE);
        named.put("scale", Attributes.SCALE);
        named.put("sneaking speed", Attributes.SNEAKING_SPEED);
        named.put("mining speed", Attributes.BLOCK_BREAK_SPEED);
        return named;
    }

    static Map<String, String> of(Rig rig) {
        Map<String, String> state = rig.ask(Traces::read);
        Map<String, String> held = rig.world().facts();
        SERVER_FACTS.forEach(fact -> state.put("server " + fact, held.get(fact)));
        state.put(APART_KEY, String.valueOf(
            rig.world().where().distanceTo(rig.ask(mc -> mc.player.position())) > APART));
        return state;
    }

    // The same once the server has caught up with the game. A module that moved the player in its
    // last tick gets this long for the two to agree again.
    static Map<String, String> settled(Rig rig, int limitTicks) {
        Map<String, String> state = of(rig);
        for (int waited = 0; waited < limitTicks && Boolean.parseBoolean(state.get(APART_KEY)); waited += POLL_TICKS) {
            rig.ticks(POLL_TICKS);
            state = of(rig);
        }
        return state;
    }

    // Every difference as what it was and what it is.
    static List<String> changed(Map<String, String> before, Map<String, String> after) {
        List<String> changed = new ArrayList<>();
        Map<String, String> all = new TreeMap<>(before);
        after.forEach(all::putIfAbsent);
        for (String name : all.keySet()) {
            String was = before.get(name);
            String is = after.get(name);
            if (was == null ? is != null : !was.equals(is)) {
                changed.add(name + " was " + was + " and is " + is);
            }
        }
        return changed;
    }

    private static Map<String, String> read(Minecraft mc) {
        Map<String, String> state = new TreeMap<>();
        for (Field field : Options.class.getDeclaredFields()) {
            if (OptionInstance.class.isAssignableFrom(field.getType())) {
                state.put("option " + field.getName(), String.valueOf(option(field, mc.options).get()));
            }
        }
        state.put("camera", mc.options.getCameraType().name());
        state.put("camera on the player", String.valueOf(mc.getCameraEntity() == mc.player));
        state.put("interface hidden", String.valueOf(mc.gui.hud.isHidden()));
        for (KeyMapping key : mc.options.keyMappings) {
            if (key.isDown()) {
                state.put("key " + key.getName(), "down");
            }
        }
        for (PlayerModelPart part : PlayerModelPart.values()) {
            state.put("skin " + part.getId(), String.valueOf(mc.options.isModelPartEnabled(part)));
        }
        LocalPlayer player = mc.player;
        Abilities may = player.getAbilities();
        state.put("no physics", String.valueOf(player.noPhysics));
        state.put("flying", String.valueOf(may.flying));
        state.put("may fly", String.valueOf(may.mayfly));
        state.put("fly speed", told(may.getFlyingSpeed()));
        state.put("walk speed", told(may.getWalkingSpeed()));
        ATTRIBUTES.forEach((name, attribute) -> state.put(name, told(player.getAttributeBaseValue(attribute))));
        state.put("height", told(player.getBbHeight()));
        state.put("width", told(player.getBbWidth()));
        state.put("using an item", String.valueOf(player.isUsingItem()));
        state.put("mining", String.valueOf(mc.gameMode.isDestroying()));
        state.put("rain", told(mc.level.getRainLevel(1)));
        state.put("thunder", told(mc.level.getThunderLevel(1)));
        state.put("tick rate", told(mc.level.tickRateManager().tickrate()));
        return state;
    }

    private static OptionInstance<?> option(Field field, Options options) {
        try {
            field.setAccessible(true);
            return (OptionInstance<?>) field.get(options);
        } catch (ReflectiveOperationException e) {
            throw new RigFailure("The option " + field.getName() + " could not be read", e);
        }
    }

    private static String told(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
