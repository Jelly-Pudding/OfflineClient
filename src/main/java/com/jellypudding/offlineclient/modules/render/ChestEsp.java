package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.HeightBand;
import com.jellypudding.offlineclient.render.NearFade;
import com.jellypudding.offlineclient.render.ShaderOutline;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChunkWindow;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.OpenedContainers;
import com.jellypudding.offlineclient.util.Villages;
import com.jellypudding.offlineclient.util.WorldWatch;
import com.jellypudding.offlineclient.worldgen.SeenBlocks;
import com.jellypudding.offlineclient.worldgen.StructureChests;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ChestEsp extends Module {

    public enum Mode { BOX, GLOW, SHADER }

    // A vehicle keeps its entity. A block keeps the boxes of its shape whilst the shader draws it.
    private record Target(BlockPos pos, AABB box, int color, boolean modelled, Entity vehicle, List<AABB> shape) {
    }

    // One sixteenth of a block. Chest models sit this far in from the block edge.
    private static final double PIXEL = 1.0 / 16;
    // The chests take turns and no tick judges them all at once.
    private static final int JUDGE_TICKS = 20;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode", "How containers are marked.", Mode.BOX)
        .describe(Mode.BOX, "A box around each container.")
        .describe(Mode.GLOW, "A glowing outline around the true shape of chests and shulkers. The rest keep a box.")
        .describe(Mode.SHADER, "A shader outline around the shape of every container.");
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final ShaderOutline outline = new ShaderOutline(style).under(mode, Mode.SHADER);
    private final BoolSetting chests = new BoolSetting("Chests",
        "Highlights chests.", true);
    private final ColorSetting chestColor = new ColorSetting("Chest colour",
        "Colour of chests.", 38, 1f, 1f, false).under(chests);
    private final BoolSetting naturalChests = new BoolSetting("Natural chests",
        "Also marks single chests that dungeons and villages and camps and trial chambers come with.", true)
        .under(chests);
    private final BoolSetting trappedChests = new BoolSetting("Trapped chests",
        "Highlights trapped chests in their own colour.", true);
    private final ColorSetting trappedColor = new ColorSetting("Trapped chest colour",
        "Colour of trapped chests.", 0, 1f, 1f, false).under(trappedChests);
    private final BoolSetting enderChests = new BoolSetting("Ender chests",
        "Highlights ender chests.", true);
    private final ColorSetting enderColor = new ColorSetting("Ender chest colour",
        "Colour of ender chests.", 268, 1f, 1f, false).under(enderChests);
    private final BoolSetting shulkers = new BoolSetting("Shulkers",
        "Highlights shulker boxes.", true);
    private final ColorSetting shulkerColor = new ColorSetting("Shulker colour",
        "Colour of shulker boxes.", 38, 1f, 1f, false).under(shulkers);
    private final BoolSetting barrels = new BoolSetting("Barrels",
        "Highlights barrels.", true);
    private final ColorSetting barrelColor = new ColorSetting("Barrel colour",
        "Colour of barrels.", 38, 1f, 1f, false).under(barrels);
    private final BoolSetting furnaces = new BoolSetting("Furnaces",
        "Also marks furnaces and smokers and blast furnaces.", true);
    private final ColorSetting furnaceColor = new ColorSetting("Furnace colour",
        "Colour of furnaces.", 24, 0.55f, 0.75f, false).under(furnaces);
    private final BoolSetting hoppers = new BoolSetting("Hoppers",
        "Also marks hoppers.", true);
    private final ColorSetting hopperColor = new ColorSetting("Hopper colour",
        "Colour of hoppers.", 0, 0f, 0.7f, false).under(hoppers);
    private final BoolSetting dispensers = new BoolSetting("Dispensers",
        "Also marks dispensers.", true);
    private final ColorSetting dispenserColor = new ColorSetting("Dispenser colour",
        "Colour of dispensers.", 0, 0f, 0.55f, false).under(dispensers);
    private final BoolSetting droppers = new BoolSetting("Droppers",
        "Also marks droppers.", true);
    private final ColorSetting dropperColor = new ColorSetting("Dropper colour",
        "Colour of droppers.", 0, 0f, 0.45f, false).under(droppers);
    private final BoolSetting crafters = new BoolSetting("Crafters",
        "Also marks crafters.", true);
    private final ColorSetting crafterColor = new ColorSetting("Crafter colour",
        "Colour of crafters.", 190, 0.45f, 0.8f, false).under(crafters);
    private final BoolSetting brewingStands = new BoolSetting("Brewing stands",
        "Also marks brewing stands.", true);
    private final ColorSetting brewingColor = new ColorSetting("Brewing stand colour",
        "Colour of brewing stands.", 300, 0.5f, 0.85f, false).under(brewingStands);
    private final BoolSetting bookshelves = new BoolSetting("Chiseled bookshelves",
        "Also marks chiselled bookshelves.", true);
    private final ColorSetting bookshelfColor = new ColorSetting("Bookshelf colour",
        "Colour of chiselled bookshelves.", 34, 0.6f, 0.7f, false).under(bookshelves);
    private final BoolSetting pots = new BoolSetting("Decorated pots",
        "Also marks decorated pots.", true);
    private final ColorSetting potColor = new ColorSetting("Pot colour",
        "Colour of decorated pots.", 14, 0.5f, 0.8f, false).under(pots);
    private final BoolSetting chestCarts = new BoolSetting("Chest minecarts",
        "Also marks minecarts with a chest.", true);
    private final ColorSetting chestCartColor = new ColorSetting("Chest minecart colour",
        "Colour of chest minecarts.", 45, 0.7f, 0.9f, false).under(chestCarts);
    private final BoolSetting hopperCarts = new BoolSetting("Hopper minecarts",
        "Also marks minecarts with a hopper.", true);
    private final ColorSetting hopperCartColor = new ColorSetting("Hopper minecart colour",
        "Colour of hopper minecarts.", 0, 0f, 0.7f, false).under(hopperCarts);
    private final BoolSetting chestBoats = new BoolSetting("Chest boats",
        "Also marks boats with a chest.", true);
    private final ColorSetting chestBoatColor = new ColorSetting("Chest boat colour",
        "Colour of chest boats.", 34, 0.65f, 0.85f, false).under(chestBoats);
    private final BoolSetting buried = new BoolSetting("Buried",
        "Marks chests and shulker boxes that cannot be opened in the colour below.", false);
    private final ColorSetting buriedColor = new ColorSetting("Buried colour",
        "Colour of containers that cannot be opened.", 0, 0f, 0.35f, false).under(buried);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draws a line from you to each container.", false);
    private final NearFade fade = new NearFade(6);
    private final BoolSetting hideOpened = new BoolSetting("Hide opened",
        "Containers you have opened are no longer drawn.", false);
    private final BoolSetting recolourOpened = new BoolSetting("Recolour opened",
        "Containers you have opened take the colour below.", false).unless(hideOpened);
    private final ColorSetting openedColor = new ColorSetting("Opened colour",
        "Colour of containers you have opened.", 300, 0.55f, 0.8f, false)
        .under(recolourOpened, () -> !hideOpened.isOn() && recolourOpened.isOn());
    private final KeybindSetting forgetKey = new KeybindSetting("Forget key",
        "Forgets which containers you have opened.", KeybindSetting.UNBOUND);
    private final NumberSetting radius = new NumberSetting("Radius",
        "Chunk radius to scan around you.", 4, 1, 8, 1, " chunks");
    private final HeightBand band = new HeightBand("containers");

    private final List<Target> targets = new ArrayList<>();
    private final OpenedContainers opened = new OpenedContainers();
    // Glow colour by position for the block entity renderer mixin. Replaced whole each tick.
    private volatile Map<BlockPos, Integer> glows = Map.of();
    private final WorldWatch world = new WorldWatch();
    // Whether each single chest looked at was put down by the world. A chest whose tests still
    // wait for chunks has no entry yet.
    private final Long2BooleanOpenHashMap naturalAt = new Long2BooleanOpenHashMap();
    private int ticks;

    private static int currentGlow;

    public ChestEsp() {
        super("ChestESP", "Shows containers through walls.", Category.RENDER);
        addSettings(mode);
        addSettings(style.settings());
        addSettings(outline.settings());
        addSettings(chests, chestColor, naturalChests, trappedChests, trappedColor, enderChests, enderColor,
            shulkers, shulkerColor, barrels, barrelColor,
            furnaces, furnaceColor, hoppers, hopperColor, dispensers, dispenserColor,
            droppers, dropperColor, crafters, crafterColor, brewingStands, brewingColor,
            bookshelves, bookshelfColor, pots, potColor,
            chestCarts, chestCartColor, hopperCarts, hopperCartColor, chestBoats, chestBoatColor,
            buried, buriedColor, tracers, fade.setting(), hideOpened, recolourOpened,
            openedColor, forgetKey, radius);
        addSettings(band.settings());
        searchTags("storage esp", "container esp", "buried chest", "dungeon chest", "shader outline");
    }

    @Override
    public String getSuffix() {
        return count(targets.size());
    }

    @Override
    protected void onDisable() {
        targets.clear();
        opened.clear();
        naturalAt.clear();
        glows = Map.of();
        outline.release();
    }

    // Set by the block entity renderer mixin around each submit.
    public static void setCurrentGlow(int color) {
        currentGlow = color;
    }

    public static int currentGlow() {
        return currentGlow;
    }

    public int glowFor(BlockPos pos) {
        if (!isEnabled() || !mode.is(Mode.GLOW) || Conceal.hides(Conceal.Part.WORLD)) {
            return 0;
        }
        return glows.getOrDefault(pos, 0);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        opened.noteClick(event.getPacket(), blockEntity -> colorFor(blockEntity) != 0);
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.presses(forgetKey) && mc.gui.screen() == null) {
            opened.clear();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        targets.clear();
        if (world.changed()) {
            opened.clear();
            naturalAt.clear();
        }
        ticks++;
        int r = radius.getInt();
        int centerX = mc.player.chunkPosition().x();
        int centerZ = mc.player.chunkPosition().z();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                LevelChunk chunk = mc.level.getChunkSource()
                    .getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false);
                if (chunk != null) {
                    collectBlocks(chunk);
                }
            }
        }
        if (chestCarts.isOn() || hopperCarts.isOn() || chestBoats.isOn()) {
            collectVehicles();
        }
        glows = mode.is(Mode.GLOW) ? glowMap() : Map.of();
    }

    // Each half of a double chest draws itself and needs the colour too.
    private Map<BlockPos, Integer> glowMap() {
        Map<BlockPos, Integer> map = new HashMap<>();
        for (Target target : targets) {
            if (target.pos() == null || !target.modelled()) {
                continue;
            }
            map.put(target.pos(), target.color());
            BlockPos other = BlockUtil.otherChestHalf(target.pos(), mc.level.getBlockState(target.pos()));
            if (other != null) {
                map.put(other, target.color());
            }
        }
        return map;
    }

    private void collectBlocks(LevelChunk chunk) {
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            int color = colorFor(blockEntity);
            if (color == 0) {
                continue;
            }
            BlockPos pos = blockEntity.getBlockPos();
            if (!band.contains(pos.getY()) || !naturalChests.isOn() && natural(blockEntity)) {
                continue;
            }
            boolean wasOpened = opened.contains(pos);
            if (wasOpened && hideOpened.isOn()) {
                continue;
            }
            if (wasOpened && recolourOpened.isOn()) {
                color = openedColor.getColor();
            }
            if (buried.isOn() && BlockUtil.keptShut(blockEntity)) {
                color = buriedColor.getColor();
            }
            boolean chestShape = blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof EnderChestBlockEntity;
            AABB box = chestShape ? chestBox(pos) : DrawBatch.blockBox(pos);
            // A double chest is one container drawn from its right half.
            BlockState state = blockEntity.getBlockState();
            BlockPos other = BlockUtil.otherChestHalf(pos, state);
            if (other != null) {
                if (state.getValue(ChestBlock.TYPE) == ChestType.LEFT) {
                    continue;
                }
                box = box.minmax(chestBox(other));
            }
            boolean modelled = chestShape || blockEntity instanceof ShulkerBoxBlockEntity;
            List<AABB> shape = mode.is(Mode.SHADER) ? shapeOf(pos, state, other, box) : List.of();
            targets.add(new Target(pos, box, color, modelled, null, shape));
        }
    }

    private List<AABB> shapeOf(BlockPos pos, BlockState state, BlockPos other, AABB box) {
        List<AABB> parts = new ArrayList<>();
        for (AABB part : state.getShape(mc.level, pos).toAabbs()) {
            parts.add(part.move(pos));
        }
        if (other != null) {
            for (AABB part : mc.level.getBlockState(other).getShape(mc.level, other).toAabbs()) {
                parts.add(part.move(other));
            }
        }
        return parts.isEmpty() ? List.of(box) : parts;
    }

    private void collectVehicles() {
        for (Entity entity : mc.level.entitiesForRendering()) {
            int color = vehicleColorFor(entity);
            if (color != 0 && band.contains(entity.getY())) {
                targets.add(new Target(null, entity.getBoundingBox(), color, false, entity, List.of()));
            }
        }
    }

    // Only the Overworld builds them. A chest with chunks round it still missing counts as placed
    // until its turn comes again. Only the village test needs the chunks a whole village spans.
    private boolean natural(BlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos();
        if (!(blockEntity instanceof ChestBlockEntity) || blockEntity instanceof TrappedChestBlockEntity
            || BlockUtil.otherChestHalf(pos, blockEntity.getBlockState()) != null
            || !mc.level.dimension().equals(Level.OVERWORLD)) {
            return false;
        }
        long key = pos.asLong();
        if (naturalAt.containsKey(key)) {
            return naturalAt.get(key);
        }
        if (Math.floorMod(ticks + Long.hashCode(key), JUDGE_TICKS) != 0) {
            return false;
        }
        ChunkWindow around = ChunkWindow.capture(pos, StructureChests.REACH_CHUNKS);
        if (!around.complete()) {
            return false;
        }
        SeenBlocks seen = around::get;
        if (StructureChests.inDungeon(seen, pos) || StructureChests.inTrialChamber(seen, pos)
            || StructureChests.atCamp(seen, pos)) {
            naturalAt.put(key, true);
            return true;
        }
        if (!Villages.arrived(pos)) {
            return false;
        }
        boolean village = Villages.around(pos, state -> state.is(Blocks.BELL));
        naturalAt.put(key, village);
        return village;
    }

    private int vehicleColorFor(Entity entity) {
        if (entity instanceof MinecartChest) {
            return chestCarts.isOn() ? chestCartColor.getColor() : 0;
        }
        if (entity instanceof MinecartHopper) {
            return hopperCarts.isOn() ? hopperCartColor.getColor() : 0;
        }
        if (entity instanceof AbstractChestBoat) {
            return chestBoats.isOn() ? chestBoatColor.getColor() : 0;
        }
        return 0;
    }

    // The chest model is a pixel in from each side and two pixels short of the top.
    private static AABB chestBox(BlockPos pos) {
        return new AABB(pos.getX() + PIXEL, pos.getY(), pos.getZ() + PIXEL,
            pos.getX() + 1 - PIXEL, pos.getY() + 1 - 2 * PIXEL, pos.getZ() + 1 - PIXEL);
    }

    // Subclasses are tested before their parents.
    private int colorFor(BlockEntity blockEntity) {
        if (blockEntity instanceof TrappedChestBlockEntity) {
            return trappedChests.isOn() ? trappedColor.getColor() : 0;
        }
        if (blockEntity instanceof ChestBlockEntity) {
            return chests.isOn() ? chestColor.getColor() : 0;
        }
        if (blockEntity instanceof EnderChestBlockEntity) {
            return enderChests.isOn() ? enderColor.getColor() : 0;
        }
        if (blockEntity instanceof ShulkerBoxBlockEntity) {
            return shulkers.isOn() ? shulkerColor.getColor() : 0;
        }
        if (blockEntity instanceof BarrelBlockEntity) {
            return barrels.isOn() ? barrelColor.getColor() : 0;
        }
        return otherColorFor(blockEntity);
    }

    // A dropper is a kind of dispenser. It has to be asked about first.
    private int otherColorFor(BlockEntity blockEntity) {
        if (blockEntity instanceof AbstractFurnaceBlockEntity) {
            return furnaces.isOn() ? furnaceColor.getColor() : 0;
        }
        if (blockEntity instanceof HopperBlockEntity) {
            return hoppers.isOn() ? hopperColor.getColor() : 0;
        }
        if (blockEntity instanceof DropperBlockEntity) {
            return droppers.isOn() ? dropperColor.getColor() : 0;
        }
        if (blockEntity instanceof DispenserBlockEntity) {
            return dispensers.isOn() ? dispenserColor.getColor() : 0;
        }
        if (blockEntity instanceof CrafterBlockEntity) {
            return crafters.isOn() ? crafterColor.getColor() : 0;
        }
        if (blockEntity instanceof BrewingStandBlockEntity) {
            return brewingStands.isOn() ? brewingColor.getColor() : 0;
        }
        if (blockEntity instanceof ChiseledBookShelfBlockEntity) {
            return bookshelves.isOn() ? bookshelfColor.getColor() : 0;
        }
        return blockEntity instanceof DecoratedPotBlockEntity && pots.isOn()
            ? potColor.getColor() : 0;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        // The shader mode draws boxes once its shaders have failed.
        boolean shader = mode.is(Mode.SHADER) && ShaderOutline.works();
        if (!shader) {
            outline.release();
        }
        for (Target target : targets) {
            float strength = fade.strengthAt(target.box().getCenter());
            if (strength <= 0) {
                continue;
            }
            int color = ColorUtil.fade(target.color(), strength);
            if (shader) {
                outlineTarget(target, color);
            } else if (!(mode.is(Mode.GLOW) && target.modelled())) {
                style.draw(batch, target.box(), color, true);
            }
            if (tracers.isOn()) {
                batch.tracer(target.box().getCenter(), color, true);
            }
        }
        if (shader) {
            outline.draw(event.getPartialTicks());
        }
    }

    private void outlineTarget(Target target, int color) {
        if (target.vehicle() != null) {
            outline.entity(target.vehicle(), color);
            return;
        }
        for (AABB part : target.shape()) {
            outline.box(part, color);
        }
    }
}
