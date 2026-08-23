package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.AirStrafingSpeedEvent;
import com.jellypudding.offlineclient.event.events.KnockbackEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.modules.combat.BowAimbot;
import com.jellypudding.offlineclient.modules.movement.AntiPush;
import com.jellypudding.offlineclient.modules.movement.HighJump;
import com.jellypudding.offlineclient.modules.movement.LongJump;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.EdgeGuard;
import com.jellypudding.offlineclient.modules.movement.Step;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.FastBreak;
import com.jellypudding.offlineclient.modules.player.Reach;
import com.jellypudding.offlineclient.modules.render.AntiBlind;
import com.jellypudding.offlineclient.util.Modules;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends AbstractClientPlayer {

    @Shadow
    public float portalEffectIntensity;

    @Shadow
    public float oPortalEffectIntensity;

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
        if (offlineclient$noSlowdown()) {
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
        if (offlineclient$noSlowdown()) {
            return false;
        }
        return original.call(instance);
    }

    private static boolean offlineclient$noSlowdown() {
        if (Modules.enabled(NoSlowdown.class)) {
            return true;
        }
        BowAimbot bowAimbot = Modules.get(BowAimbot.class);
        if (bowAimbot != null && bowAimbot.suppressesSlowdown()) {
            return true;
        }
        AutoEat autoEat = Modules.get(AutoEat.class);
        return autoEat != null && autoEat.suppressesSlowdown();
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
        HighJump highJump = Modules.get(HighJump.class);
        return super.getJumpPower() + (highJump == null ? 0f : highJump.getAdditionalJumpMotion());
    }

    @Override
    public void jumpFromGround() {
        super.jumpFromGround();
        LongJump longJump = Modules.get(LongJump.class);
        if (longJump != null) {
            longJump.onJump();
        }
    }

    /**
     * Crowd pushing checks this on both sides of a collision. False here blocks
     * incoming pushes and leaves outgoing ones alone.
     */
    @Override
    public boolean isPushable() {
        AntiPush antiPush = Modules.get(AntiPush.class);
        if (antiPush != null && antiPush.blocksEntities()) {
            return false;
        }
        return super.isPushable();
    }

    @Override
    public boolean isPushedByFluid() {
        AntiPush antiPush = Modules.get(AntiPush.class);
        if (antiPush != null && antiPush.blocksCurrents()) {
            return false;
        }
        return super.isPushedByFluid();
    }

    @Override
    public void onAboveBubbleColumn(boolean downwards, BlockPos pos) {
        AntiPush antiPush = Modules.get(AntiPush.class);
        if (antiPush != null && antiPush.blocksBubbleColumns()) {
            return;
        }
        super.onAboveBubbleColumn(downwards, pos);
    }

    @Override
    public void onInsideBubbleColumn(boolean downwards) {
        AntiPush antiPush = Modules.get(AntiPush.class);
        if (antiPush != null && antiPush.blocksBubbleColumns()) {
            return;
        }
        super.onInsideBubbleColumn(downwards);
    }

    /**
     * The client predicts mining five times slower in the air. FastBreak fixes
     * the server side by rewriting packets and can raise the rate outright.
     */
    @Override
    public float getDestroySpeed(BlockState state) {
        float speed = super.getDestroySpeed(state);
        FastBreak fastBreak = Modules.get(FastBreak.class);
        if (fastBreak == null) {
            return speed;
        }
        if (!onGround() && !isInWater() && fastBreak.removesAirPenalty()) {
            speed *= 5;
        }
        return speed * fastBreak.speedMultiplier();
    }

    @Override
    public float maxUpStep() {
        float vanilla = super.maxUpStep();
        Step step = Modules.get(Step.class);
        return step == null ? vanilla : step.adjustStepHeight(vanilla);
    }

    @Override
    protected boolean isStayingOnGroundSurface() {
        if (super.isStayingOnGroundSurface()) {
            return true;
        }
        EdgeGuard edgeGuard = Modules.get(EdgeGuard.class);
        return edgeGuard != null && edgeGuard.shouldGuard();
    }

    @Override
    public double blockInteractionRange() {
        double vanilla = super.blockInteractionRange();
        Reach reach = Modules.get(Reach.class);
        return reach == null ? vanilla : reach.adjustRange(vanilla);
    }

    @Override
    public double entityInteractionRange() {
        double vanilla = super.entityInteractionRange();
        Reach reach = Modules.get(Reach.class);
        return reach == null ? vanilla : reach.adjustRange(vanilla);
    }

    @Override
    public boolean hasEffect(Holder<MobEffect> effect) {
        if (blocked(effect)) {
            return false;
        }
        return super.hasEffect(effect);
    }

    // The pulsing darkness dimming and the nausea spin read this instead of hasEffect.
    @Override
    public float getEffectBlendFactor(Holder<MobEffect> effect, float partialTicks) {
        if (blocked(effect)) {
            return 0;
        }
        return super.getEffectBlendFactor(effect, partialTicks);
    }

    @Unique
    private static boolean blocked(Holder<MobEffect> effect) {
        AntiBlind antiBlind = Modules.active(AntiBlind.class);
        if (antiBlind == null) {
            return false;
        }
        if (effect == MobEffects.BLINDNESS) {
            return antiBlind.blocksBlindness();
        }
        if (effect == MobEffects.DARKNESS) {
            return antiBlind.blocksDarkness();
        }
        return effect == MobEffects.NAUSEA && antiBlind.blocksNausea();
    }

    // The renderer reads the intensity a tick behind. Clearing it here is enough.
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void onTickEnd(CallbackInfo ci) {
        AntiBlind antiBlind = Modules.active(AntiBlind.class);
        if (antiBlind != null && antiBlind.blocksPortal()) {
            portalEffectIntensity = 0;
            oPortalEffectIntensity = 0;
        }
    }
}
