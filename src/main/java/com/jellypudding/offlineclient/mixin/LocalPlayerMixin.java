package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.AirStrafingSpeedEvent;
import com.jellypudding.offlineclient.event.events.KnockbackEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.modules.movement.AntiPush;
import com.jellypudding.offlineclient.modules.movement.HighJump;
import com.jellypudding.offlineclient.modules.movement.LongJump;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.SafeWalk;
import com.jellypudding.offlineclient.modules.movement.Step;
import com.jellypudding.offlineclient.modules.player.Reach;
import com.jellypudding.offlineclient.modules.render.AntiBlind;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends AbstractClientPlayer {

    private LocalPlayerMixin(OfflineClient client, ClientLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Inject(method = "tick()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V",
            ordinal = 0))
    private void onTick(CallbackInfo ci) {
        OfflineClient.INSTANCE.getEventBus().post(TickEvent.INSTANCE);
    }

    @Inject(method = "sendPosition()V", at = @At("HEAD"))
    private void onPreMotion(CallbackInfo ci) {
        OfflineClient.INSTANCE.getEventBus().post(PreMotionEvent.INSTANCE);
    }

    @Inject(method = "sendPosition()V", at = @At("TAIL"))
    private void onPostMotion(CallbackInfo ci) {
        OfflineClient.INSTANCE.getEventBus().post(PostMotionEvent.INSTANCE);
    }

    @WrapOperation(method = "aiStep()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isSlowDueToUsingItem()Z",
            ordinal = 0))
    private boolean wrapAiStepItemUse(LocalPlayer instance, Operation<Boolean> original) {
        if (OfflineClient.INSTANCE.getModuleManager().get(NoSlowdown.class).isEnabled()) {
            return false;
        }
        return original.call(instance);
    }

    @WrapOperation(
        method = "modifyInput(Lnet/minecraft/world/phys/Vec2;)Lnet/minecraft/world/phys/Vec2;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z",
            ordinal = 0))
    private boolean wrapModifyInputItemUse(LocalPlayer instance, Operation<Boolean> original) {
        if (OfflineClient.INSTANCE.getModuleManager().get(NoSlowdown.class).isEnabled()) {
            return false;
        }
        return original.call(instance);
    }

    @Override
    protected float getFlyingSpeed() {
        AirStrafingSpeedEvent event = new AirStrafingSpeedEvent(super.getFlyingSpeed());
        OfflineClient.INSTANCE.getEventBus().post(event);
        return event.getSpeed();
    }

    @Override
    public void lerpMotion(Vec3 vec) {
        KnockbackEvent event = new KnockbackEvent(vec.x, vec.y, vec.z);
        OfflineClient.INSTANCE.getEventBus().post(event);
        super.lerpMotion(new Vec3(event.getX(), event.getY(), event.getZ()));
    }

    @Override
    protected float getJumpPower() {
        return super.getJumpPower()
            + OfflineClient.INSTANCE.getModuleManager().get(HighJump.class).getAdditionalJumpMotion();
    }

    /** LongJump adds its boost right after the game sets the jump velocity. */
    @Override
    public void jumpFromGround() {
        super.jumpFromGround();
        OfflineClient.INSTANCE.getModuleManager().get(LongJump.class).onJump();
    }

    /**
     * Crowd pushing checks this on both sides of a collision. False here
     * blocks pushes on us while we still push others.
     */
    @Override
    public boolean isPushable() {
        if (OfflineClient.INSTANCE.getModuleManager().get(AntiPush.class).blocksEntities()) {
            return false;
        }
        return super.isPushable();
    }

    @Override
    public boolean isPushedByFluid() {
        if (OfflineClient.INSTANCE.getModuleManager().get(AntiPush.class).blocksCurrents()) {
            return false;
        }
        return super.isPushedByFluid();
    }

    @Override
    public void onAboveBubbleColumn(boolean downwards, BlockPos pos) {
        if (OfflineClient.INSTANCE.getModuleManager().get(AntiPush.class).blocksBubbleColumns()) {
            return;
        }
        super.onAboveBubbleColumn(downwards, pos);
    }

    @Override
    public void onInsideBubbleColumn(boolean downwards) {
        if (OfflineClient.INSTANCE.getModuleManager().get(AntiPush.class).blocksBubbleColumns()) {
            return;
        }
        super.onInsideBubbleColumn(downwards);
    }

    /**
     * The client predicts mining five times slower in the air. FastBreak
     * lifts that here and fixes the server side by rewriting packets.
     */
    @Override
    public float getDestroySpeed(net.minecraft.world.level.block.state.BlockState state) {
        float speed = super.getDestroySpeed(state);
        if (!onGround() && !isInWater()
            && OfflineClient.INSTANCE.getModuleManager()
                .get(com.jellypudding.offlineclient.modules.player.FastBreak.class)
                .removesAirPenalty()) {
            speed *= 5;
        }
        return speed;
    }

    @Override
    public float maxUpStep() {
        return OfflineClient.INSTANCE.getModuleManager().get(Step.class).adjustStepHeight(super.maxUpStep());
    }

    @Override
    protected boolean isStayingOnGroundSurface() {
        return super.isStayingOnGroundSurface()
            || OfflineClient.INSTANCE.getModuleManager().get(SafeWalk.class).shouldGuard();
    }

    @Override
    public double blockInteractionRange() {
        return OfflineClient.INSTANCE.getModuleManager().get(Reach.class)
            .adjustRange(super.blockInteractionRange());
    }

    @Override
    public double entityInteractionRange() {
        return OfflineClient.INSTANCE.getModuleManager().get(Reach.class)
            .adjustRange(super.entityInteractionRange());
    }

    @Override
    public boolean hasEffect(Holder<MobEffect> effect) {
        if ((effect == MobEffects.BLINDNESS || effect == MobEffects.DARKNESS)
            && OfflineClient.INSTANCE.getModuleManager().get(AntiBlind.class).isEnabled()) {
            return false;
        }
        return super.hasEffect(effect);
    }

    /**
     * The pulsing darkness dimming reads this instead of hasEffect.
     */
    @Override
    public float getEffectBlendFactor(Holder<MobEffect> effect, float partialTicks) {
        if (effect == MobEffects.DARKNESS
            && OfflineClient.INSTANCE.getModuleManager().get(AntiBlind.class).isEnabled()) {
            return 0;
        }
        return super.getEffectBlendFactor(effect, partialTicks);
    }
}
