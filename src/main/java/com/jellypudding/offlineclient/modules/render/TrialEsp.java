package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.config.BlockMarks;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.SpawnerMob;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// A vault keeps the players it rewarded to itself. Once a second it looks for players near it that
// it can still reward and wakes whilst there are any. Standing near a vault long enough for that
// to reach you shows whether it would reward you. A trial spawner sends when its next mob comes
// only whilst its trial runs.
public final class TrialEsp extends Module {

    private record Vault(BlockPos pos, boolean looted) {
    }

    private static final int SCAN_TICKS = 10;
    // A slow server looks for players near a vault less often than once a second.
    private static final int JUDGE_TICKS = 60;
    // Your place on the server can trail the one you see. A block of room keeps clear of the edge.
    private static final double NEAR_MARGIN = 1;
    // A label sits this far over the top of the spawner.
    private static final double LABEL_LIFT = 0.3;
    private static final int TEXT = 0xFFFFFFFF;

    private final BoolSetting vaults = new BoolSetting("Vaults",
        "Colours every vault by whether it can still reward you.", true);
    private final ColorSetting openColor = new ColorSetting("Open colour",
        "Colour of vaults that can still reward you.", 120, false).under(vaults);
    private final ColorSetting lootedColor = new ColorSetting("Looted colour",
        "Colour of vaults that already rewarded you.", 0, false).under(vaults);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH).under(vaults);
    private final BoolSetting spawners = new BoolSetting("Spawners",
        "Writes the mob and the seconds to the next wave above each active trial spawner.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1).under(spawners);

    private final WorldWatch world = new WorldWatch();
    // The tick you came near each vault you still stand near.
    private final Map<BlockPos, Integer> nearSince = new HashMap<>();
    // Rebuilt by every scan.
    private List<Vault> vaultsSeen = List.of();
    private List<TrialSpawnerBlockEntity> trialSpawners = List.of();
    private int open;
    private int ticks;
    private int wait;

    public TrialEsp() {
        super("TrialESP", "Marks the vaults that already rewarded you and times each trial spawner's next wave.",
            Category.RENDER);
        addSettings(vaults, openColor, lootedColor);
        addSettings(style.settings());
        addSettings(spawners, scale);
        searchTags("vault", "trial chamber", "trial spawner", "ominous", "trial key");
    }

    @Override
    public String getSuffix() {
        return count(open, "open");
    }

    @Override
    protected void onDisable() {
        clear();
    }

    private void clear() {
        nearSince.clear();
        vaultsSeen = List.of();
        trialSpawners = List.of();
        open = 0;
        wait = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (world.changed()) {
            clear();
        }
        ticks++;
        if (--wait > 0) {
            return;
        }
        wait = SCAN_TICKS;
        scan();
    }

    private void scan() {
        List<Vault> found = new ArrayList<>();
        List<TrialSpawnerBlockEntity> foundSpawners = new ArrayList<>();
        Set<BlockPos> near = new HashSet<>();
        LoadedChunks.forEachBlockEntity(blockEntity -> {
            if (blockEntity instanceof VaultBlockEntity vault && vaults.isOn()) {
                found.add(new Vault(vault.getBlockPos(), judge(vault, near)));
            } else if (blockEntity instanceof TrialSpawnerBlockEntity spawner && spawners.isOn()) {
                foundSpawners.add(spawner);
            }
        });
        nearSince.keySet().retainAll(near);
        vaultsSeen = found;
        trialSpawners = foundSpawners;
        open = (int) found.stream().filter(vault -> !vault.looted()).count();
    }

    // True once the vault is known to have rewarded you. Standing near it long enough settles it.
    private boolean judge(VaultBlockEntity vault, Set<BlockPos> near) {
        BlockPos pos = vault.getBlockPos();
        BlockMarks looted = BlockMarks.lootedVaults();
        if (counted(vault)) {
            near.add(pos);
            if (ticks - nearSince.computeIfAbsent(pos, key -> ticks) >= JUDGE_TICKS) {
                Boolean rewards = canReward(vault);
                if (rewards != null) {
                    looted.set(pos, !rewards);
                }
            }
        }
        return looted.isMarked(pos);
    }

    // The vault looks for players within its range and never counts a spectator.
    private boolean counted(VaultBlockEntity vault) {
        return !mc.player.isSpectator() && mc.player.blockPosition()
            .closerThan(vault.getBlockPos(), vault.getConfig().activationRange() - NEAR_MARGIN);
    }

    // Null whilst the vault cannot tell. Only an idle or awake vault looks for players near it.
    private Boolean canReward(VaultBlockEntity vault) {
        VaultState state = vault.getBlockState().getValue(VaultBlock.STATE);
        if (state != VaultState.INACTIVE && state != VaultState.ACTIVE) {
            return null;
        }
        return vault.getSharedData().getConnectedPlayers().contains(mc.player.getUUID());
    }

    // Such as Zombie 0.6s. A spawner whose time has come waits for room for its next mob.
    private static String labelOf(TrialSpawnerStateData.Packed data, double now) {
        double left = (data.nextMobSpawnsAt() - now) / SharedConstants.TICKS_PER_SECOND;
        String when = left > 0 ? String.format(Locale.ROOT, "%.1fs", left) : "due";
        EntityType<?> mob = data.nextSpawnData().map(SpawnerMob::of).orElse(null);
        return mob == null ? when : mob.getDescription().getString() + " " + when;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || !vaults.isOn()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Vault vault : vaultsSeen) {
            style.draw(batch, vault.pos(), vault.looted() ? lootedColor.getColor() : openColor.getColor(), true);
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || !spawners.isOn() || trialSpawners.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        double now = mc.level.getGameTime() + event.getPartialTicks();
        for (TrialSpawnerBlockEntity spawner : trialSpawners) {
            if (spawner.isRemoved() || spawner.getState() != TrialSpawnerState.ACTIVE) {
                continue;
            }
            Vec3 screen = WorldToScreen.project(Vec3.upFromBottomCenterOf(spawner.getBlockPos(), 1 + LABEL_LIFT));
            if (screen != null) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                    List.of(labelOf(spawner.getTrialSpawner().getStateData().pack(), now)), List.of(TEXT));
            }
        }
    }
}
