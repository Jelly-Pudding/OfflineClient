package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.Feeding;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;

public final class DropCommand extends Command {

    private static final String ALL = "all";
    private static final String OFFHAND = "offhand";
    private static final String HOTBAR = "hotbar";
    private static final String INVENTORY = "inventory";
    private static final String EVERYTHING = "everything";
    private static final String ARMOUR = "armour";
    private static final String STOP = "stop";
    private static final String SLOW = "slow";
    private static final List<String> WORDS = List.of(ALL, OFFHAND, HOTBAR, INVENTORY, EVERYTHING, ARMOUR, STOP);

    private static final int SLOW_GAP = 5;

    // Paper drops a player who throws over twenty single items with the throw key in one of its
    // ticks. A held key manages about two a tick and a macro may ask for no more.
    private static final int SINGLES_A_TICK = 2;

    private static final List<Integer> ARMOUR_SLOTS = range(InventoryUtil.ARMOR_START, InventoryUtil.MAIN_START);
    private static final List<Integer> BACKPACK_SLOTS = range(InventoryUtil.MAIN_START, InventoryUtil.HOTBAR_START);
    private static final List<Integer> HOTBAR_SLOTS = range(InventoryUtil.HOTBAR_START, InventoryUtil.OFFHAND_SLOT);
    private static final List<Integer> OFFHAND_SLOTS = List.of(InventoryUtil.OFFHAND_SLOT);

    private record Throw(int slot, Item item, int count) {
    }

    private static final class Plan {

        private final List<Throw> stacks = new ArrayList<>();
        private int items;
        private int kept;
        private int stuck;
    }

    private static SlowDrop running;
    private static int singles;
    private static int singlesTick;

    public DropCommand() {
        super("drop", "Throws away what you hold or a whole part of what you carry or a counted item. Slow"
                + " spreads a large throw over time.",
            "drop [all|offhand|hotbar|inventory|everything|armour|stop] [slow] or drop <item> [count] [slow]");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        boolean slow = args.length > 0 && args[args.length - 1].equalsIgnoreCase(SLOW);
        String[] words = slow ? Arrays.copyOf(args, args.length - 1) : args;
        String first = words.length == 0 ? "" : words[0].toLowerCase(Locale.ROOT);
        if (words.length == 1 && first.equals(STOP) && !slow) {
            stop();
        } else if (words.length == 0 || words.length == 1 && first.equals(ALL)) {
            if (slow) {
                usage();
            } else {
                dropHeld(player, words.length == 1);
            }
        } else {
            dropPart(player, words, first, slow);
        }
    }

    private static void dropHeld(LocalPlayer player, boolean whole) {
        String refused = throwHeld(player, whole);
        if (refused != null) {
            ChatUtil.error(refused);
        } else {
            ChatUtil.message(whole ? "§7Dropped the stack." : "§7Dropped one.");
        }
    }

    static String throwHeld(LocalPlayer player, boolean whole) {
        if (player.getMainHandItem().isEmpty()) {
            return "Your hand is empty.";
        }
        if (Modules.keepsFromThrow(player.getMainHandItem())) {
            return "NoInteract keeps what you hold from being thrown.";
        }
        if (!whole && !singleFits()) {
            return "Only " + SINGLES_A_TICK + " single throws fit in a tick. Name the item and a count instead.";
        }
        OfflineClient.MC.gameMode.dropItem(player, whole);
        return null;
    }

    private static boolean singleFits() {
        if (singlesTick != MoveGate.tick()) {
            singlesTick = MoveGate.tick();
            singles = 0;
        }
        return singles++ < SINGLES_A_TICK;
    }

    private void dropPart(LocalPlayer player, String[] words, String first, boolean slow) {
        Item item = null;
        int count = Integer.MAX_VALUE;
        List<Integer> slots = slotsOf(first);
        if (slots != null && words.length > 1 || words.length > 2) {
            usage();
            return;
        }
        if (slots == null) {
            item = CommandManager.registryEntry(BuiltInRegistries.ITEM, first);
            if (item == null) {
                ChatUtil.error("There is no item called " + words[0] + ".");
                return;
            }
            if (words.length == 2) {
                OptionalInt typed = wholeNumber(words[1]);
                if (typed.isEmpty()) {
                    return;
                }
                if (typed.getAsInt() < 1) {
                    ChatUtil.error("The count has to be one or more.");
                    return;
                }
                count = typed.getAsInt();
            }
            slots = join(BACKPACK_SLOTS, HOTBAR_SLOTS, OFFHAND_SLOTS);
        }
        if (player.isSpectator()) {
            ChatUtil.error("A spectator carries nothing to throw.");
            return;
        }
        if (Modules.packing()) {
            ChatUtil.error("ShulkerPacker is moving your stacks. Switch it off first.");
            return;
        }
        if (!InventoryUtil.cursorFree()) {
            ChatUtil.error("Close the container you have open and put down what the cursor holds first.");
            return;
        }
        Plan plan = plan(player, slots, item, count);
        String left = left(plan);
        if (plan.stacks.isEmpty()) {
            ChatUtil.message("§7There is nothing there to throw." + left);
            return;
        }
        String what = item == null ? Tally.counted(plan.stacks.size(), "stack")
            : plan.items + " " + ChatUtil.words(item);
        stopQuietly();
        if (!slow) {
            plan.stacks.forEach(DropCommand::make);
            ChatUtil.message("§7Threw §b" + what + "§7." + left);
            return;
        }
        running = new SlowDrop(player, plan.stacks);
        OfflineClient.INSTANCE.getEventBus().register(running);
        ChatUtil.message("§7Throwing §b" + what + "§7 with " + SLOW_GAP + " ticks between stacks. §f"
            + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "drop " + STOP + "§7 ends it." + left);
    }

    private static List<Integer> slotsOf(String word) {
        return switch (word) {
            case OFFHAND -> OFFHAND_SLOTS;
            case HOTBAR -> HOTBAR_SLOTS;
            case INVENTORY -> BACKPACK_SLOTS;
            case ARMOUR, "armor" -> ARMOUR_SLOTS;
            case EVERYTHING -> join(BACKPACK_SLOTS, HOTBAR_SLOTS, ARMOUR_SLOTS, OFFHAND_SLOTS);
            default -> null;
        };
    }

    private static Plan plan(LocalPlayer player, List<Integer> slots, Item item, int count) {
        Plan plan = new Plan();
        for (int slot : slots) {
            ItemStack stack = player.inventoryMenu.getSlot(slot).getItem();
            if (stack.isEmpty() || item != null && !stack.is(item) || plan.items >= count) {
                continue;
            }
            if (Modules.keepsFromThrow(stack)) {
                plan.kept++;
            } else if (ARMOUR_SLOTS.contains(slot) && InventoryUtil.stuckOn(stack)) {
                plan.stuck++;
            } else {
                int thrown = Math.min(stack.getCount(), count - plan.items);
                plan.stacks.add(new Throw(slot, stack.getItem(), thrown));
                plan.items += thrown;
            }
        }
        return plan;
    }

    private static String left(Plan plan) {
        String left = "";
        if (plan.kept > 0) {
            left += " NoInteract keeps §f" + Tally.counted(plan.kept, "stack") + "§7.";
        }
        if (plan.stuck > 0) {
            left += " §f" + Tally.counted(plan.stuck, "piece") + "§7 of armour cannot come off.";
        }
        return left;
    }

    private static void make(Throw planned) {
        LocalPlayer player = OfflineClient.MC.player;
        ItemStack stack = player.inventoryMenu.getSlot(planned.slot()).getItem();
        if (stack.isEmpty() || !stack.is(planned.item()) || Modules.keepsFromThrow(stack)) {
            return;
        }
        if (planned.count() >= stack.getCount()) {
            InventoryUtil.throwStack(planned.slot());
            return;
        }
        // Button nought throws one item a click.
        for (int i = 0; i < planned.count(); i++) {
            MenuClicks.click(player.inventoryMenu, planned.slot(), 0, ContainerInput.THROW);
        }
    }

    private static void stop() {
        if (running == null) {
            ChatUtil.message("§7No slow drop is running.");
            return;
        }
        stopQuietly();
        ChatUtil.message("§7Stopped the slow drop.");
    }

    private static void stopQuietly() {
        if (running != null) {
            OfflineClient.INSTANCE.getEventBus().unregister(running);
            running = null;
        }
    }

    private static List<Integer> range(int from, int to) {
        return IntStream.range(from, to).boxed().toList();
    }

    @SafeVarargs
    private static List<Integer> join(List<Integer>... parts) {
        List<Integer> all = new ArrayList<>();
        for (List<Integer> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private static final class SlowDrop {

        private final LocalPlayer player;
        private final Deque<Throw> left;
        private int wait;

        private SlowDrop(LocalPlayer player, List<Throw> plan) {
            this.player = player;
            left = new ArrayDeque<>(plan);
        }

        @Subscribe
        private void onTick(TickEvent event) {
            if (OfflineClient.MC.player != player || Modules.dormant()) {
                stopQuietly();
                return;
            }
            if (Modules.packing()) {
                stopQuietly();
                ClientLines.inChat(() -> ChatUtil.error("The slow drop stopped because ShulkerPacker is moving your stacks."));
                return;
            }
            if (wait-- > 0 || !InventoryUtil.cursorFree() || Feeding.busy(null)) {
                return;
            }
            make(left.poll());
            wait = SLOW_GAP;
            if (left.isEmpty()) {
                stopQuietly();
                ClientLines.inChat(() -> ChatUtil.message("§7The slow drop is done."));
            }
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index > 1) {
            return CommandManager.filter(current, List.of(SLOW));
        }
        Set<String> options = new LinkedHashSet<>(WORDS);
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null) {
            for (int slot : join(BACKPACK_SLOTS, HOTBAR_SLOTS, OFFHAND_SLOTS)) {
                ItemStack stack = player.inventoryMenu.getSlot(slot).getItem();
                if (!stack.isEmpty()) {
                    options.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
                }
            }
        }
        return CommandManager.filter(current, new ArrayList<>(options));
    }
}
