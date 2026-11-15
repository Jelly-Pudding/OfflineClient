package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.AirStrafingSpeedEvent;
import com.jellypudding.offlineclient.event.events.KnockbackEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.mixinterface.ISprintReport;
import com.jellypudding.offlineclient.modules.combat.AutoPearl;
import com.jellypudding.offlineclient.modules.movement.AntiPush;
import com.jellypudding.offlineclient.modules.movement.Flight;
import com.jellypudding.offlineclient.modules.movement.HighJump;
import com.jellypudding.offlineclient.modules.movement.HoleSnap;
import com.jellypudding.offlineclient.modules.movement.NoClip;
import com.jellypudding.offlineclient.modules.movement.NoKnockback;
import com.jellypudding.offlineclient.modules.movement.LongJump;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.EdgeGuard;
import com.jellypudding.offlineclient.modules.movement.Sprint;
import com.jellypudding.offlineclient.modules.movement.Step;
import com.jellypudding.offlineclient.modules.movement.VehicleFly;
import com.jellypudding.offlineclient.modules.player.FastBreak;
import com.jellypudding.offlineclient.modules.player.Ghost;
import com.jellypudding.offlineclient.modules.player.LiquidInteract;
import com.jellypudding.offlineclient.modules.player.NoMiningTrace;
import com.jellypudding.offlineclient.modules.player.PortalMenus;
import com.jellypudding.offlineclient.modules.player.Reach;
import com.jellypudding.offlineclient.modules.render.AntiBlind;
import com.jellypudding.offlineclient.modules.world.AirPlace;
import com.jellypudding.offlineclient.util.AwayView;
import com.jellypudding.offlineclient.util.CrosshairClip;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends AbstractClientPlayer implements ISprintReport {

    @Shadow
    public float portalEffectIntensity;

    @Shadow
    public float oPortalEffectIntensity;

    @Shadow
    private boolean wasSprinting;

    // The tick of the last jump. A body that has never jumped holds no tick it could reach.
    @Unique
    private int offlineclient$jumpTick = Integer.MIN_VALUE;

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

    // Before the branch that skips the position packet for a passenger. Posting
    // on both sides of it keeps an aura working on a horse.
    @Inject(method = "sendChanges()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isPassenger()Z"))
    private void onPreMotion(CallbackInfo ci) {
        OfflineClient.INSTANCE.getEventBus().post(PreMotionEvent.INSTANCE);
    }

    @Inject(method = "sendChanges()V", at = @At("TAIL"))
    private void onPostMotion(CallbackInfo ci) {
        OfflineClient.INSTANCE.getEventBus().post(PostMotionEvent.INSTANCE);
    }

    @Override
    public boolean offlineclient$toldSprinting() {
        return wasSprinting;
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

    // The sprint start and the double tap sprint timer ask this.
    @Inject(method = "isSlowDueToUsingItem()Z", at = @At("HEAD"), cancellable = true)
    private void onSlowDueToUsingItem(CallbackInfoReturnable<Boolean> cir) {
        if (Modules.itemsKeepSpeed()) {
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(
        method = "modifyInput(Lnet/minecraft/world/phys/Vec2;)Lnet/minecraft/world/phys/Vec2;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z",
            ordinal = 0))
    private boolean wrapModifyInputItemUse(LocalPlayer instance, Operation<Boolean> original) {
        if (Modules.itemsKeepSpeed()) {
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
        HighJump highJump = Modules.get(HighJump.class);
        return super.getJumpPower() + (highJump == null ? 0f : highJump.getAdditionalJumpMotion());
    }

    // Every jump goes through here. A second jump in one tick would add the sprint push twice
    // and the game jumps on its own whilst the key is held.
    @Override
    public void jumpFromGround() {
        if (tickCount == offlineclient$jumpTick) {
            return;
        }
        HoleSnap holeSnap = Modules.get(HoleSnap.class);
        if (holeSnap != null && holeSnap.cancelsJump()) {
            return;
        }
        super.jumpFromGround();
        offlineclient$jumpTick = tickCount;
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
    private void onMoveTowardsClosestSpace(CallbackInfo ci) {
        NoKnockback noKnockback = Modules.get(NoKnockback.class);
        AutoPearl autoPearl = Modules.get(AutoPearl.class);
        NoClip noClip = Modules.get(NoClip.class);
        if (noKnockback != null && noKnockback.blocksBlockPush()
            || autoPearl != null && autoPearl.holdsPhase()
            || noClip != null && noClip.holdsInBlock()) {
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
        if (offlineclient$blocked(effect)) {
            return false;
        }
        return super.hasEffect(effect);
    }

    // The pulsing darkness dimming and the nausea spin read this instead of hasEffect.
    @Override
    public float getEffectBlendFactor(Holder<MobEffect> effect, float partialTicks) {
        if (offlineclient$blocked(effect)) {
            return 0;
        }
        return super.getEffectBlendFactor(effect, partialTicks);
    }

    @Unique
    private static boolean offlineclient$blocked(Holder<MobEffect> effect) {
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


    // A view that is away can swap the whole ray for one cast from the camera.
    @Inject(method = "raycastHitResult(FLnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;",
        at = @At("HEAD"),
        cancellable = true)
    private void onPickFromCamera(float partialTicks, Entity camera,
                                  CallbackInfoReturnable<HitResult> cir) {
        AwayView view = AwayView.active();
        if (view != null && view.interactsFromCamera()) {
            cir.setReturnValue(view.pick((LocalPlayer) (Object) this, partialTicks));
        }
    }

    // Runs once per frame after vanilla has picked.
    @Inject(method = "raycastHitResult(FLnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;",
        at = @At("RETURN"),
        cancellable = true)
    private void onRaycastHitResult(float partialTicks, Entity camera,
                                    CallbackInfoReturnable<HitResult> cir) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        HitResult result = cir.getReturnValue();
        NoMiningTrace noTrace = Modules.get(NoMiningTrace.class);
        if (noTrace != null && result instanceof EntityHitResult hit && noTrace.ignores(hit.getEntity())) {
            result = offlineclient$clip(player, camera, partialTicks, ClipContext.Fluid.NONE);
            cir.setReturnValue(result);
        }

        LiquidInteract liquid = Modules.active(LiquidInteract.class);
        if (liquid == null) {
            return;
        }
        BlockHitResult fluidHit = offlineclient$clip(player, camera, partialTicks,
            liquid.includesFlowing() ? ClipContext.Fluid.ANY : ClipContext.Fluid.SOURCE_ONLY);
        if (fluidHit.getType() == HitResult.Type.MISS) {
            return;
        }
        Vec3 eye = camera.getEyePosition(partialTicks);
        if (result.getType() == HitResult.Type.MISS
            || fluidHit.getLocation().distanceToSqr(eye) < result.getLocation().distanceToSqr(eye)) {
            cir.setReturnValue(fluidHit);
        }
    }

    // The game takes a dead player out of the world a second after the death and nothing walks a
    // body that is gone. Ghost keeps yours in.
    @WrapOperation(method = "tickDeath()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))
    private void onDeathRemoval(LocalPlayer player, Entity.RemovalReason reason, Operation<Void> original) {
        Ghost ghost = Modules.get(Ghost.class);
        if (ghost == null || !ghost.keepsBody()) {
            original.call(player, reason);
        }
    }

    // The same block ray vanilla casts with a chosen liquid rule.
    @Unique
    private static BlockHitResult offlineclient$clip(LocalPlayer player, Entity camera,
                                                     float partialTicks, ClipContext.Fluid fluid) {
        double range = player.blockInteractionRange();
        Vec3 eye = camera.getEyePosition(partialTicks);
        Vec3 end = eye.add(camera.getViewVector(partialTicks).scale(range));
        return player.level().clip(new CrosshairClip(eye, end, fluid, player));
    }

    // The crosshair casts its block ray as Entity.pick does but with the shapes CrosshairClip hands
    // out. Entities are then picked up to wherever it ends.
    @WrapOperation(method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"))
    private static HitResult onPickBlock(Entity camera, double range, float partialTicks, boolean fluids,
                                         Operation<HitResult> original) {
        Vec3 eye = camera.getEyePosition(partialTicks);
        Vec3 end = eye.add(camera.getViewVector(partialTicks).scale(range));
        return camera.level().clip(new CrosshairClip(eye, end,
            fluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, camera));
    }
}
