package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
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
import org.lwjgl.glfw.GLFW;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ChestEsp extends Module {

    public enum Mode { BOX, GLOW }

    // The box of a container with the colour it gets and whether it has a fancy renderer.
    private record Target(BlockPos pos, AABB box, int color, boolean modelled) {
    }

    // One sixteenth of a block. Chest models sit this far in from the block edge.
    private static final double PIXEL = 1.0 / 16;
    // Below this share of full strength a faded box is not worth drawing.
    private static final float FAINT = 0.075f;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode", "How containers are marked.", Mode.BOX)
        .describe(Mode.BOX, "A box around each container.")
        .describe(Mode.GLOW, "A glowing outline around the true shape of chests and shulkers. The rest keep a box.");
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final BoolSetting chests = new BoolSetting("Chests",
        "Highlight chests.", true);
    private final ColorSetting chestColor = new ColorSetting("Chest colour",
        "Colour of chests.", 38, 1f, 1f, false).under(chests);
    private final BoolSetting trappedChests = new BoolSetting("Trapped chests",
        "Highlight trapped chests in their own colour.", true);
    private final ColorSetting trappedColor = new ColorSetting("Trapped chest colour",
        "Colour of trapped chests.", 0, 1f, 1f, false).under(trappedChests);
    private final BoolSetting enderChests = new BoolSetting("Ender chests",
        "Highlight ender chests.", true);
    private final ColorSetting enderColor = new ColorSetting("Ender chest colour",
        "Colour of ender chests.", 268, 1f, 1f, false).under(enderChests);
    private final BoolSetting shulkers = new BoolSetting("Shulkers",
        "Highlight shulker boxes.", true);
    private final ColorSetting shulkerColor = new ColorSetting("Shulker colour",
        "Colour of shulker boxes.", 38, 1f, 1f, false).under(shulkers);
    private final BoolSetting barrels = new BoolSetting("Barrels",
        "Highlight barrels.", true);
    private final ColorSetting barrelColor = new ColorSetting("Barrel colour",
        "Colour of barrels.", 38, 1f, 1f, false).under(barrels);
    private final BoolSetting furnaces = new BoolSetting("Furnaces",
        "Also mark furnaces and smokers and blast furnaces.", true);
    private final ColorSetting furnaceColor = new ColorSetting("Furnace colour",
        "Colour of furnaces.", 24, 0.55f, 0.75f, false).under(furnaces);
    private final BoolSetting hoppers = new BoolSetting("Hoppers",
        "Also mark hoppers.", true);
    private final ColorSetting hopperColor = new ColorSetting("Hopper colour",
        "Colour of hoppers.", 0, 0f, 0.7f, false).under(hoppers);
    private final BoolSetting dispensers = new BoolSetting("Dispensers",
        "Also mark dispensers.", true);
    private final ColorSetting dispenserColor = new ColorSetting("Dispenser colour",
        "Colour of dispensers.", 0, 0f, 0.55f, false).under(dispensers);
    private final BoolSetting droppers = new BoolSetting("Droppers",
        "Also mark droppers.", true);
    private final ColorSetting dropperColor = new ColorSetting("Dropper colour",
        "Colour of droppers.", 0, 0f, 0.45f, false).under(droppers);
    private final BoolSetting crafters = new BoolSetting("Crafters",
        "Also mark crafters.", true);
    private final ColorSetting crafterColor = new ColorSetting("Crafter colour",
        "Colour of crafters.", 190, 0.45f, 0.8f, false).under(crafters);
    private final BoolSetting brewingStands = new BoolSetting("Brewing stands",
        "Also mark brewing stands.", true);
    private final ColorSetting brewingColor = new ColorSetting("Brewing stand colour",
        "Colour of brewing stands.", 300, 0.5f, 0.85f, false).under(brewingStands);
    private final BoolSetting bookshelves = new BoolSetting("Chiseled bookshelves",
        "Also mark chiselled bookshelves.", true);
    private final ColorSetting bookshelfColor = new ColorSetting("Bookshelf colour",
        "Colour of chiselled bookshelves.", 34, 0.6f, 0.7f, false).under(bookshelves);
    private final BoolSetting pots = new BoolSetting("Decorated pots",
        "Also mark decorated pots.", true);
    private final ColorSetting potColor = new ColorSetting("Pot colour",
        "Colour of decorated pots.", 14, 0.5f, 0.8f, false).under(pots);
    private final BoolSetting chestCarts = new BoolSetting("Chest minecarts",
        "Also mark minecarts with a chest.", true);
    private final ColorSetting chestCartColor = new ColorSetting("Chest minecart colour",
        "Colour of chest minecarts.", 45, 0.7f, 0.9f, false).under(chestCarts);
    private final BoolSetting hopperCarts = new BoolSetting("Hopper minecarts",
        "Also mark minecarts with a hopper.", true);
    private final ColorSetting hopperCartColor = new ColorSetting("Hopper minecart colour",
        "Colour of hopper minecarts.", 0, 0f, 0.7f, false).under(hopperCarts);
    private final BoolSetting chestBoats = new BoolSetting("Chest boats",
        "Also mark boats with a chest.", true);
    private final ColorSetting chestBoatColor = new ColorSetting("Chest boat colour",
        "Colour of chest boats.", 34, 0.65f, 0.85f, false).under(chestBoats);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draw a line from you to each container.", false);
    private final NumberSetting fadeDistance = new NumberSetting("Fade distance",
        "Containers closer than this fade out so they do not fill your view.", 6, 0, 12, 0.5, " blocks");
    private final BoolSetting hideOpened = new BoolSetting("Hide opened",
        "Containers you have opened are no longer drawn.", false);
    private final BoolSetting recolourOpened = new BoolSetting("Recolour opened",
        "Containers you have opened take the colour below.", false).unless(hideOpened);
    private final ColorSetting openedColor = new ColorSetting("Opened colour",
        "Colour of containers you have opened.", 300, 0.55f, 0.8f, false)
        .under(recolourOpened, () -> !hideOpened.isOn() && recolourOpened.isOn());
    private final KeybindSetting forgetKey = new KeybindSetting("Forget key",
        "Press to forget which containers you have opened.", KeybindSetting.UNBOUND);
    private final NumberSetting radius = new NumberSetting("Radius",
        "Chunk radius to scan around you.", 4, 1, 8, 1, " chunks");

    private final List<Target> targets = new ArrayList<>();
    // Positions you have right clicked. Written from the network thread.
    private final Set<BlockPos> opened = ConcurrentHashMap.newKeySet();
    // Glow colour by position for the block entity renderer mixin. Replaced whole each tick.
    private volatile Map<BlockPos, Integer> glows = Map.of();
    private WeakReference<Level> world = new WeakReference<>(null);

    // The colour the block entity being drawn right now should glow in. Zero for none.
    private static int currentGlow;

    public ChestEsp() {
        super("ChestESP", "See containers through walls.", Category.RENDER);
        addSettings(mode);
        addSettings(style.settings());
        addSettings(chests, chestColor, trappedChests, trappedColor, enderChests, enderColor,
            shulkers, shulkerColor, barrels, barrelColor,
            furnaces, furnaceColor, hoppers, hopperColor, dispensers, dispenserColor,
            droppers, dropperColor, crafters, crafterColor, brewingStands, brewingColor,
            bookshelves, bookshelfColor, pots, potColor,
            chestCarts, chestCartColor, hopperCarts, hopperCartColor, chestBoats, chestBoatColor,
            tracers, fadeDistance, hideOpened, recolourOpened,
            openedColor, forgetKey, radius);
        searchTags("storage esp", "container esp");
    }

    @Override
    public String getSuffix() {
        return count(targets.size());
    }

    @Override
    protected void onDisable() {
        targets.clear();
        opened.clear();
        glows = Map.of();
    }

    // Set by the block entity renderer mixin around each submit.
    public static void setCurrentGlow(int color) {
        currentGlow = color;
    }

    public static int currentGlow() {
        return currentGlow;
    }

    // The glow colour for a block entity at a position. Zero when it gets none.
    public int glowFor(BlockPos pos) {
        if (!isEnabled() || !mode.is(Mode.GLOW)) {
            return 0;
        }
        return glows.getOrDefault(pos, 0);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundUseItemOnPacket packet && mc.level != null) {
            noteOpened(packet.getHitResult().getBlockPos());
        }
    }

    // Both halves of a double chest count as opened together.
    private void noteOpened(BlockPos pos) {
        BlockEntity blockEntity = mc.level.getBlockEntity(pos);
        if (blockEntity == null || colorFor(blockEntity) == 0) {
            return;
        }
        opened.add(pos);
        BlockState state = blockEntity.getBlockState();
        if (blockEntity instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE)
            && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            opened.add(pos.relative(ChestBlock.getConnectedDirection(state)));
        }
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() == GLFW.GLFW_PRESS && mc.gui.screen() == null
            && forgetKey.isBound() && event.getKey() == forgetKey.getValue()) {
            opened.clear();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        targets.clear();
        if (!inGame()) {
            return;
        }
        if (mc.level != world.get()) {
            world = new WeakReference<>(mc.level);
            opened.clear();
        }
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

    private Map<BlockPos, Integer> glowMap() {
        Map<BlockPos, Integer> map = new HashMap<>();
        for (Target target : targets) {
            if (target.pos() != null && target.modelled()) {
                map.put(target.pos(), target.color());
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
            boolean wasOpened = opened.contains(pos);
            if (wasOpened && hideOpened.isOn()) {
                continue;
            }
            if (wasOpened && recolourOpened.isOn()) {
                color = openedColor.getColor();
            }
            boolean chestShape = blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof EnderChestBlockEntity;
            AABB box = chestShape ? chestBox(pos) : DrawBatch.blockBox(pos);
            // A double chest is one container drawn from its right half.
            if (blockEntity instanceof ChestBlockEntity) {
                BlockState state = blockEntity.getBlockState();
                if (state.hasProperty(ChestBlock.TYPE)) {
                    ChestType type = state.getValue(ChestBlock.TYPE);
                    if (type == ChestType.LEFT) {
                        continue;
                    }
                    if (type == ChestType.RIGHT) {
                        box = box.minmax(chestBox(pos.relative(ChestBlock.getConnectedDirection(state))));
                    }
                }
            }
            boolean modelled = chestShape || blockEntity instanceof ShulkerBoxBlockEntity;
            targets.add(new Target(pos, box, color, modelled));
        }
    }

    private void collectVehicles() {
        for (Entity entity : mc.level.entitiesForRendering()) {
            int color = vehicleColorFor(entity);
            if (color != 0) {
                targets.add(new Target(null, entity.getBoundingBox(), color, false));
            }
        }
    }

    // Zero for a vehicle the settings leave out.
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

    // Zero for a container the settings leave out. Subclasses are tested before their parents.
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

    // Full strength beyond the fade distance and the square of the share inside it.
    private float strengthAt(AABB box) {
        double fade = fadeDistance.getValue();
        if (fade <= 0 || mc.player == null) {
            return 1;
        }
        double away = mc.player.distanceToSqr(box.getCenter());
        return away >= fade * fade ? 1 : (float) (away / (fade * fade));
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        for (Target target : targets) {
            float strength = strengthAt(target.box());
            if (strength < FAINT) {
                continue;
            }
            int color = ColorUtil.fade(target.color(), strength);
            if (!(mode.is(Mode.GLOW) && target.modelled())) {
                style.draw(batch, target.box(), color, true);
            }
            if (tracers.isOn()) {
                batch.tracer(target.box().getCenter(), color, true);
            }
        }
    }
}
