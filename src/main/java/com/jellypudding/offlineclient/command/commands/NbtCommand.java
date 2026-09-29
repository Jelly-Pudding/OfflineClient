package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.serialization.Codec;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

// Components are shown one to a line. The clipboard gets the give command that makes the item.
public final class NbtCommand extends Command {

    private static final List<String> MODES = List.of("full", "save");

    // A component written this way was taken off the item rather than changed.
    private static final String REMOVED = "!";

    public NbtCommand() {
        super("nbt", "Shows the component data of the item you hold and copies a give command for it.",
            "nbt [full|save]", "viewnbt", "components");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            ChatUtil.error("Your hand is empty.");
            return;
        }
        String mode = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1 || (!mode.isEmpty() && !MODES.contains(mode))) {
            usage();
            return;
        }
        CompoundTag changed = encode(player, DataComponentPatch.CODEC, stack.getComponentsPatch());
        String give = giveCommand(stack, changed);
        if (mode.equals("save")) {
            save(stack, give);
            return;
        }
        OfflineClient.MC.keyboardHandler.setClipboard(give);
        String name = stack.getHoverName().getString();
        if (mode.equals("full")) {
            CompoundTag all = encode(player, DataComponentMap.CODEC, stack.getComponents());
            ChatUtil.message("§b" + name + " §7has §b" + Tally.counted(all.size(), "component")
                + "§7. Its give command is on the clipboard.");
            show(all);
            return;
        }
        if (changed.isEmpty()) {
            ChatUtil.message("§b" + name + " §7is the same as a fresh one. Its give command is on the clipboard.");
            return;
        }
        ChatUtil.message("§b" + name + " §7differs from a fresh one in §b" + Tally.counted(changed.size(), "component")
            + "§7. Its give command is on the clipboard.");
        show(changed);
    }

    private static <T> CompoundTag encode(LocalPlayer player, Codec<T> codec, T value) {
        Tag tag = codec.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), value)
            .resultOrPartial(problem -> OfflineClient.LOG.warn("Part of an item could not be written out. {}", problem))
            .orElse(null);
        return tag instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    // The item id with its changed components in brackets and the count after them.
    private static String giveCommand(ItemStack stack, CompoundTag changed) {
        StringBuilder command = new StringBuilder("/give @s ").append(stack.typeHolder().getRegisteredName());
        if (!changed.isEmpty()) {
            StringJoiner components = new StringJoiner(",", "[", "]");
            for (String key : changed.keySet()) {
                components.add(key.startsWith(REMOVED) ? key : key + "=" + changed.get(key));
            }
            command.append(components);
        }
        if (stack.getCount() > 1) {
            command.append(' ').append(stack.getCount());
        }
        return command.toString();
    }

    // Each line copies its own component when clicked.
    private static void show(CompoundTag components) {
        for (String key : components.keySet().stream().sorted().toList()) {
            Tag value = components.get(key);
            if (key.startsWith(REMOVED)) {
                ChatUtil.message("§b" + key.substring(REMOVED.length()) + " §7removed");
                continue;
            }
            String text = key + "=" + value;
            Component shown = text.length() > ChatUtil.LONGEST_SHOWN
                ? Component.literal("§7" + Tally.counted(text.length(), "character"))
                : NbtUtils.toPrettyComponent(value);
            ChatUtil.component(ChatUtil.copyOnClick(Component.literal("§b" + key + " ").append(shown), text));
        }
    }

    private static void save(ItemStack stack, String give) {
        Path file = DataFiles.path("nbt.txt");
        String line = DataFiles.timestamp() + " " + stack.getHoverName().getString() + " " + give;
        if (!DataFiles.appendLines(file, List.of(line))) {
            ChatUtil.error("The item could not be saved. The game log says why.");
            return;
        }
        ChatUtil.component(Component.literal("§7Saved the give command to ").append(ChatUtil.fileLink(file)));
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, MODES) : List.of();
    }
}
