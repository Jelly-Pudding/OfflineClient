package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.World;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The things a case puts on the stage and the questions it asks the server about them. Every
// answer comes from a server command and reads the same on both grounds.
final class Props {

    static final String STILL_MOB = "{NoAI:1b,PersistenceRequired:1b}";
    private static final int POLL_TICKS = 4;
    private static final int HELD_TICKS = 2;
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");

    private Props() {
    }

    // Puts an item in a hotbar slot counted from nought and takes that slot in the hand.
    static void hold(Rig rig, int slot, String item) {
        rig.world().hotbar(slot, item);
        hold(rig, slot);
    }

    static void hold(Rig rig, int slot) {
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(slot));
        rig.ticks(HELD_TICKS);
    }

    // Puts an item in a slot of the backpack counted from nought.
    static void pack(Rig rig, int slot, String item) {
        World world = rig.world();
        world.command("item replace entity " + world.player() + " inventory." + slot + " with " + item);
    }

    static void wear(Rig rig, String slot, String item) {
        World world = rig.world();
        world.command("item replace entity " + world.player() + " " + slot + " with " + item);
    }

    // Stands a mob in the middle of a block. The tags follow the id with no space between.
    static void summon(Rig rig, String mob, BlockPos at, String tags) {
        rig.world().command("summon " + mob + " " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5)
            + " " + tags);
    }

    // How many of an entity the server holds near the stage.
    static int living(Rig rig, String type) {
        return rig.world().count("execute if entity @e[type=" + type + "]");
    }

    // The health of the one mob of its kind on the stage with the fraction cut off.
    static int health(Rig rig, String type) {
        return rig.world().count("data get entity @e[type=" + type + ",limit=1] Health");
    }

    // How many of an item the player carries.
    static int carried(Rig rig, String item) {
        World world = rig.world();
        return world.count("clear " + world.player() + " " + item + " 0");
    }

    // True when the slot of the player holds the item. The slot is named the way commands name it.
    static boolean holds(Rig rig, String slot, String item) {
        World world = rig.world();
        return world.attempt("execute if items entity " + world.player() + " " + slot + " " + item);
    }

    // The three numbers of a spot the way a command takes them.
    static String at(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    static boolean is(Rig rig, BlockPos pos, String block) {
        return rig.world().blockAt(pos).equals("minecraft:" + block);
    }

    // Waits for something only the server can say and asks every few ticks. Hands back the ticks
    // it took.
    static int await(Rig rig, String what, int limitTicks, BooleanSupplier holds) {
        for (int waited = 0; waited <= limitTicks; waited += POLL_TICKS) {
            if (holds.getAsBoolean()) {
                return waited;
            }
            rig.ticks(POLL_TICKS);
        }
        throw new RigFailure("Waited " + limitTicks + " ticks for " + what);
    }

    // Picks the entries of a list setting by their ids. Nothing else stays picked.
    static void pick(Rig rig, String module, String setting, String... ids) {
        Module owner = rig.client().module(module);
        Set<Identifier> picked = new LinkedHashSet<>();
        for (String id : ids) {
            picked.add(Identifier.parse(id));
        }
        rig.act(mc -> ((RegistryListSetting<?>) owner.getSetting(setting)).setValue(picked));
    }

    // Picks the first option of a choice list whose name holds the words whatever their case.
    static void choose(Rig rig, String module, String setting, String words) {
        chooseSome(rig, module, setting, words, 1);
    }

    // Picks every such option.
    static void chooseAll(Rig rig, String module, String setting, String words) {
        chooseSome(rig, module, setting, words, Integer.MAX_VALUE);
    }

    private static void chooseSome(Rig rig, String module, String setting, String words, int most) {
        Module owner = rig.client().module(module);
        rig.act(mc -> {
            ChoiceListSetting list = (ChoiceListSetting) owner.getSetting(setting);
            List<String> like = list.options().stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).contains(words.toLowerCase(Locale.ROOT)))
                .limit(most).toList();
            if (like.isEmpty()) {
                throw new RigFailure(module + " offers nothing like " + words + " under " + setting);
            }
            list.addAll(like);
        });
    }

    // Types words into a text setting. They may hold spaces.
    static void text(Rig rig, String module, String setting, String words) {
        Module owner = rig.client().module(module);
        rig.act(mc -> ((TextSetting) owner.getSetting(setting)).setValue(words));
    }

    // The number a module writes beside its name in the module list. Nought when it writes none.
    static int tally(Module module) {
        Matcher number = NUMBER.matcher(String.valueOf(module.getSuffix()));
        return number.find() ? Integer.parseInt(number.group()) : 0;
    }

    // True when the game has a stack like that anywhere on the player.
    static boolean has(Inventory pack, Predicate<ItemStack> wanted) {
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            if (wanted.test(pack.getItem(slot))) {
                return true;
            }
        }
        return false;
    }
}
