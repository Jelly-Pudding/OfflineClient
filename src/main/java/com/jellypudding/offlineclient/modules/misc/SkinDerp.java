package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// Sends the server skin layers and a main hand of its own whilst your options stay as they are.
// The server shows everyone what it was sent. Switching off sends your options again.
public final class SkinDerp extends Module {

    public enum Pattern { RANDOM, BLINK, PEEL, CHASE }

    private final EnumSetting<Pattern> pattern = new EnumSetting<>("Pattern", "How the layers change.", Pattern.RANDOM)
        .describe(Pattern.RANDOM, "Flips the layers at random.")
        .describe(Pattern.BLINK, "Takes every layer off and puts them all back together.")
        .describe(Pattern.PEEL, "Takes the layers off one by one and puts them back.")
        .describe(Pattern.CHASE, "Shows one layer at a time in turn.");
    private final NumberSetting chance = new NumberSetting("Chance",
        "How likely each tick is to flip the layers.", 25, 1, 100, 1, "%").min(1).max(100)
        .under(pattern, Pattern.RANDOM);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between changes.", 4, 1, 40, 1, " ticks").min(1)
        .under(pattern, Pattern.BLINK, Pattern.PEEL, Pattern.CHASE);
    private final BoolSetting keepCape = new BoolSetting("Keep cape",
        "Leaves your cape showing.", false);
    private final BoolSetting keepHat = new BoolSetting("Keep hat",
        "Leaves your hat layer showing.", false);
    private final BoolSetting swapHands = new BoolSetting("Swap hands",
        "Swaps your main hand from side to side for everyone to see.", false);

    // The layers last sent as one mask and the hand last sent.
    private int sent;
    private HumanoidArm hand;
    private int step;
    private int waited;

    public SkinDerp() {
        super("SkinDerp", "Makes your skin layers blink on and off for everyone to see.",
            Category.MISC);
        addSettings(pattern, chance, delay, keepCape, keepHat, swapHands);
        searchTags("skin blink", "spooky skin", "troll", "swap hands");
    }

    @Override
    protected void onEnable() {
        ClientInformation own = mc.options.buildPlayerInformation();
        sent = own.modelCustomisation();
        hand = own.mainHand();
        step = 0;
        waited = 0;
    }

    @Override
    protected void onDisable() {
        mc.options.broadcastOptions();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (pattern.is(Pattern.RANDOM)) {
            if (ThreadLocalRandom.current().nextInt(100) >= chance.getInt()) {
                return;
            }
        } else if (++waited < delay.getInt()) {
            return;
        }
        waited = 0;
        step++;
        List<PlayerModelPart> moving = moving();
        int kept = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            if (!moving.contains(part)) {
                kept |= part.getMask();
            }
        }
        sent = kept | shown(moving);
        hand = swapHands.isOn() ? hand.getOpposite() : mc.options.mainHand().get();
        ClientInformation own = mc.options.buildPlayerInformation();
        mc.getConnection().broadcastClientInformation(new ClientInformation(own.language(), own.viewDistance(),
            own.chatVisibility(), own.chatColors(), sent, hand, own.textFilteringEnabled(), own.allowsListing(),
            own.particleStatus()));
    }

    // Every layer the pattern changes. A kept layer always shows.
    private List<PlayerModelPart> moving() {
        List<PlayerModelPart> moving = new ArrayList<>(List.of(PlayerModelPart.values()));
        if (keepCape.isOn()) {
            moving.remove(PlayerModelPart.CAPE);
        }
        if (keepHat.isOn()) {
            moving.remove(PlayerModelPart.HAT);
        }
        return moving;
    }

    // The moving layers that show at this step as one mask.
    private int shown(List<PlayerModelPart> moving) {
        int count = moving.size();
        int all = mask(moving, 0, count);
        return switch (pattern.getValue()) {
            case RANDOM -> ~sent & all;
            case BLINK -> step % 2 == 0 ? all : 0;
            case PEEL -> {
                // One more layer comes off each step until none is left. Then they go back on in turn.
                int at = step % (2 * count);
                int off = at < count ? at + 1 : 2 * count - 1 - at;
                yield mask(moving, off, count);
            }
            case CHASE -> mask(moving, step % count, step % count + 1);
        };
    }

    private static int mask(List<PlayerModelPart> parts, int from, int to) {
        int mask = 0;
        for (PlayerModelPart part : parts.subList(from, to)) {
            mask |= part.getMask();
        }
        return mask;
    }
}
