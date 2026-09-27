package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ProjectilePath;
import com.jellypudding.offlineclient.util.ProjectilePath.Aim;
import com.jellypudding.offlineclient.util.ProjectilePath.Path;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.SwingMode;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Every throw is flown first with the pearl's own maths. The server puts the thrower
// where the pearl stood at the start of the tick it struck something.
public final class AutoPearl extends Module {

    // The landing hurts the thrower by this much before resistance and feather falling.
    private static final float PEARL_DAMAGE = 5;

    // Ticks between two searches for a way out whilst none turns up.
    private static final int SEARCH_GAP = 5;

    // Ticks of flight followed before a throw counts as lost.
    private static final int FLIGHT_TICKS = 100;

    // The escape tries this many headings and every pitch between the two in steps.
    private static final int HEADINGS = 24;
    private static final int HIGHEST_PITCH = -60;
    private static final int LOWEST_PITCH = 80;
    private static final int PITCH_STEP = 10;

    // How far under the landing a block that burns or pricks still counts.
    private static final double GROUND_PROBE = 0.1;

    // Enemies this far off still count against a landing.
    private static final double ENEMY_SEARCH = 64;

    // A pearl ends its first tick up to this far off its aim.
    private static final double SPREAD = 0.03;

    // A phase pearl has to strike on its second tick and land where it ended the first.
    private static final int STRIKE_TICK = 2;

    // How far you may drift from the phase landing before the hold lets go.
    private static final double PHASE_SLACK = 0.1;

    // The server checks this share of your width around the eyes for suffocation.
    private static final double EYE_SHARE = 0.8;

    // Ticks the landing stays drawn and a phase waits for the teleport.
    private static final int SHOW_TICKS = 40;

    private record Throw(float yaw, float pitch, Vec3 landing) {
    }

    private final BoolSetting escape = new BoolSetting("Escape",
        "Throws a pearl away from enemies when your health drops or you are boxed in.", true);
    private final NumberSetting health = new NumberSetting("Escape health",
        "Escapes once your health plus absorption drops to this many hearts whilst an enemy is near. Zero never escapes on health.",
        4, 0, 18, 0.5, " hearts").under(escape);
    private final BoolSetting whenTrapped = new BoolSetting("When trapped",
        "Escapes once a block covers your head and no side is open whilst an enemy is near. Skipped whilst SelfTrap is on.",
        true).under(escape);
    private final NumberSetting enemyRange = new NumberSetting("Enemy range",
        "How close an enemy has to be before you escape.", 6, 1, 16, 0.5, " blocks").under(escape);
    private final NumberSetting safeDistance = new NumberSetting("Safe distance",
        "The landing has to be at least this far from every enemy.", 12, 2, 48, 1, " blocks")
        .under(escape);
    private final KeybindSetting phaseKey = new KeybindSetting("Phase key",
        "Press this whilst the module is on to pearl part way into the blocks beside your feet.",
        KeybindSetting.UNBOUND);
    private final BoolSetting holdPhase = new BoolSetting("Hold phase",
        "Stops the game pushing you back out of those blocks until you walk away.", true)
        .under(phaseKey, phaseKey::isBound);
    private final BoolSetting fromInventory = new BoolSetting("Take from inventory",
        "Borrows a pearl from the rest of your inventory when the hotbar has none.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting render = new BoolSetting("Show landing",
        "Draws where the last pearl will put you for two seconds.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 290).under(render);

    private final HotbarLoan loan = new HotbarLoan();
    private final List<Player> enemies = new ArrayList<>();
    private int searchTimer;
    private boolean phaseAsked;
    private Vec3 phaseSpot;
    private boolean phaseArrived;
    private int phaseWait;
    private Vec3 landing;
    private int shownTicks;
    private String status;

    public AutoPearl() {
        super("AutoPearl", "Throws ender pearls to escape a fight or to phase into a wall.", Category.COMBAT);
        addSettings(escape, health, whenTrapped, enemyRange, safeDistance, phaseKey, holdPhase,
            fromInventory, swing, render);
        addSettings(style.settings());
        searchTags("pearl", "escape", "phase", "clip");
    }

    @Override
    public String getSuffix() {
        return status;
    }

    @Override
    protected void onEnable() {
        searchTimer = 0;
        phaseAsked = false;
        forgetPhase();
        landing = null;
        shownTicks = 0;
        status = null;
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
        forgetPhase();
        landing = null;
    }

    // True whilst you stand where a phase put you. The push out of blocks waits till you leave.
    public boolean holdsPhase() {
        return isEnabled() && holdPhase.isOn() && phaseArrived;
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != InputConstants.PRESS || mc.gui.screen() != null || !inGame()) {
            return;
        }
        if (phaseKey.isBound() && event.getKey() == phaseKey.getValue()) {
            phaseAsked = true;
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        status = null;
        if (!inGame() || mc.player.isSpectator() || mc.player.isDeadOrDying()) {
            phaseAsked = false;
            forgetPhase();
            return;
        }
        searchTimer = Math.max(0, searchTimer - 1);
        shownTicks = Math.max(0, shownTicks - 1);
        watchPhase();
        if (phaseAsked) {
            phaseAsked = false;
            phase();
            return;
        }
        if (escape.isOn()) {
            escape();
        }
    }

    private void escape() {
        if (EntityUtil.nearestEnemy(enemyRange.getValue()) == null) {
            return;
        }
        String reason = trigger();
        if (reason == null) {
            return;
        }
        status = reason;
        if (!pearlReady() || searchTimer > 0) {
            return;
        }
        if (pearlFlying()) {
            status = "(pearl flying)";
            return;
        }
        searchTimer = SEARCH_GAP;
        if (pearlKills()) {
            status = "(too weak)";
            return;
        }
        collectEnemies();
        Throw best = bestEscape();
        if (best == null) {
            status = "(no way out)";
            return;
        }
        if (throwPearl(best.yaw(), best.pitch())) {
            show(best.landing());
        }
    }

    private String trigger() {
        if (EntityUtil.healthAtOrBelow(health.getValue())) {
            return "(low health)";
        }
        if (whenTrapped.isOn() && !Modules.enabled(SelfTrap.class) && boxedIn()) {
            return "(trapped)";
        }
        return null;
    }

    // A block over the head keeps you from jumping and every side is shut at the feet or the head.
    private boolean boxedIn() {
        BlockPos feet = mc.player.blockPosition();
        if (!BlockUtil.isSolid(feet.above(2))) {
            return false;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = feet.relative(side);
            if (!BlockUtil.isSolid(next) && !BlockUtil.isSolid(next.above())) {
                return false;
            }
        }
        return true;
    }

    private void collectEnemies() {
        enemies.clear();
        for (Player player : mc.level.players()) {
            if (EntityUtil.isEnemy(player) && player != mc.player
                && player.distanceTo(mc.player) <= ENEMY_SEARCH) {
                enemies.add(player);
            }
        }
    }

    // The throw whose landing ends up furthest from the nearest enemy.
    private Throw bestEscape() {
        Throw best = null;
        double bestGap = 0;
        Vec3 origin = mc.player.position();
        for (int heading = 0; heading < HEADINGS; heading++) {
            float yaw = heading * 360f / HEADINGS;
            for (int pitch = HIGHEST_PITCH; pitch <= LOWEST_PITCH; pitch += PITCH_STEP) {
                Path path = ProjectilePath.fly(mc.player, ProjectilePath.THROWABLE,
                    ProjectilePath.leaveHand(mc.player, ProjectilePath.THROWABLE, origin, yaw, pitch),
                    0, FLIGHT_TICKS);
                if (path.type() != HitResult.Type.BLOCK) {
                    continue;
                }
                Vec3 spot = path.lastStart();
                double gap = enemyGap(spot);
                if (gap < safeDistance.getValue() || gap <= bestGap) {
                    continue;
                }
                Vec3 settled = safeLanding(spot);
                if (settled != null) {
                    best = new Throw(yaw, pitch, settled);
                    bestGap = gap;
                }
            }
        }
        return best;
    }

    private double enemyGap(Vec3 spot) {
        double nearest = Double.MAX_VALUE;
        for (Player enemy : enemies) {
            nearest = Math.min(nearest, enemy.position().distanceTo(spot));
        }
        return nearest;
    }

    // Where you come to rest after the teleport. Null when you would not fit there or the
    // drop would hurt or the ground burns.
    private Vec3 safeLanding(Vec3 spot) {
        AABB box = mc.player.getBoundingBox().move(spot.subtract(mc.player.position()));
        if (!mc.level.noCollision(mc.player, box)) {
            return null;
        }
        Vec3 fall = Entity.collideBoundingBox(mc.player, new Vec3(0, -(DamageUtil.SAFE_FALL + 1), 0), box,
            mc.level, List.of());
        if (-fall.y > DamageUtil.SAFE_FALL) {
            return null;
        }
        AABB landed = box.move(fall);
        boolean burns = mc.level.getBlockStates(landed.expandTowards(0, -GROUND_PROBE, 0))
            .anyMatch(state -> BlockUtil.harmful(state) || state.getFluidState().is(FluidTags.LAVA));
        return burns ? null : spot.add(fall);
    }

    // Aims the first tick of flight at the edge or the corner of your block that leans
    // furthest into walls. The second tick strikes the floor and the server puts you
    // where the first tick ended with your body part way inside those walls.
    private void phase() {
        if (!mc.player.onGround()) {
            ChatUtil.error("Stand on the ground to phase.");
            return;
        }
        if (!pearlReady()) {
            ChatUtil.error("No pearl ready to throw.");
            return;
        }
        if (pearlKills()) {
            ChatUtil.error("The pearl would kill you.");
            return;
        }
        Throw best = bestPhase();
        if (best == null) {
            ChatUtil.error("No wall beside your feet to phase into.");
            return;
        }
        if (throwPearl(best.yaw(), best.pitch())) {
            show(best.landing());
            phaseSpot = best.landing();
            phaseArrived = false;
            phaseWait = SHOW_TICKS;
        }
    }

    private Throw bestPhase() {
        BlockPos feet = mc.player.blockPosition();
        Throw best = null;
        double bestDepth = 0;
        for (Direction first : Direction.Plane.HORIZONTAL) {
            if (!BlockUtil.isSolid(feet.relative(first))) {
                continue;
            }
            for (Direction second : Direction.Plane.HORIZONTAL) {
                // The same side twice aims at the middle of that edge.
                if (second.getAxis() == first.getAxis() && second != first) {
                    continue;
                }
                Throw aimed = phaseThrow(feet, towards(feet, first, second));
                double depth = aimed == null ? 0 : depthInWalls(feet, aimed.landing());
                if (depth > bestDepth) {
                    bestDepth = depth;
                    best = aimed;
                }
            }
        }
        return best;
    }

    // The point inside your block that lies against the given sides with room for the spread.
    private Vec3 towards(BlockPos feet, Direction first, Direction second) {
        double x = inside(mc.player.getX(), feet.getX());
        double z = inside(mc.player.getZ(), feet.getZ());
        for (Direction side : new Direction[] {first, second}) {
            if (side.getAxis() == Direction.Axis.X) {
                x = side.getStepX() > 0 ? feet.getX() + 1 - SPREAD : feet.getX() + SPREAD;
            } else {
                z = side.getStepZ() > 0 ? feet.getZ() + 1 - SPREAD : feet.getZ() + SPREAD;
            }
        }
        return new Vec3(x, mc.player.getY(), z);
    }

    private static double inside(double coordinate, int column) {
        return Math.clamp(coordinate, column + SPREAD, column + 1 - SPREAD);
    }

    // The throw that ends its first tick over the target and strikes on the second.
    // Null when the pearl would hit something sooner or land too low or leave you choking.
    private Throw phaseThrow(BlockPos feet, Vec3 target) {
        Aim aim = ProjectilePath.firstTickAim(mc.player, ProjectilePath.THROWABLE, target);
        if (aim == null) {
            return null;
        }
        Path path = ProjectilePath.fly(mc.player, ProjectilePath.THROWABLE,
            ProjectilePath.leaveHand(mc.player, ProjectilePath.THROWABLE, mc.player.position(),
                aim.yaw(), aim.pitch()), 0, STRIKE_TICK);
        if (path.type() != HitResult.Type.BLOCK || path.ticks() != STRIKE_TICK) {
            return null;
        }
        Vec3 spot = path.lastStart();
        if (spot.y < mc.player.getY() + SPREAD || !BlockPos.containing(spot).equals(feet)) {
            return null;
        }
        double eyes = mc.player.getBbWidth() * EYE_SHARE;
        AABB eyeBox = AABB.ofSize(spot.add(0, mc.player.getEyeHeight(), 0), eyes, 1.0E-6, eyes);
        if (mc.level.collidesWithSuffocatingBlock(mc.player, eyeBox)) {
            return null;
        }
        return new Throw(aim.yaw(), aim.pitch(), spot);
    }

    // How far your body would reach into the solid blocks beside your feet from the spot.
    private double depthInWalls(BlockPos feet, Vec3 spot) {
        double half = mc.player.getBbWidth() / 2;
        double depth = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (BlockUtil.isSolid(feet.relative(side))) {
                depth += Math.max(0, pastEdge(side, feet, spot, half));
            }
        }
        return depth;
    }

    // How far a body this wide sticks out of your block on the given side.
    private static double pastEdge(Direction side, BlockPos feet, Vec3 spot, double half) {
        return switch (side) {
            case EAST -> spot.x + half - (feet.getX() + 1);
            case WEST -> feet.getX() - (spot.x - half);
            case SOUTH -> spot.z + half - (feet.getZ() + 1);
            default -> feet.getZ() - (spot.z - half);
        };
    }

    // Keeps the push out of blocks off whilst you stay on the phase spot.
    private void watchPhase() {
        if (phaseSpot == null) {
            return;
        }
        double drift = mc.player.position().subtract(phaseSpot).horizontalDistance();
        if (drift <= PHASE_SLACK) {
            phaseArrived = true;
            return;
        }
        if (phaseArrived || --phaseWait <= 0) {
            forgetPhase();
        }
    }

    private void forgetPhase() {
        phaseSpot = null;
        phaseArrived = false;
        phaseWait = 0;
    }

    private boolean pearlReady() {
        if (pearlHand() == null) {
            status = "(no pearls)";
            return false;
        }
        if (mc.player.getCooldowns().isOnCooldown(Items.ENDER_PEARL.getDefaultInstance())) {
            status = "(cooling down)";
            return false;
        }
        return true;
    }

    // The offhand when it holds pearls and the main hand when a slot has some.
    // Null when there are none to hand.
    private InteractionHand pearlHand() {
        if (mc.player.getOffhandItem().is(Items.ENDER_PEARL)) {
            return InteractionHand.OFF_HAND;
        }
        return pearlSlot() == -1 ? null : InteractionHand.MAIN_HAND;
    }

    private int pearlSlot() {
        int limit = fromInventory.isOn() ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE;
        return InventoryUtil.findSlot(Items.ENDER_PEARL, limit);
    }

    // The use packet carries the angle and the server throws along it.
    private boolean throwPearl(float yaw, float pitch) {
        InteractionHand hand = pearlHand();
        if (hand == null || hand == InteractionHand.MAIN_HAND && !loan.select(pearlSlot())) {
            return false;
        }
        boolean thrown = RotationManager.whileFacing(yaw, pitch,
            () -> mc.gameMode.useItem(mc.player, hand).consumesAction());
        loan.giveBack();
        if (thrown) {
            swing.getValue().swing(hand);
        }
        return thrown;
    }

    private boolean pearlKills() {
        return DamageUtil.reduce(PEARL_DAMAGE, mc.player, mc.level.damageSources().enderPearl())
            >= EntityUtil.totalHealth(mc.player);
    }

    // A pearl already in the air moves you again when it lands.
    private static boolean pearlFlying() {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof ThrownEnderpearl pearl && pearl.getOwner() == mc.player) {
                return true;
            }
        }
        return false;
    }

    private void show(Vec3 spot) {
        landing = spot;
        shownTicks = SHOW_TICKS;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || landing == null || shownTicks == 0 || !inGame()) {
            return;
        }
        AABB box = mc.player.getBoundingBox().move(landing.subtract(mc.player.position()));
        style.draw(event.getBatch(), box, true);
    }
}
