package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.modules.render.Weather;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.AntiCheatGuess;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EffectText;
import com.jellypudding.offlineclient.util.Feed;
import com.jellypudding.offlineclient.util.HurtReport;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.PullBacks;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// What Notifier tells you about yourself. The server pulling you back and the anti cheat it seems
// to run and what hurt you and bad effects and worn armour and rain.
final class SelfWarnings {

    private static final Minecraft MC = OfflineClient.MC;

    // A deep tone that stands apart from the soft ping.
    private static final float WARNING_PITCH = 0.5f;

    // After a pull back line the next ones wait this long and are counted in one line.
    private static final int PULL_BACK_QUIET_TICKS = 60;

    private static final double PERCENT = 100;

    // A piece that was warned about is warned about again once it has been mended this many
    // points of a hundred past the line and has worn down to it once more.
    private static final double MENDED_MARGIN = 5;

    private final BoolSetting pullBacks = new BoolSetting("Pull backs",
        "Says when the server pulls you back to where you were.", true);
    private final BoolSetting antiCheat = new BoolSetting("Anti cheat",
        "Names the anti cheat the server seems to run when you join.", true);

    private final BoolSetting hitsTaken = new BoolSetting("Hits taken",
        "Says in chat what hurt you and how far away and which way it came from.", false);

    private final BoolSetting badEffects = new BoolSetting("Bad effects",
        "Warns you in chat and with a deep tone when you gain one of the effects below.", true);
    private final RegistryListSetting<MobEffect> warnEffects = new RegistryListSetting<>("Effects",
        "The effects that set off the warning. Click to pick them.", BuiltInRegistries.MOB_EFFECT,
        List.of(MobEffects.WEAKNESS.value(), MobEffects.POISON.value(), MobEffects.WITHER.value()))
        .under(badEffects);
    private final BoolSetting wornArmour = new BoolSetting("Worn armour",
        "Warns you in chat and with a deep tone when a piece of armour you wear is nearly worn out.", false);
    private final NumberSetting armourLeft = new NumberSetting("Armour left",
        "The share of its durability a piece is down to when the warning comes.", 10, 1, 50, 1, "%")
        .min(0).max(PERCENT)
        .under(wornArmour);
    private final BoolSetting rain = new BoolSetting("Rain",
        "Says in chat when it starts to rain.", false);

    private final Notifier notifier;
    // The level of each effect you had last tick. It outlives a dimension change and the
    // effects the server sends again then are nothing new.
    private final Map<Holder<MobEffect>, Integer> effectLevels = new HashMap<>();
    // The piece in each armour slot that has been warned about.
    private final Map<EquipmentSlot, Item> wornOut = new EnumMap<>(EquipmentSlot.class);
    private final Feed.Reader<Double> pulls = PullBacks.reader();
    private final HurtReport hurts = new HurtReport(false);
    private int pullBackQuiet;
    private int pullBacksHeld;
    private boolean antiCheatTold;
    private boolean raining;

    SelfWarnings(Notifier notifier) {
        this.notifier = notifier;
    }

    // Pull backs and the anti cheat the server seems to run.
    Setting<?>[] serverRows() {
        return new Setting<?>[] {pullBacks, antiCheat};
    }

    // Hits taken and bad effects and worn armour and rain.
    Setting<?>[] warningRows() {
        return new Setting<?>[] {hitsTaken, badEffects, warnEffects, wornArmour, armourLeft, rain};
    }

    void reset() {
        effectLevels.clear();
        forgetServer();
        forgetWorld();
        pulls.sync();
        wornOut.clear();
        raining = MC.level != null && Weather.real(MC.level::isRaining);
    }

    void forgetServer() {
        antiCheatTold = false;
    }

    void forgetWorld() {
        hurts.clear();
        pullBackQuiet = 0;
        pullBacksHeld = 0;
    }

    void hit(EntityDamageEvent event) {
        if (hitsTaken.isOn()) {
            hurts.hit(event);
        }
    }

    void tick() {
        checkEffects();
        checkArmour();
        watchRain();
        for (double distance : pulls.fresh()) {
            onPullBack(distance);
        }
        endPullBackQuiet();
        tellAntiCheat();
        for (String line : hurts.tick()) {
            notifier.say(line);
        }
    }

    // Warns once for each piece as it wears down to the line. A piece mended well past the
    // line can be warned about again. Another piece put on in its place can be too. A portal
    // changes nothing about what you wear and the pieces warned about stay warned about.
    private void checkArmour() {
        if (!wornArmour.isOn()) {
            wornOut.clear();
            return;
        }
        double line = armourLeft.getValue();
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            ItemStack worn = MC.player.getItemBySlot(slot);
            if (!ItemUtil.wornBelow(worn, (line + MENDED_MARGIN) / PERCENT)) {
                wornOut.remove(slot);
            } else if (ItemUtil.wornBelow(worn, line / PERCENT)
                && wornOut.put(slot, worn.getItem()) != worn.getItem()) {
                warning("§7Your §c" + ChatUtil.words(worn.getItem()) + "§7 is down to §c"
                    + Math.round(ItemUtil.durabilityPercent(worn)) + "%§7 of its durability.");
            }
        }
    }

    // The weather the server sends along with a join or a new dimension is nothing that started
    // and neither is rain already falling as the module goes on.
    private void watchRain() {
        boolean now = Weather.real(MC.level::isRaining);
        if (rain.isOn() && now && !raining && MC.player.tickCount >= Notifier.SETTLE_TICKS) {
            notifier.say("§7It has started to rain.");
        }
        raining = now;
    }

    // Warns once when an effect is gained or grows stronger. The first ticks of a new
    // player object only take stock of what the server sends along with it.
    private void checkEffects() {
        Map<Holder<MobEffect>, MobEffectInstance> active = MC.player.getActiveEffectsMap();
        boolean settling = MC.player.tickCount < Notifier.SETTLE_TICKS;
        for (MobEffectInstance effect : active.values()) {
            Integer before = effectLevels.get(effect.getEffect());
            boolean gained = before == null || effect.getAmplifier() > before;
            if (gained && !settling && badEffects.isOn() && warnEffects.contains(effect.getEffect().value())) {
                warn(effect);
            }
        }
        rememberEffects();
    }

    void rememberEffects() {
        effectLevels.clear();
        MC.player.getActiveEffectsMap().forEach((type, effect) -> effectLevels.put(type, effect.getAmplifier()));
    }

    private void warn(MobEffectInstance effect) {
        String time = effect.isInfiniteDuration() ? "" : " §7for §b" + EffectText.timeLeft(effect);
        warning("§7You have §c" + EffectText.name(effect) + time + "§7.");
    }

    // A line about yourself with the deep tone.
    private static void warning(String line) {
        ChatUtil.message(line);
        MC.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, WARNING_PITCH));
    }

    // The server pulled you back this many blocks.
    private void onPullBack(double distance) {
        if (!pullBacks.isOn()) {
            return;
        }
        if (pullBackQuiet > 0) {
            pullBacksHeld++;
            return;
        }
        pullBackQuiet = PULL_BACK_QUIET_TICKS;
        notifier.say("§7The server pulled you back §b" + String.format(Locale.ROOT, "%.1f", distance) + "§7 blocks.");
    }

    // The pull backs held back in the quiet time go out in one line which starts another.
    private void endPullBackQuiet() {
        if (pullBackQuiet == 0 || --pullBackQuiet > 0 || pullBacksHeld == 0) {
            return;
        }
        notifier.say("§7The server pulled you back §b" + Tally.counted(pullBacksHeld, "more time") + "§7.");
        pullBacksHeld = 0;
        pullBackQuiet = PULL_BACK_QUIET_TICKS;
    }

    // Once a join and once more after the module is switched on.
    private void tellAntiCheat() {
        if (antiCheatTold || !antiCheat.isOn() || !AntiCheatGuess.INSTANCE.settled()) {
            return;
        }
        antiCheatTold = true;
        notifier.say(AntiCheatGuess.INSTANCE.found()
            ? "§7The server seems to run " + AntiCheatGuess.INSTANCE.summary() + "§7."
            : "§7No anti cheat showed itself on this server.");
    }
}
