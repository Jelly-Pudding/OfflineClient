package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// The balls follow the nearest player rule of natural spawning and despawning and the boxes follow
// BaseSpawner.
public final class FarmRanges extends Module {

    public enum Centre { YOU, PINNED }

    // The ranges come with the block's data from the server. The server never sends a trial
    // spawner's ranges and it gets only its wake ball at the game's default reach.
    private record Spawner(BlockPos pos, int wake, int reach, boolean trial) {
    }

    // NaturalSpawner never lets a mob spawn on its own this close to a player.
    private static final double NO_SPAWN = 24;

    private static final int SCAN_TICKS = 20;

    private final EnumSetting<Centre> centre = new EnumSetting<>("Centre",
        "Where the balls of natural spawning sit.", Centre.YOU)
        .describe(Centre.YOU, "Round you wherever you go.")
        .describe(Centre.PINNED, "Round the pinned spot such as where you stand whilst your farm runs.");
    private final TextSetting spot = new TextSetting("Pinned spot",
        "The block the balls sit round whilst pinned. Type x y z or press Pin here.", "")
        .under(centre, Centre.PINNED);
    private final ActionSetting pin = new ActionSetting("Pin here",
        "Pins the balls to the block you stand in.", this::pinHere)
        .under(centre, Centre.PINNED);
    private final BoolSetting noSpawn = new BoolSetting("No spawn ball",
        "Draws the ball 24 blocks round the centre that no mob spawns inside on its own.", true);
    private final ColorSetting noSpawnColour = new ColorSetting("No spawn colour",
        "Colour of that ball.", 0, false).under(noSpawn);
    private final BoolSetting stay = new BoolSetting("Stay ball",
        "Draws the ball 32 blocks round the centre. A mob inside it never despawns at random. A Paper server"
            + " can set another distance.", true);
    private final ColorSetting stayColour = new ColorSetting("Stay colour",
        "Colour of that ball.", 60, false).under(stay);
    private final BoolSetting despawn = new BoolSetting("Despawn ball",
        "Draws the ball 128 blocks round the centre. A mob further than that from every player goes at once and"
            + " fish go at 64. A Paper server can set other distances.", true);
    private final ColorSetting despawnColour = new ColorSetting("Despawn colour",
        "Colour of that ball.", 280, false).under(despawn);
    private final BoolSetting spawners = new BoolSetting("Spawners",
        "Draws the wake ball and the spawn box and the count box of each spawner near you. A trial spawner gets"
            + " only its ball.", true);
    private final NumberSetting spawnerRange = new NumberSetting("Spawner range",
        "How far from you a spawner gets its shapes.", 48, 16, 128, 8, " blocks").min(1)
        .under(spawners);
    private final ColorSetting wakeColour = new ColorSetting("Wake colour",
        "Colour of the ball a player must stand in for the spawner to work.", 195, false).under(spawners);
    private final ColorSetting spawnColour = new ColorSetting("Spawn box colour",
        "Colour of the box the feet of each new mob land in.", 120, false).under(spawners);
    private final ColorSetting countColour = new ColorSetting("Count box colour",
        "Colour of the box the spawner counts its own mobs in before it spawns more.", 30, false)
        .under(spawners);

    private List<Spawner> near = List.of();
    private int wait;

    public FarmRanges() {
        super("FarmRanges", "Draws the distances that rule mob spawning round you and round each spawner.",
            Category.RENDER);
        addSettings(centre, spot, pin, noSpawn, noSpawnColour, stay, stayColour, despawn, despawnColour,
            spawners, spawnerRange, wakeColour, spawnColour, countColour);
        searchTags("mob farm", "spawn range", "despawn range", "spawner range", "afk spot");
    }

    @Override
    protected void onEnable() {
        wait = 0;
    }

    @Override
    protected void onDisable() {
        near = List.of();
    }

    private String pinHere() {
        if (!inGame()) {
            return "Join a world first";
        }
        spot.setValue(BlockUtil.text(mc.player.blockPosition()));
        return null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!spawners.isOn()) {
            near = List.of();
            return;
        }
        if (--wait > 0) {
            return;
        }
        wait = SCAN_TICKS;
        double reachSqr = spawnerRange.getValue() * spawnerRange.getValue();
        Vec3 eye = mc.player.getEyePosition();
        List<Spawner> found = new ArrayList<>();
        LoadedChunks.forEachBlockEntity(blockEntity -> {
            BlockPos pos = blockEntity.getBlockPos();
            if (Vec3.atCenterOf(pos).distanceToSqr(eye) > reachSqr) {
                return;
            }
            if (blockEntity instanceof SpawnerBlockEntity spawner) {
                BaseSpawner base = spawner.getSpawner();
                found.add(new Spawner(pos, base.requiredPlayerRange, base.spawnRange, false));
            } else if (blockEntity instanceof TrialSpawnerBlockEntity trial) {
                found.add(new Spawner(pos, trial.getTrialSpawner().getRequiredPlayerRange(), 0, true));
            }
        });
        near = found;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        Vec3 middle = middle(event.getPartialTicks());
        if (middle != null) {
            if (noSpawn.isOn()) {
                batch.sphere(middle, NO_SPAWN, noSpawnColour.getColor(), true);
            }
            if (stay.isOn()) {
                batch.sphere(middle, MobCategory.MONSTER.getNoDespawnDistance(), stayColour.getColor(), true);
            }
            if (despawn.isOn()) {
                batch.sphere(middle, MobCategory.MONSTER.getDespawnDistance(), despawnColour.getColor(), true);
            }
        }
        for (Spawner spawner : near) {
            draw(batch, spawner);
        }
    }

    private Vec3 middle(float partialTicks) {
        if (centre.is(Centre.YOU)) {
            return mc.player.getPosition(partialTicks);
        }
        BlockPos pinned = BlockUtil.parse(spot.getValue());
        return pinned == null ? null : Vec3.atBottomCenterOf(pinned);
    }

    // A player's feet must come within the wake ball of the spawner's middle. A new mob's feet
    // land within the reach of the middle sideways and one block above or below the spawner.
    // The count box is the spawner's block grown by the reach.
    private void draw(DrawBatch batch, Spawner spawner) {
        BlockPos pos = spawner.pos();
        Vec3 middle = Vec3.atCenterOf(pos);
        batch.sphere(middle, spawner.wake(), wakeColour.getColor(), true);
        if (spawner.trial()) {
            return;
        }
        int reach = spawner.reach();
        batch.outlineBox(new AABB(middle.x - reach, pos.getY() - 1, middle.z - reach,
            middle.x + reach, pos.getY() + 2, middle.z + reach), spawnColour.getColor(), true);
        batch.outlineBox(new AABB(pos).inflate(reach), countColour.getColor(), true);
    }
}
