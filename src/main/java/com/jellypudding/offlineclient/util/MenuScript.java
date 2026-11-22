package com.jellypudding.offlineclient.util;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// A list of clicks for a container menu the way a player writes it with one step to a row.
// A step is a click word and a slot such as shift 12 or a row of slots such as shift 0 to 26.
// Swap 12 with 3 trades a slot with a hotbar key and wait 10 waits and close shuts the menu.
public final class MenuScript {

    public sealed interface Action permits Click, Wait, Close {
    }

    public record Click(int slot, int button, ContainerInput kind) implements Action {
    }

    public record Wait(int ticks) implements Action {
    }

    public record Close() implements Action {
    }

    public static final String HELP = "Write left or right or shift or drop or dropall or double or middle"
        + " and then a slot number such as shift 12. Two numbers with the word to between them click every"
        + " slot from the first to the last such as shift 0 to 26. The word outside in place of a number"
        + " clicks beside the menu. Swap 12 with 3 trades a slot with what hotbar key 3 holds and offhand"
        + " works in place of the key. Wait 10 waits that many ticks and close shuts the menu.";

    // The click each word makes. Button one of a throw drops the whole stack.
    private enum Press {
        LEFT(ContainerInput.PICKUP, 0),
        RIGHT(ContainerInput.PICKUP, 1),
        SHIFT(ContainerInput.QUICK_MOVE, 0),
        DROP(ContainerInput.THROW, 0),
        DROPALL(ContainerInput.THROW, 1),
        DOUBLE(ContainerInput.PICKUP_ALL, 0),
        MIDDLE(ContainerInput.CLONE, 2);

        private final ContainerInput kind;
        private final int button;

        Press(ContainerInput kind, int button) {
            this.kind = kind;
            this.button = button;
        }

        // Only a plain click does anything beside the menu. It drops what the cursor holds.
        private boolean worksOutside() {
            return kind == ContainerInput.PICKUP;
        }
    }

    // A click runs from one slot to another and the two are the same for a single slot.
    private record Step(Action fixed, Press press, int from, int to) {
    }

    private static final String WAIT = "wait";
    private static final String CLOSE = "close";
    private static final String SWAP = "swap";
    private static final String WITH = "with";
    private static final String TO = "to";
    private static final String OUTSIDE = "outside";
    private static final String OFFHAND = "offhand";
    private static final String CLICK = "click";

    // No slot number or wait is longer. A longer run of digits is a slip of the hand.
    private static final int MOST_DIGITS = 6;

    private final List<Step> steps;
    private final String unread;

    private MenuScript(List<Step> steps, String unread) {
        this.steps = steps;
        this.unread = unread;
    }

    // A blank row is passed over. The first row that makes no sense ends the reading and is kept to
    // show the player.
    public static MenuScript parse(List<String> rows) {
        List<Step> steps = new ArrayList<>();
        for (String row : rows) {
            String text = row.strip().toLowerCase(Locale.ROOT);
            if (text.isEmpty()) {
                continue;
            }
            Step step = read(text.split("\\s+"));
            if (step == null) {
                return new MenuScript(List.of(), row.strip());
            }
            steps.add(step);
        }
        return new MenuScript(List.copyOf(steps), null);
    }

    public String unread() {
        return unread;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    // A row of slots is cut down to the slots the menu has and a slot it lacks is left out. The
    // game crashes on a click past the last slot.
    public List<Action> actionsFor(int slots) {
        List<Action> actions = new ArrayList<>();
        for (Step step : steps) {
            if (step.fixed() instanceof Click swap) {
                if (swap.slot() < slots) {
                    actions.add(swap);
                }
            } else if (step.fixed() != null) {
                actions.add(step.fixed());
            } else if (step.from() == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE) {
                actions.add(click(step, step.from()));
            } else if (slots > 0 && Math.min(step.from(), step.to()) < slots) {
                int first = Math.min(step.from(), slots - 1);
                int last = Math.min(step.to(), slots - 1);
                int stride = first <= last ? 1 : -1;
                for (int slot = first; slot != last + stride; slot += stride) {
                    actions.add(click(step, slot));
                }
            }
        }
        return actions;
    }

    private static Click click(Step step, int slot) {
        return new Click(slot, step.press().button, step.press().kind);
    }

    private static Step read(String[] words) {
        return switch (words[0]) {
            case WAIT -> words.length == 2 && number(words[1]) >= 0
                ? new Step(new Wait(number(words[1])), null, 0, 0) : null;
            case CLOSE -> words.length == 1 ? new Step(new Close(), null, 0, 0) : null;
            case SWAP -> swap(words);
            default -> press(words);
        };
    }

    // Swap 12 with 3 or the same without the word in the middle.
    private static Step swap(String[] words) {
        boolean wordy = words.length == 4 && words[2].equals(WITH);
        if (!wordy && words.length != 3) {
            return null;
        }
        int slot = number(words[1]);
        String key = words[words.length - 1];
        int button = key.equals(OFFHAND) ? Inventory.SLOT_OFFHAND : number(key) - 1;
        boolean reaches = button == Inventory.SLOT_OFFHAND || Inventory.isHotbarSlot(button);
        if (slot < 0 || !reaches) {
            return null;
        }
        return new Step(new Click(slot, button, ContainerInput.SWAP), null, 0, 0);
    }

    private static Step press(String[] words) {
        Press press = pressNamed(words[0]);
        if (press == null || words.length != 2 && !(words.length == 4 && words[2].equals(TO))) {
            return null;
        }
        if (words[1].equals(OUTSIDE)) {
            int outside = AbstractContainerMenu.SLOT_CLICKED_OUTSIDE;
            return words.length == 2 && press.worksOutside() ? new Step(null, press, outside, outside) : null;
        }
        int from = number(words[1]);
        int to = words.length == 4 ? number(words[3]) : from;
        return from < 0 || to < 0 ? null : new Step(null, press, from, to);
    }

    private static Press pressNamed(String word) {
        if (word.equals(CLICK)) {
            return Press.LEFT;
        }
        for (Press press : Press.values()) {
            if (press.name().toLowerCase(Locale.ROOT).equals(word)) {
                return press;
            }
        }
        return null;
    }

    private static int number(String word) {
        if (word.isEmpty() || word.length() > MOST_DIGITS || !word.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        return Integer.parseInt(word);
    }
}
