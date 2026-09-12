package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.AirStrafingSpeedEvent;
import com.jellypudding.offlineclient.event.events.KnockbackEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.modules.combat.BowAimbot;
import com.jellypudding.offlineclient.modules.movement.AntiPush;
import com.jellypudding.offlineclient.modules.movement.Flight;
import com.jellypudding.offlineclient.modules.movement.HighJump;
import com.jellypudding.offlineclient.modules.movement.HoleSnap;
import com.jellypudding.offlineclient.modules.movement.NoKnockback;
import com.jellypudding.offlineclient.modules.movement.LongJump;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.EdgeGuard;
import com.jellypudding.offlineclient.modules.movement.Sprint;
import com.jellypudding.offlineclient.modules.movement.Step;
import com.jellypudding.offlineclient.modules.movement.VehicleFly;
import com.jellypudding.offlineclient.modules.player.AutoDrop;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.AutoGap;
import com.jellypudding.offlineclient.modules.player.FastBreak;
import com.jellypudding.offlineclient.modules.player.PortalMenus;
import com.jellypudding.offlineclient.modules.player.Reach;
import com.jellypudding.offlineclient.modules.render.AntiBlind;
import com.jellypudding.offlineclient.modules.world.AirPlace;
import com.jellypudding.offlineclient.util.Modules;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends AbstractClientPlayer {

    // The throw key never gives away an item AutoDrop is guarding.
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void onDrop(boolean whole, CallbackInfoReturnable<Boolean> cir) {
        AutoDrop autoDrop = Modules.get(AutoDrop.class);
        if (autoDrop != null && autoDrop.guards(OfflineClient.MC.player.getMainHandItem())) {
            cir.setReturnValue(false);
        }
    }

    @Shadow
    public float portalEffectIntensity;

    @Shadow
    public float oPortalEffectIntensity;

    // Never called. The compiler only wants a constructor for the parent.
    private LocalPlayerMixin(ClientLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Inject(method = "tick()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V",
            ordinal = 0))
    private void onTick(CallbackInfo ci) {
        OfflineClient.INSTANCE.getEventBus().post(TickEvent.INSTANCE);
    }

    // A mounted player never reaches sendPosition in this method.
    // Posting here on both sides of that branch keeps an aura working on a horse.
    @Inject(method = "tick()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isPassenger()Z"))
    private void onPreMotion(CallbackInfo ci) {
        OfflineClient.INSTANCE.getEventBus().post(PreMotionEvent.INSTANCE);
    }

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void onPostMotion(CallbackInfo ci) {
        OfflineClient.INSTANCE.getEventBus().post(PostMotionEvent.INSTANCE);
    }

    // Vanilla closes any screen the moment the portal effect starts. With no
    // screen in sight that branch never runs.
    @ModifyExpressionValue(method = "handlePortalTransitionEffect(Z)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;",
            ordinal = 0))
    private Screen hideScreenFromPortal(Screen original) {
        return Modules.active(PortalMenus.class) == null ? original : null;
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

    @Unique
    private static boolean offlineclient$noSlowdown() {
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if (noSlowdown != null && noSlowdown.skipsItems()) {
            return true;
        }
        BowAimbot bowAimbot = Modules.get(BowAimbot.class);
        if (bowAimbot != null && bowAimbot.suppressesSlowdown()) {
            return true;
        }
        AutoEat autoEat = Modules.get(AutoEat.class);
        if (autoEat != null && autoEat.suppressesSlowdown()) {
            return true;
        }
        AutoGap autoGap = Modules.get(AutoGap.class);
        return autoGap != null && autoGap.suppressesSlowdown();
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
        HoleSnap holeSnap = Modules.get(HoleSnap.class);
        if (holeSnap != null && holeSnap.cancelsJump()) {
            return;
        }
        super.jumpFromGround();
        LongJump longJump = Modules.get(LongJump.class);
        if (longJump != null) {
            longJump.onJump();
        }
        HighJump highJump = Modules.get(HighJump.class);
        if (highJump != null) {
            highJump.onJump();
        }
    }

    // Rage sprint answers the three vanilla sprint rules itself. Anything that
    // would normally refuse a sprint such as a wall or an item in use is ignored.
    @Inject(method = "canStartSprinting()Z", at = @At("HEAD"), cancellable = true)
    private void onCanStartSprinting(CallbackInfoReturnable<Boolean> cir) {
        Sprint sprint = Modules.get(Sprint.class);
        if (sprint != null && sprint.rage()) {
            cir.setReturnValue(!isSprinting() && sprint.rageWantsSprint((LocalPlayer) (Object) this));
        }
    }

    @Inject(method = "shouldStopRunSprinting()Z", at = @At("HEAD"), cancellable = true)
    private void onShouldStopRunSprinting(CallbackInfoReturnable<Boolean> cir) {
        Sprint sprint = Modules.get(Sprint.class);
        if (sprint != null && sprint.rage()) {
            cir.setReturnValue(!sprint.rageWantsSprint((LocalPlayer) (Object) this));
        }
    }

    @Inject(method = "shouldStopSwimSprinting()Z", at = @At("HEAD"), cancellable = true)
    private void onShouldStopSwimSprinting(CallbackInfoReturnable<Boolean> cir) {
        Sprint sprint = Modules.get(Sprint.class);
        if (sprint != null && sprint.rage()) {
            cir.setReturnValue(!sprint.rageWantsSprint((LocalPlayer) (Object) this));
        }
    }

    // The one place creative flight reads the fly speed for its push up
    // and down. Flight hands over its own vertical speed instead of scaling it.
    @WrapOperation(method = "aiStep()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Abilities;getFlyingSpeed()F"))
    private float wrapVerticalFlySpeed(Abilities abilities, Operation<Float> original) {
        Flight flight = Modules.active(Flight.class);
        return flight == null ? original.call(abilities) : flight.verticalFlySpeed();
    }

    // Crowd pushing checks this on both sides of a collision. False here
    // blocks incoming pushes and leaves outgoing ones alone.
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

    // The client predicts mining five times slower in the air. FastBreak
    // fixes the server side by rewriting packets and can raise the rate outright.
    @Override
    public float getDestroySpeed(BlockState state) {
        float speed = super.getDestroySpeed(state);
        FastBreak fastBreak = Modules.get(FastBreak.class);
        return fastBreak == null ? speed : fastBreak.adjustSpeed(state, speed);
    }

    // Honey and soul sand slow through this factor alone.
    @Override
    protected float getBlockSpeedFactor() {
        float factor = super.getBlockSpeedFactor();
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if (factor >= 1 || noSlowdown == null) {
            return factor;
        }
        boolean skipped = noSlowdown.skipsBlockFriction(level().getBlockState(blockPosition()).getBlock())
            || noSlowdown.skipsBlockFriction(
                level().getBlockState(getBlockPosBelowThatAffectsMyMovement()).getBlock());
        return skipped ? 1 : factor;
    }

    // The sneak slowdown hangs off this. Crawling keeps its own pace.
    @Inject(method = "isMovingSlowly()Z", at = @At("HEAD"), cancellable = true)
    private void onIsMovingSlowly(CallbackInfoReturnable<Boolean> cir) {
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if (noSlowdown != null && noSlowdown.skipsSneaking()) {
            cir.setReturnValue(isVisuallyCrawling());
        }
    }

    @Override
    public float getSpeed() {
        float speed = super.getSpeed();
        MobEffectInstance slowness = getEffect(MobEffects.SLOWNESS);
        NoSlowdown noSlowdown = Modules.get(NoSlowdown.class);
        if (slowness == null || noSlowdown == null) {
            return speed;
        }
        return noSlowdown.withoutSlowness(speed, slowness.getAmplifier());
    }

    // A jumping mount takes the jump key to charge its leap. VehicleFly wants it to lift.
    @Inject(method = "jumpableVehicle()Lnet/minecraft/world/entity/PlayerRideableJumping;",
        at = @At("HEAD"), cancellable = true)
    private void onJumpableVehicle(CallbackInfoReturnable<PlayerRideableJumping> cir) {
        VehicleFly vehicleFly = Modules.get(VehicleFly.class);
        if (vehicleFly != null && vehicleFly.takesJumpKey()) {
            cir.setReturnValue(null);
        }
    }

    // The charge of a horse jump. One is a full power leap.
    @Inject(method = "getJumpRidingScale()F", at = @At("HEAD"), cancellable = true)
    private void onGetJumpRidingScale(CallbackInfoReturnable<Float> cir) {
        VehicleFly vehicleFly = Modules.get(VehicleFly.class);
        if (vehicleFly != null && vehicleFly.maxesJump()) {
            cir.setReturnValue(1f);
        }
    }

    // Vanilla shoves the player out of any block they stand inside.
    @Inject(method = "moveTowardsClosestSpace(DD)V", at = @At("HEAD"), cancellable = true)
    private void onMoveTowardsClosestSpace(double x, double z, CallbackInfo ci) {
        NoKnockback noKnockback = Modules.get(NoKnockback.class);
        if (noKnockback != null && noKnockback.blocksBlockPush()) {
            ci.cancel();
        }
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
        double range = super.blockInteractionRange();
        Reach reach = Modules.get(Reach.class);
        if (reach != null) {
            range = reach.adjustBlockRange(range);
        }
        // A block placed in the air has to be reachable to build onto.
        AirPlace airPlace = Modules.active(AirPlace.class);
        return airPlace == null ? range : Math.max(range, airPlace.getRange());
    }

    @Override
    public double entityInteractionRange() {
        double vanilla = super.entityInteractionRange();
        Reach reach = Modules.get(Reach.class);
        return reach == null ? vanilla : reach.adjustEntityRange(vanilla);
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
