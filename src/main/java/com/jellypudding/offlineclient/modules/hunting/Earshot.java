package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.FarShapes;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.Bearing;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

// Marks the sounds the server sends. A sound packet reaches 16 blocks and a louder one
// sixteen times its volume. A goat horn carries 256 that way. Block breaks and mining and
// workstations arrive as level events and reach 64 blocks as explosions do. Paper keeps
// these ranges and only holds back a player hidden from you.
public final class Earshot extends Module {

    // A spot where picked sounds were heard. A new sound close by moves it and starts its fade
    // again. It keeps the name of the first sound or mining would flick between hit and break.
    private static final class Mark {

        private Vec3 at;
        private final String name;
        private long heard;

        private Mark(Vec3 at, String name, long heard) {
            this.at = at;
            this.name = name;
            this.heard = heard;
        }
    }

    // A sound this close to a mark belongs to the same spot. A tunnel stays one mark.
    private static final double MERGE_DISTANCE = 4;

    private static final int MAX_MARKS = 64;

    private static final double BOX_SIZE = 0.5;

    private static final double LABEL_LIFT = 0.6;

    private final RegistryListSetting<SoundEvent> sounds = new RegistryListSetting<>("Sounds",
        "The sounds to mark. Click to pick them.", BuiltInRegistries.SOUND_EVENT, List.of(
            SoundEvents.STONE_BREAK, SoundEvents.STONE_HIT, SoundEvents.DEEPSLATE_BREAK,
            SoundEvents.NETHERRACK_BREAK, SoundEvents.CHEST_OPEN, SoundEvents.BARREL_OPEN,
            SoundEvents.ENDER_CHEST_OPEN, SoundEvents.SHULKER_BOX_OPEN, SoundEvents.WOODEN_DOOR_OPEN,
            SoundEvents.WOODEN_TRAPDOOR_OPEN, SoundEvents.FENCE_GATE_OPEN, SoundEvents.PISTON_EXTEND,
            SoundEvents.GENERIC_EXPLODE.value(), SoundEvents.ANVIL_USE));
    private final NumberSetting ignoreWithin = new NumberSetting("Ignore within",
        "Sounds closer to you than this are left out. Your own chests and blasts stay unmarked.",
        8, 0, 32, 1, " blocks").min(0);
    private final NumberSetting markTime = new NumberSetting("Mark time",
        "How long a mark takes to fade away.", 30, 5, 120, 5, " seconds").min(1);
    private final BoolSetting chat = new BoolSetting("Chat",
        "Posts each new spot in chat with how far away it is.", false);
    private final ColorSetting color = new ColorSetting("Colour",
        "Colour of the marks.", 45, false);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1);

    private final List<Mark> marks = new ArrayList<>();
    // Filled from the network thread and handled on the next tick.
    private final Queue<Packet<?>> heard = new ConcurrentLinkedQueue<>();
    private final WorldWatch world = new WorldWatch();
    // The server reports mining progress to the miner as well. This is the block you last started on.
    private volatile BlockPos digging;

    public Earshot() {
        super("Earshot", "Marks where the sounds you pick come from through walls.", Category.HUNTING);
        addSettings(sounds, ignoreWithin, markTime, chat, color, scale);
        searchTags("sound locator", "sound esp", "hearing", "mining", "chest");
    }

    @Override
    public String getSuffix() {
        return count(marks.size());
    }

    @Override
    protected void onEnable() {
        clear();
        if (inGame()) {
            world.accept();
        }
    }

    @Override
    protected void onDisable() {
        clear();
        world.forget();
    }

    private void clear() {
        marks.clear();
        heard.clear();
        digging = null;
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        // Global events reach the whole server and are Eavesdrop's.
        if (packet instanceof ClientboundSoundPacket || packet instanceof ClientboundSoundEntityPacket
            || packet instanceof ClientboundExplodePacket
            || (packet instanceof ClientboundLevelEventPacket levelEvent && !levelEvent.isGlobalEvent())) {
            heard.add(packet);
        }
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundPlayerActionPacket action
            && action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            digging = action.getPos();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            clear();
        }
        Packet<?> packet;
        while ((packet = heard.poll()) != null) {
            hear(packet);
        }
        long oldest = System.currentTimeMillis() - lifeMillis();
        marks.removeIf(mark -> mark.heard < oldest);
    }

    private void hear(Packet<?> packet) {
        switch (packet) {
            case ClientboundSoundPacket sound ->
                note(sound.getSound().value(), new Vec3(sound.getX(), sound.getY(), sound.getZ()));
            case ClientboundSoundEntityPacket sound -> {
                Entity source = mc.level.getEntity(sound.getId());
                if (source != null) {
                    note(sound.getSound().value(), source.getBoundingBox().getCenter());
                }
            }
            case ClientboundExplodePacket blast when blast.playSound() ->
                note(blast.explosionSound().value(), blast.center());
            case ClientboundLevelEventPacket levelEvent -> {
                SoundEvent sound = levelSound(levelEvent);
                if (sound != null) {
                    note(sound, Vec3.atCenterOf(levelEvent.getPos()));
                }
            }
            default -> {
            }
        }
    }

    // The sound the game plays for a level event. Null for one that plays none worth a mark.
    private SoundEvent levelSound(ClientboundLevelEventPacket packet) {
        BlockPos pos = packet.getPos();
        int data = packet.getData();
        return switch (packet.getType()) {
            case LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK -> {
                BlockState broken = Block.stateById(data);
                yield broken.isAir() ? null : broken.getSoundType().getBreakSound();
            }
            case LevelEvent.PARTICLES_AND_SOUND_DESTROY_PROGRESS -> {
                BlockState mined = mc.level.getBlockState(pos);
                yield mined.isAir() || pos.equals(digging) ? null : mined.getSoundType().getHitSound();
            }
            case LevelEvent.SOUND_DISPENSER_DISPENSE -> SoundEvents.DISPENSER_DISPENSE;
            case LevelEvent.SOUND_DISPENSER_FAIL -> SoundEvents.DISPENSER_FAIL;
            case LevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH -> SoundEvents.DISPENSER_LAUNCH;
            case LevelEvent.SOUND_FIREWORK_SHOOT -> SoundEvents.FIREWORK_ROCKET_SHOOT;
            case LevelEvent.SOUND_EXTINGUISH_FIRE -> switch (data) {
                case 0 -> SoundEvents.FIRE_EXTINGUISH;
                case 1 -> SoundEvents.GENERIC_EXTINGUISH_FIRE;
                default -> null;
            };
            case LevelEvent.SOUND_ANVIL_BROKEN -> SoundEvents.ANVIL_DESTROY;
            case LevelEvent.SOUND_ANVIL_USED -> SoundEvents.ANVIL_USE;
            case LevelEvent.SOUND_ANVIL_LAND -> SoundEvents.ANVIL_LAND;
            case LevelEvent.SOUND_BREWING_STAND_BREW -> SoundEvents.BREWING_STAND_BREW;
            case LevelEvent.SOUND_GRINDSTONE_USED -> SoundEvents.GRINDSTONE_USE;
            case LevelEvent.SOUND_PAGE_TURN -> SoundEvents.BOOK_PAGE_TURN;
            case LevelEvent.SOUND_SMITHING_TABLE_USED -> SoundEvents.SMITHING_TABLE_USE;
            case LevelEvent.SOUND_CRAFTER_CRAFT -> SoundEvents.CRAFTER_CRAFT;
            case LevelEvent.SOUND_CRAFTER_FAIL -> SoundEvents.CRAFTER_FAIL;
            case LevelEvent.SOUND_WIND_CHARGE_SHOOT -> SoundEvents.WIND_CHARGE_THROW;
            case LevelEvent.SOUND_SPELL_POTION_SPLASH, LevelEvent.SOUND_INSTANT_POTION_SPLASH ->
                SoundEvents.SPLASH_POTION_BREAK;
            case LevelEvent.COMPOSTER_FILL -> data > 0 ? SoundEvents.COMPOSTER_FILL_SUCCESS : SoundEvents.COMPOSTER_FILL;
            case LevelEvent.LAVA_FIZZ -> SoundEvents.LAVA_EXTINGUISH;
            case LevelEvent.REDSTONE_TORCH_BURNOUT -> SoundEvents.REDSTONE_TORCH_BURNOUT;
            case LevelEvent.END_PORTAL_FRAME_FILL -> SoundEvents.END_PORTAL_FRAME_FILL;
            case LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH -> SoundEvents.BONE_MEAL_USE;
            default -> null;
        };
    }

    private void note(SoundEvent sound, Vec3 at) {
        if (!picked(sound) || mc.player.position().distanceTo(at) < ignoreWithin.getValue()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Mark mark : marks) {
            if (mark.at.distanceTo(at) <= MERGE_DISTANCE) {
                mark.at = at;
                mark.heard = now;
                return;
            }
        }
        String name = nameOf(sound);
        marks.add(new Mark(at, name, now));
        if (marks.size() > MAX_MARKS) {
            marks.removeFirst();
        }
        if (chat.isOn()) {
            say(name, at);
        }
    }

    // A sound sent without its registry entry is matched by name.
    private boolean picked(SoundEvent sound) {
        return sounds.contains(BuiltInRegistries.SOUND_EVENT.getValue(sound.location()));
    }

    // The subtitle the game shows for the sound or its name when it has none.
    private static String nameOf(SoundEvent sound) {
        WeighedSoundEvents events = mc.getSoundManager().getSoundEvent(sound.location());
        Component subtitle = events == null ? null : events.getSubtitle();
        return subtitle == null ? sound.location().getPath() : subtitle.getString();
    }

    private void say(String name, Vec3 at) {
        Vec3 me = mc.player.position();
        ChatUtil.message("§bEarshot §f" + name + " §7" + Math.round(me.distanceTo(at)) + " blocks "
            + Bearing.between(me, at).compass() + " at §f" + BlockUtil.text(BlockPos.containing(at)) + "§7.");
    }

    private long lifeMillis() {
        return Math.round(markTime.getValue() * TimeUnit.SECONDS.toMillis(1));
    }

    // Full strength when heard and nothing once the mark time has passed.
    private float strength(Mark mark, long now) {
        return (float) Math.clamp(1 - (now - mark.heard) / (double) lifeMillis(), 0, 1);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || marks.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        long now = System.currentTimeMillis();
        for (Mark mark : marks) {
            AABB box = FarShapes.pullIn(AABB.ofSize(mark.at, BOX_SIZE, BOX_SIZE, BOX_SIZE));
            batch.outlineBox(box, ColorUtil.fade(color.getColor(), strength(mark, now)), true);
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || marks.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        Vec3 camera = WorldToScreen.cameraPos();
        long now = System.currentTimeMillis();
        for (Mark mark : marks) {
            Vec3 screen = WorldToScreen.project(mark.at.add(0, LABEL_LIFT, 0));
            if (screen == null) {
                continue;
            }
            float strength = strength(mark, now);
            String distance = String.format(Locale.ROOT, " %dm", Math.round(camera.distanceTo(mark.at)));
            RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                List.of(mark.name, distance),
                List.of(ColorUtil.fade(color.getColor(), strength), ColorUtil.fade(RenderUtil.MUTED_TEXT, strength)),
                ColorUtil.fade(RenderUtil.LABEL_BACKGROUND, strength));
        }
    }
}
