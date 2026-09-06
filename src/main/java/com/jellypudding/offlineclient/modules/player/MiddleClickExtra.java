package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.UseHold;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import java.util.Locale;

public final class MiddleClickExtra extends Module {

    // The item a mode reaches for. Held modes keep the use key down until the use ends.
    public enum Mode {
        ADD_FRIEND(null, false),
        PEARL(Items.ENDER_PEARL, false),
        EXPERIENCE(Items.EXPERIENCE_BOTTLE, false),
        ROCKET(Items.FIREWORK_ROCKET, false),
        WIND_CHARGE(Items.WIND_CHARGE, false),
        BOW(Items.BOW, true),
        GOLDEN_APPLE(Items.GOLDEN_APPLE, true),
        ENCHANTED_APPLE(Items.ENCHANTED_GOLDEN_APPLE, true),
        CHORUS_FRUIT(Items.CHORUS_FRUIT, true);

        private final Item item;
        private final boolean held;

        Mode(Item item, boolean held) {
            this.item = item;
            this.held = held;
        }
    }

    private static final String NAME_TOKEN = "%player";

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "What a middle click does.", Mode.ADD_FRIEND)
        .describe(Mode.ADD_FRIEND, "Toggles the player under the crosshair as a friend.")
        .describe(Mode.PEARL, "Throws an ender pearl.")
        .describe(Mode.EXPERIENCE, "Throws a bottle of enchanting.")
        .describe(Mode.ROCKET, "Uses a firework rocket.")
        .describe(Mode.WIND_CHARGE, "Throws a wind charge.")
        .describe(Mode.BOW, "Draws a bow fully and fires it.")
        .describe(Mode.GOLDEN_APPLE, "Eats a golden apple.")
        .describe(Mode.ENCHANTED_APPLE, "Eats an enchanted golden apple.")
        .describe(Mode.CHORUS_FRUIT, "Eats a chorus fruit.");
    private final BoolSetting notify = new BoolSetting("Notify",
        "Say in chat who was added or removed.", true).under(mode, Mode.ADD_FRIEND);
    private final BoolSetting sendMessage = new BoolSetting("Send message",
        "Sends a chat message to the player you add.", false).under(mode, Mode.ADD_FRIEND);
    private final TextSetting message = new TextSetting("Message",
        "The message to send. The word %player becomes their name.",
        "/msg %player I have added you as a friend.").under(sendMessage);
    private final BoolSetting quickSwap = new BoolSetting("Quick swap",
        "Also reaches into the main inventory by swapping the item into the hotbar and back.",
        false).under(mode, () -> !mode.is(Mode.ADD_FRIEND));
    private final BoolSetting swapBack = new BoolSetting("Swap back",
        "Returns to the slot you had once the item is used.", false)
        .under(mode, () -> !mode.is(Mode.ADD_FRIEND));
    private final BoolSetting warnMissing = new BoolSetting("Warn when missing",
        "Says in chat when the item cannot be found.", true)
        .under(mode, () -> !mode.is(Mode.ADD_FRIEND));
    private final BoolSetting skipCreative = new BoolSetting("Skip in creative",
        "Does nothing in creative so pick block works as normal.", true);

    private final HotbarLoan loan = new HotbarLoan();
    private final UseHold hold = new UseHold();
    private boolean using;

    public MiddleClickExtra() {
        super("MiddleClickExtra", "Does something useful on a middle click.", Category.PLAYER);
        addSettings(mode, notify, sendMessage, message, quickSwap, swapBack, warnMissing,
            skipCreative);
        searchTags("friend", "add friend", "middle click pearl", "middle click bow");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onDisable() {
        if (using) {
            finish(false);
        }
    }

    // Called from the mouse mixin on every middle click press.
    public void onMiddleClick() {
        if (!isEnabled() || !inGame() || mc.gui.screen() != null || using) {
            return;
        }
        if (skipCreative.isOn() && mc.player.gameMode() == GameType.CREATIVE) {
            return;
        }
        if (mode.is(Mode.ADD_FRIEND)) {
            toggleFriend();
        } else {
            useItem(mode.getValue());
        }
    }

    private void toggleFriend() {
        if (!(mc.crosshairPickEntity instanceof Player player) || player == mc.player) {
            return;
        }
        String name = player.getGameProfile().name();
        FriendManager friends = OfflineClient.INSTANCE.getFriendManager();
        boolean added = !friends.isFriend(name);
        if (added) {
            friends.add(name);
        } else {
            friends.remove(name);
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        if (notify.isOn()) {
            ChatUtil.message("§b" + name + (added
                ? " §7is now a friend." : " §7is no longer a friend."));
        }
        if (added && sendMessage.isOn() && !message.isBlank()) {
            send(message.getValue().replace(NAME_TOKEN, name));
        }
    }

    private void send(String text) {
        if (text.startsWith("/")) {
            mc.getConnection().sendCommand(text.substring(1));
        } else {
            mc.getConnection().sendChat(text);
        }
    }

    private void useItem(Mode chosen) {
        int limit = quickSwap.isOn() ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE;
        int slot = InventoryUtil.findSlot(chosen.item, limit);
        if (slot == -1 || !loan.select(slot)) {
            if (warnMissing.isOn()) {
                ChatUtil.error("No " + chosen.item.getName(chosen.item.getDefaultInstance())
                    .getString().toLowerCase(Locale.ROOT) + " to use.");
            }
            return;
        }
        if (chosen.held) {
            using = true;
            hold.begin();
            InputUtil.hold(mc.options.keyUse);
            return;
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        loan.giveBack(swapBack.isOn());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!using) {
            return;
        }
        // A slot picked by hand cancels the use.
        if (!loan.stillMine()) {
            finish(false);
            return;
        }
        if (bowDrawn()) {
            InputUtil.release(mc.options.keyUse);
            return;
        }
        if (!hold.tick()) {
            finish(true);
        }
    }

    private boolean bowDrawn() {
        return mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof BowItem
            && BowItem.getPowerForTime(mc.player.getTicksUsingItem()) >= 1;
    }

    // Lets the key go and puts the slots back. A cancelled use keeps the slot the player chose.
    private void finish(boolean completed) {
        using = false;
        hold.release();
        loan.giveBack(completed && swapBack.isOn());
    }
}
