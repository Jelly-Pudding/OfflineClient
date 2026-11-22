package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.serialization.Codec;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

public final class NbtCommand extends Command {

    private static final String FULL = "full";
    private static final String SAVE = "save";
    private static final String ENTITY = "entity";
    private static final String BLOCK = "block";
    private static final List<String> MODES = List.of(FULL, SAVE, ENTITY, BLOCK);

    // A component written this way was taken off the item rather than changed.
    private static final String REMOVED = "!";

    // A copy summoned from the command stands where the command is run under an id of its own.
    private static final List<String> OWN_TO_ONE_ENTITY = List.of("Pos", "UUID");

    public NbtCommand() {
        super("nbt", "Shows the data of the item you hold or of the entity or block you point at and copies a"
                + " command that makes it.",
            "nbt [full|save|entity|block]", "viewnbt", "components", "data");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        String mode = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1 || (!mode.isEmpty() && !MODES.contains(mode))) {
            usage();
            return;
        }
        switch (mode) {
            case ENTITY -> showEntity(player);
            case BLOCK -> showBlock(player);
            default -> showItem(player, mode);
        }
    }

    private static void showItem(LocalPlayer player, String mode) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            ChatUtil.error("Your hand is empty.");
            return;
        }
        CompoundTag changed = encode(player, DataComponentPatch.CODEC, stack.getComponentsPatch());
        String give = giveCommand(stack, changed);
        if (mode.equals(SAVE)) {
            save(stack, give);
            return;
        }
        OfflineClient.MC.keyboardHandler.setClipboard(give);
        String name = stack.getHoverName().getString();
        if (mode.equals(FULL)) {
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

    private static void showEntity(LocalPlayer player) {
        if (!(OfflineClient.MC.hitResult instanceof EntityHitResult hit)) {
            ChatUtil.error("Point at an entity first.");
            return;
        }
        Entity entity = hit.getEntity();
        CompoundTag data;
        try {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
            entity.saveWithoutId(output);
            data = output.buildResult();
        } catch (RuntimeException e) {
            OfflineClient.LOG.warn("An entity could not be written out.", e);
            ChatUtil.error("The client could not write that entity out. The game log says why.");
            return;
        }
        EntityType<?> type = entity.getType();
        boolean summons = type.canSummon();
        CompoundTag copied = data.copy();
        OWN_TO_ONE_ENTITY.forEach(copied::remove);
        OfflineClient.MC.keyboardHandler.setClipboard(summons
            ? "/summon " + EntityType.getKey(type) + " ~ ~ ~ " + copied : copied.toString());
        ChatUtil.message("§b" + type.getDescription().getString() + " §7has §b" + Tally.counted(data.size(), "value")
            + "§7. " + (summons ? "Its summon command" : "Its data") + " is on the clipboard.");
        show(data);
    }

    private static void showBlock(LocalPlayer player) {
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit == null) {
            ChatUtil.error("Point at a block first.");
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = player.level().getBlockState(pos);
        BlockEntity inside = player.level().getBlockEntity(pos);
        CompoundTag data = inside == null ? new CompoundTag() : inside.saveWithoutMetadata(player.registryAccess());
        OfflineClient.MC.keyboardHandler.setClipboard("/setblock " + BlockUtil.text(pos) + " "
            + BlockStateParser.serialize(state) + (data.isEmpty() ? "" : data));
        int values = state.getProperties().size() + data.size();
        ChatUtil.message("§b" + state.getBlock().getName().getString() + " §7at §f" + BlockUtil.text(pos) + " §7has §b"
            + Tally.counted(values, "value") + "§7. Its setblock command is on the clipboard.");
        for (Property<?> property : state.getProperties()) {
            String value = valueName(state, property);
            ChatUtil.component(ChatUtil.copyOnClick(
                Component.literal("§b" + property.getName() + " §f" + value), property.getName() + "=" + value));
        }
        show(data);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T> CompoundTag encode(LocalPlayer player, Codec<T> codec, T value) {
        Tag tag = codec.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), value)
            .resultOrPartial(problem -> OfflineClient.LOG.warn("Part of an item could not be written out. {}", problem))
            .orElse(null);
        return tag instanceof CompoundTag compound ? compound : new CompoundTag();
    }

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
