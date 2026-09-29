package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.HologramPresets;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.EntityAddedEvent;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.TextMarkup;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Spawner;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

// A spawn egg makes whatever entity its data names and loads the rest of that data into it. In
// creative an egg made to hold a text display puts styled text in the world with no command access.
// Paper drops a position given in that data unless the player holds its nbt place permission. The
// click decides where the entity goes instead.
public final class HologramCommand extends Command {

    private static final String STAND = "stand";
    private static final List<String> VERBS = List.of(STAND, "save", "load", "delete", "list", "reset");

    // Any spawn egg makes the entity its data names.
    private static final Item EGG = Items.PIG_SPAWN_EGG;

    // Keys the game reads that it keeps private.
    private static final String LINE_WIDTH_KEY = "line_width";
    private static final String NAME_VISIBLE_KEY = "CustomNameVisible";
    private static final String INVISIBLE_KEY = "Invisible";
    private static final String MARKER_KEY = "Marker";

    // Wide enough that only the typed line breaks start a new line.
    private static final int LINE_WIDTH = 4096;

    // The server answers a spawn within a round trip. This allows for a slow one.
    private static final int ANSWER_TICKS = 3 * SharedConstants.TICKS_PER_SECOND;
    // How far past the clicked blocks a new entity still counts as the hologram.
    private static final double ANSWER_REACH = 1;

    public HologramCommand() {
        super("hologram", "Puts floating styled text on the block you look at whilst in creative.",
            "hologram [stand] <text> or hologram <save|load|delete|list|reset> [name] [text]", "holo");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            usage();
            ChatUtil.message("§7Colour codes such as &6 work as in chat. &#ff8800 picks any colour and \\n starts "
                + "a new line. Add stand in front for armour stands that every client version shows.");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case STAND -> spawnStands(args);
            case "save" -> save(args);
            case "load" -> load(args);
            case "delete" -> delete(args);
            case "list" -> list();
            case "reset" -> ChatUtil.message("§7Put back the §b" + HologramPresets.get().restoreExample()
                + " §7example.");
            default -> spawn(words(args, 0), false);
        }
    }

    private void spawnStands(String[] args) {
        if (args.length < 2) {
            usage("hologram stand <text>");
            return;
        }
        spawn(words(args, 1), true);
    }

    private void save(String[] args) {
        if (args.length < 3) {
            usage("hologram save <name> <text>");
            return;
        }
        HologramPresets.get().save(args[1], words(args, 2));
        ChatUtil.message("§7Saved §b" + args[1] + "§7.");
    }

    private void load(String[] args) {
        boolean stands = args.length == 3 && args[2].equalsIgnoreCase(STAND);
        if (args.length != 2 && !stands) {
            usage("hologram load <name> [stand]");
            return;
        }
        String text = HologramPresets.get().find(args[1]);
        if (text == null) {
            ChatUtil.error("There is no hologram called " + args[1] + ".");
            return;
        }
        spawn(text, stands);
    }

    private void delete(String[] args) {
        if (args.length != 2) {
            usage("hologram delete <name>");
            return;
        }
        if (HologramPresets.get().delete(args[1])) {
            ChatUtil.message("§7Deleted §b" + args[1] + "§7.");
        } else {
            ChatUtil.error("There is no hologram called " + args[1] + ".");
        }
    }

    // Each name shows its text as it would look. Clicking a name types the command that places it.
    private static void list() {
        List<String> names = HologramPresets.get().names();
        if (names.isEmpty()) {
            ChatUtil.message("§7You have no saved holograms.");
            return;
        }
        String load = OfflineClient.INSTANCE.getCommandManager().getPrefix() + "hologram load ";
        for (String name : names) {
            MutableComponent row = Component.literal("§b" + name)
                .withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand(load + name)));
            List<Component> lines = TextMarkup.lines(HologramPresets.get().find(name));
            for (int i = 0; i < lines.size(); i++) {
                row.append(i == 0 ? " " : " §8| ").append(lines.get(i));
            }
            ChatUtil.component(row);
        }
    }

    private static void spawn(String markup, boolean stands) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (!player.hasInfiniteMaterials()) {
            ChatUtil.error("Holograms need creative mode.");
            return;
        }
        BlockHitResult aim = BlockUtil.aimedBlock();
        if (aim == null) {
            ChatUtil.error("Look at the block the hologram should go on.");
            return;
        }
        List<Component> lines = TextMarkup.lines(markup);
        if (stands) {
            placeStands(aim, lines);
        } else {
            placeDisplay(aim, lines);
        }
    }

    // One text display holds every line and turns to face whoever looks at it.
    private static void placeDisplay(BlockHitResult aim, List<Component> lines) {
        if (OfflineClient.MC.level.getBlockEntity(aim.getBlockPos()) instanceof Spawner) {
            ChatUtil.error("A spawn egg would change that spawner. Look at another block.");
            return;
        }
        MutableComponent text = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            text.append(i == 0 ? "" : "\n").append(lines.get(i));
        }
        CompoundTag data = new CompoundTag();
        data.put(Display.TextDisplay.TAG_TEXT, encode(text));
        data.putInt(LINE_WIDTH_KEY, LINE_WIDTH);
        data.putString(Display.TAG_BILLBOARD, Display.BillboardConstraints.CENTER.getSerializedName());
        if (!use(egg(EntityTypes.TEXT_DISPLAY, data), aim)) {
            ChatUtil.error("The egg could not be used on that block.");
            return;
        }
        expect(EntityTypes.TEXT_DISPLAY, 1, new AABB(aim.getBlockPos()));
    }

    // An invisible armour stand per line with its name showing. The top line comes first and each
    // line takes the empty block above the one after it. Every one of those blocks must be in reach.
    private static void placeStands(BlockHitResult aim, List<Component> lines) {
        BlockPos bottom = spawnSpot(aim);
        int placed = 0;
        for (int i = 0; i < lines.size(); i++) {
            BlockPos spot = bottom.above(lines.size() - 1 - i);
            BlockHitResult click = new BlockHitResult(Vec3.atCenterOf(spot), Direction.UP, spot, false);
            if (spawnSpot(click).equals(spot) && BlockUtil.serverReaches(spot)
                && use(egg(EntityTypes.ARMOR_STAND, standData(lines.get(i))), click)) {
                placed++;
            }
        }
        if (placed < lines.size()) {
            ChatUtil.error((lines.size() - placed) + " of " + Tally.counted(lines.size(), "line")
                + " could not be placed. Each needs an empty block within reach.");
        }
        if (placed > 0) {
            AABB column = AABB.encapsulatingFullBlocks(bottom, bottom.above(lines.size() - 1));
            expect(EntityTypes.ARMOR_STAND, placed, column);
        }
    }

    // Where an egg used on this face puts its entity. An empty block takes it itself and a solid one
    // passes it to the side that was clicked.
    private static BlockPos spawnSpot(BlockHitResult click) {
        BlockPos pos = click.getBlockPos();
        return BlockUtil.state(pos).getCollisionShape(OfflineClient.MC.level, pos).isEmpty()
            ? pos : pos.relative(click.getDirection());
    }

    private static CompoundTag standData(Component line) {
        CompoundTag data = new CompoundTag();
        data.put(Entity.TAG_CUSTOM_NAME, encode(line));
        data.putBoolean(NAME_VISIBLE_KEY, true);
        data.putBoolean(INVISIBLE_KEY, true);
        data.putBoolean(MARKER_KEY, true);
        data.putBoolean(Entity.TAG_NO_GRAVITY, true);
        return data;
    }

    private static Tag encode(Component text) {
        return ComponentSerialization.CODEC.encodeStart(
            OfflineClient.MC.player.registryAccess().createSerializationContext(NbtOps.INSTANCE), text).getOrThrow();
    }

    private static ItemStack egg(EntityType<?> type, CompoundTag data) {
        ItemStack egg = new ItemStack(EGG);
        egg.set(DataComponents.ENTITY_DATA, TypedEntityData.of(type, data));
        return egg;
    }

    // Holds the egg for one click. The click is made crouching or a block that opens would take it.
    private static boolean use(ItemStack egg, BlockHitResult click) {
        InventoryUtil.Conjured held = InventoryUtil.conjure(egg);
        if (held == null) {
            return false;
        }
        try {
            return InputUtil.whileSneaking(() -> OfflineClient.MC.gameMode
                .useItemOn(OfflineClient.MC.player, InteractionHand.MAIN_HAND, click).consumesAction());
        } finally {
            held.giveBack();
        }
    }

    private static void expect(EntityType<?> type, int count, AABB clicked) {
        OfflineClient.INSTANCE.getEventBus().register(new Arrival(type, count, clicked.inflate(ANSWER_REACH)));
    }

    // Counts the entities the server spawns where the eggs were used and says how the hologram went.
    // A plugin can still refuse a spawn egg and the click alone never shows that.
    private static final class Arrival {

        private final EntityType<?> type;
        private final int expected;
        private final AABB area;
        private int arrived;
        private int ticksLeft = ANSWER_TICKS;

        private Arrival(EntityType<?> type, int expected, AABB area) {
            this.type = type;
            this.expected = expected;
            this.area = area;
        }

        @Subscribe
        private void onEntityAdded(EntityAddedEvent event) {
            Entity entity = event.getEntity();
            if (entity.getType() == type && area.contains(entity.position()) && ++arrived == expected) {
                finish();
            }
        }

        @Subscribe
        private void onClientTick(ClientTickEvent event) {
            if (--ticksLeft <= 0) {
                finish();
            }
        }

        private void finish() {
            OfflineClient.INSTANCE.getEventBus().unregister(this);
            if (arrived == expected) {
                ChatUtil.message(expected == 1
                    ? "§7Placed the hologram." : "§7Placed " + Tally.counted(expected, "line") + ".");
            } else if (arrived == 0) {
                ChatUtil.error("The server did not spawn the hologram. It or a plugin refused the spawn egg.");
            } else {
                ChatUtil.error("The server spawned " + arrived + " of " + Tally.counted(expected, "line") + ".");
            }
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, VERBS);
        }
        String verb = tokens[1].toLowerCase(Locale.ROOT);
        if (index == 2 && (verb.equals("load") || verb.equals("delete"))) {
            return CommandManager.filter(current, HologramPresets.get().names());
        }
        if (index == 3 && verb.equals("load")) {
            return CommandManager.filter(current, List.of(STAND));
        }
        return List.of();
    }
}
