package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.friend.FriendManager;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TextLines;
import com.jellypudding.offlineclient.util.UseHold;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;
import java.util.Locale;

public final class MiddleClickExtra extends Module {

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
        "Says in chat who was added or removed.", true).under(mode, Mode.ADD_FRIEND);
    private final BoolSetting sendMessage = new BoolSetting("Send message",
        "Sends a chat message to the player you add.", false).under(mode, Mode.ADD_FRIEND);
    private final TextSetting message = new TextSetting("Message",
        "The message to send. The word {player} becomes their name." + TextLines.LIVE_WORDS,
        "/msg {player} I have added you as a friend.").under(sendMessage);
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
        "Leaves pick block alone in creative.", true);
    private final BoolSetting rightPlace = new BoolSetting("Right click places",
        "A right click on a block whilst a listed item is in your main hand places a block from your hotbar"
            + " there and goes back to the item. An off hand holding anything but a totem keeps its right click.",
        false);
    private final RegistryListSetting<Item> placeWith = new RegistryListSetting<>("Place with",
        "Items in your main hand that turn a right click into a placement. Click to pick them.",
        BuiltInRegistries.ITEM, List.of(Items.WOODEN_SWORD, Items.COPPER_SWORD, Items.STONE_SWORD,
            Items.GOLDEN_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD,
            Items.WOODEN_PICKAXE, Items.COPPER_PICKAXE, Items.STONE_PICKAXE, Items.GOLDEN_PICKAXE,
            Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE))
        .under(rightPlace);
    private final RegistryListSetting<Block> placeBlocks = new RegistryListSetting<>("Place blocks",
        "Blocks to place. The one highest on the list that is in your hotbar goes down. Click to pick them.",
        BuiltInRegistries.BLOCK, List.of(Blocks.OBSIDIAN))
        .under(rightPlace);
    private final BoolSetting warnNoBlock = new BoolSetting("Warn when no block",
        "Says in chat when none of those blocks is in your hotbar.", true)
        .under(rightPlace);

    private final HotbarLoan loan = new HotbarLoan();
    private final SlotSwap slots = new SlotSwap();
    private final UseHold hold = new UseHold();
    private boolean using;
    // A held use key repeats the right click. The missing block is told once until the key goes up.
    private boolean toldNoBlock;

    public MiddleClickExtra() {
        super("MiddleClickExtra", "Does something useful on a middle click and can place blocks on a right click.",
            Category.PLAYER);
        addSettings(mode, notify, sendMessage, message, quickSwap, swapBack, warnMissing,
            skipCreative, rightPlace, placeWith, placeBlocks, warnNoBlock);
        searchTags("friend", "add friend", "middle click pearl", "middle click bow", "right click place");
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
            ChatPace.PLAYER.say(SentLine.of(message.getValue(), "player", name));
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
            InputUtil.holdUse();
            return;
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        loan.giveBack(swapBack.isOn());
    }

    // After NoInteract has had its say about the click. The block goes down as if it had been in
    // hand all along and the held slot comes back before anything else is sent.
    @Subscribe(priority = Subscribe.LAST)
    private void onRightClick(RightClickEvent event) {
        if (!rightPlace.isOn() || !inGame() || using) {
            return;
        }
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit == null || !placeWith.contains(mc.player.getMainHandItem().getItem()) || offHandUses()
            || mc.player.isUsingItem() || Hands.slotsTaken()) {
            return;
        }
        int slot = BlockUtil.findRankedBlockSlot(placeBlocks.getValue(), block -> true);
        if (slot == -1) {
            if (warnNoBlock.isOn() && !toldNoBlock) {
                ChatUtil.error("No block to place in your hotbar.");
            }
            toldNoBlock = true;
            return;
        }
        event.cancel();
        mc.rightClickDelay = InputUtil.USE_DELAY;
        slots.select(slot);
        if (mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            SwingMode.swingUse(InteractionHand.MAIN_HAND);
        }
        slots.restore();
    }

    // The game tries the off hand after the main hand. A totem there has no use.
    private boolean offHandUses() {
        ItemStack off = mc.player.getOffhandItem();
        return !off.isEmpty() && !off.is(Items.TOTEM_OF_UNDYING);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!mc.options.keyUse.isDown()) {
            toldNoBlock = false;
        }
        if (!using) {
            return;
        }
        // A slot picked by hand cancels the use.
        if (!loan.stillMine()) {
            finish(false);
            return;
        }
        if (bowDrawn()) {
            InputUtil.releaseUse();
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

    private void finish(boolean completed) {
        using = false;
        hold.release();
        loan.giveBack(completed && swapBack.isOn());
    }
}
