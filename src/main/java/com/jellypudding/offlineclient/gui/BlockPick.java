package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.util.AwayView;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.CornerPicker;
import com.jellypudding.offlineclient.util.CrosshairClip;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

// A block chosen by clicking it in the world. The screen that asked steps aside for the click
// and comes back once the block is in. One pick runs at a time.
public final class BlockPick {

    // How far off a block can be clicked. Far further than the hand reaches.
    private static final double REACH = 256;

    private static final Minecraft MC = OfflineClient.MC;

    // Null whilst no pick is waiting for its click.
    private static BlockPick waiting;

    private final String what;
    private final Consumer<BlockPos> picked;
    // The client screen the pick was asked from. Null when a command asked.
    private final Screen back;

    private BlockPick(String what, Consumer<BlockPos> picked, Screen back) {
        this.what = what;
        this.picked = picked;
        this.back = back;
    }

    // Waits for a click on a block and hands it over. What names the thing the block is for
    // such as the corner two of Wall. A chat line that asked closes its own screen first.
    public static void start(String what, Consumer<BlockPos> picked) {
        if (MC.player == null) {
            ChatUtil.error("You need to be in a world to pick a block.");
            return;
        }
        MC.schedule(() -> {
            if (waiting != null) {
                waiting.end();
            }
            Screen open = MC.gui.screen();
            waiting = new BlockPick(what, picked, open instanceof ClientScreen ? open : null);
            MC.gui.setScreen(null);
            OfflineClient.INSTANCE.getEventBus().register(waiting);
            ChatUtil.message("§7Click a block to set " + what + ". Opening any screen stops the pick.");
        });
    }

    private void end() {
        OfflineClient.INSTANCE.getEventBus().unregister(this);
        if (waiting == this) {
            waiting = null;
        }
    }

    // The block the middle of the view rests on. A camera that has left your eyes aims for itself.
    private static BlockPos aimed() {
        LocalPlayer player = MC.player;
        AwayView view = AwayView.active();
        Vec3 from = view == null ? player.getEyePosition(1) : view.getCamPos(1);
        Vec3 look = view == null ? player.getViewVector(1)
            : Vec3.directionFromRotation(view.getCamPitch(), view.getCamYaw());
        BlockHitResult hit = MC.level.clip(new CrosshairClip(from, from.add(look.scale(REACH)),
            ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().immutable() : null;
    }

    // The press is the pick's own and never reaches the game. One on thin air waits for another.
    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != InputConstants.PRESS || MC.gui.screen() != null || MC.player == null
            || !event.isFor(MC.options.keyUse) && !event.isFor(MC.options.keyAttack)) {
            return;
        }
        event.cancel();
        BlockPos pos = aimed();
        if (pos == null) {
            return;
        }
        end();
        picked.accept(pos);
        if (back != null) {
            MC.schedule(() -> MC.gui.setScreen(back));
        }
    }

    // A screen the player opened ends the pick. A sleeping client and a world left behind end it
    // without a word.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (MC.player == null || Modules.dormant()) {
            end();
        } else if (MC.gui.screen() != null) {
            end();
            ChatUtil.message("§7Stopped picking a block.");
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (MC.gui.screen() == null) {
            RenderUtil.underCrosshair(event.getContext(), "Click a block for " + what, CornerPicker.COLOR);
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        BlockPos pos = MC.player == null ? null : aimed();
        if (pos != null) {
            event.getBatch().outlineBlock(pos, CornerPicker.COLOR, true);
        }
    }
}
