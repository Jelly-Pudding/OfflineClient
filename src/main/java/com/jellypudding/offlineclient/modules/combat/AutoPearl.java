package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockPlaceEvent;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Feeding;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ProjectilePath;
import com.jellypudding.offlineclient.util.ProjectilePath.Aim;
import com.jellypudding.offlineclient.util.ProjectilePath.Path;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.SafeDrop;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

// Every throw is flown first with the pearl's own maths. The server puts the thrower
// where the pearl stood at the start of the tick it struck something.
public final class AutoPearl extends Module {

    // The landing hurts the thrower by this much before resistance and feather falling.
    private static final float PEARL_DAMAGE = 5;

    // Ticks between two searches for a way out whilst none turns up.
    private static final int SEARCH_GAP = 5;

    // Ticks of flight followed before a throw counts as lost.
    private static final int FLIGHT_TICKS = 100;

    // The escape tries headings this many degrees apart and every pitch between the two in steps.
    private static final float HEADING_STEP = 15;
    private static final float HIGHEST_PITCH = -60;
    private static final float LOWEST_PITCH = 80;
    private static final float PITCH_STEP = 10;

    // A chase tries every pitch along the bearing and then a finer net round the best of them.
    private static final float COARSE_PITCH = 2;
    private static final float FINE_YAW = 3;
    private static final float FINE_YAW_STEP = 0.5f;
    private static final float FINE_PITCH = 2;
    private static final float FINE_PITCH_STEP = 0.25f;

    // Enemy pearls already judged for a chase. Each is chased once.
    private static final int MAX_JUDGED = 256;

    // Blocks of your own remembered to tell your roof from an enemy's.
    private static final int MAX_PLACED = 256;

    // Ticks a block you placed may take to show whilst the server confirms it. A lag spike can hold
    // the answer back for seconds.
    private static final int CONFIRM_TICKS = 100;

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

    // Ticks to sit still after a fruit whilst its teleport lands.
    private static final int FRUIT_SETTLE = 10;

    private record Throw(float yaw, float pitch, Vec3 landing) {
    }

    // Angles from one to another a step apart.
    private record Sweep(float from, float to, float step) {

        private static Sweep only(float angle) {
            return new Sweep(angle, angle, 1);
        }

        private int steps() {
            return Math.round((to - from) / step);
        }

        private float at(int index) {
            return from + index * step;
        }
    }

    private final BoolSetting escape = new BoolSetting("Escape",
        "Throws a pearl away from enemies when your health drops or you are boxed in.", true);
    private final NumberSetting health = new NumberSetting("Escape health",
        "Escapes once your health plus absorption drops to this many hearts whilst an enemy is near. Zero never escapes on health.",
        4, 0, 18, 0.5, " hearts").under(escape);
    private final BoolSetting whenTrapped = new BoolSetting("When trapped",
        "Escapes once a block covers your head and no side is open whilst an enemy is near. A roof you placed"
            + " yourself is left alone.", true).under(escape);
    private final NumberSetting enemyRange = new NumberSetting("Enemy range",
        "How close an enemy has to be before you escape.", 6, 1, 16, 0.5, " blocks").under(escape);
    private final NumberSetting safeDistance = new NumberSetting("Safe distance",
        "The landing has to be at least this far from every enemy.", 12, 2, 48, 1, " blocks")
        .under(escape);
    private final BoolSetting chorus = new BoolSetting("Chorus fruit",
        "Eats a chorus fruit to escape when no pearl will do.", true).under(escape);
    private final BoolSetting counter = new BoolSetting("Counter pearl",
        "Throws your own pearl to land where an enemy pearl will.", false);
    private final NumberSetting counterRange = new NumberSetting("Counter range",
        "How far away an enemy pearl may land and still be chased.", 32, 8, 64, 1, " blocks")
        .min(1).under(counter);
    private final NumberSetting landingSpread = new NumberSetting("Landing spread",
        "How far from the enemy landing your own pearl may come down.", 3, 1, 12, 0.5, " blocks")
        .min(0.5).under(counter);
    private final KeybindSetting phaseKey = new KeybindSetting("Phase key",
        "Pearls part way into the blocks beside your feet when pressed whilst the module is on.",
        KeybindSetting.UNBOUND);
    private final BoolSetting holdPhase = new BoolSetting("Hold phase",
        "Stops the game pushing you back out of those blocks until you walk away.", true)
        .under(phaseKey, phaseKey::isBound);
    private final BoolSetting fromInventory = new BoolSetting("Take from inventory",
        "Borrows a pearl or a chorus fruit from the rest of your inventory when the hotbar has none.", true);
    private final BoolSetting swing = SwingMode.useSetting(true);
    private final BoolSetting render = new BoolSetting("Show landing",
        "Draws where the last pearl will put you for two seconds.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 290).under(render);

    private final HotbarLoan loan = new HotbarLoan();
    private final Feeding feeding = new Feeding();
    private final List<Player> enemies = new ArrayList<>();
    private final Set<Integer> judged = Collections.newSetFromMap(new BoundedMap<>(MAX_JUDGED));
    // Blocks you placed in this world and the last tick each may stay missing. A roof among them is
    // your own.
    private final Map<BlockPos, Integer> placed = new BoundedMap<>(MAX_PLACED);
    private final WorldWatch world = new WorldWatch();
    private int ticks;
    private int searchTimer;
    private boolean phaseAsked;
    private Vec3 phaseSpot;
    private boolean phaseArrived;
    private int phaseWait;
    private Vec3 landing;
    private int shownTicks;
    private String status;

    public AutoPearl() {
        super("AutoPearl", "Throws ender pearls to escape a fight or chase an enemy or phase into a wall.",
            Category.COMBAT);
        addSettings(escape, health, whenTrapped, enemyRange, safeDistance, chorus, counter, counterRange,
            landingSpread, phaseKey, holdPhase, fromInventory, swing, render);
        addSettings(style.settings());
        searchTags("pearl", "escape", "phase", "clip", "chorus", "counter pearl", "chase");
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
        judged.clear();
        landing = null;
        shownTicks = 0;
        status = null;
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
        feeding.stop();
        forgetPhase();
        landing = null;
    }

    // True whilst you stand where a phase put you. The push out of blocks waits till you leave.
    public boolean holdsPhase() {
        return isEnabled() && holdPhase.isOn() && phaseArrived;
    }

    // True whilst a chorus fruit is going down. Other feeders keep their hands off the use key.
    public boolean isBusy() {
        return isEnabled() && feeding.isActive();
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (mc.gui.screen() != null || !inGame()) {
            return;
        }
        if (event.presses(phaseKey)) {
            phaseAsked = true;
        }
    }

    @Subscribe
    private void onBlockPlace(BlockPlaceEvent event) {
        syncWorld();
        placed.put(event.getPos().immutable(), ticks + CONFIRM_TICKS);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        status = null;
        if (Hands.tied()) {
            phaseAsked = false;
            forgetPhase();
            return;
        }
        ticks++;
        forgetLostBlocks();
        // A death or the pause whilst a fruit's teleport lands.
        if (feeding.waiting()) {
            phaseAsked = false;
            forgetPhase();
            return;
        }
        searchTimer = Math.max(0, searchTimer - 1);
        shownTicks = Math.max(0, shownTicks - 1);
        watchPhase();
        if (feeding.isActive()) {
            keepEating();
            return;
        }
        if (phaseAsked) {
            phaseAsked = false;
            phase();
            return;
        }
        if (escape.isOn()) {
            escape();
        }
        if (counter.isOn() && status == null && !feeding.isActive()) {
            counter();
        }
    }

    // A pearl first. A pearl cooling down is waited for and the fruit only goes when no pearl will do.
    private void escape() {
        if (EntityUtil.nearestEnemy(enemyRange.getValue()) == null) {
            return;
        }
        String reason = trigger();
        if (reason == null) {
            return;
        }
        status = reason;
        if (pearlFlying()) {
            status = "(pearl flying)";
            return;
        }
        if (searchTimer > 0 || pearlHand() != null && !pearlReady()) {
            return;
        }
        if (pearlHand() == null) {
            status = "(no pearls)";
        } else if (pearlKills()) {
            status = "(too weak)";
        } else if (throwEscape()) {
            return;
        } else {
            status = "(no way out)";
        }
        if (chorus.isOn()) {
            eatChorus();
        }
    }

    // The throw whose landing ends up furthest from the nearest enemy. False when none is safe.
    private boolean throwEscape() {
        searchTimer = SEARCH_GAP;
        collectEnemies();
        Throw best = search(new Sweep(0, 360 - HEADING_STEP, HEADING_STEP),
            new Sweep(HIGHEST_PITCH, LOWEST_PITCH, PITCH_STEP), this::escapeScore);
        if (best == null || !throwPearl(best.yaw(), best.pitch())) {
            return false;
        }
        show(best.landing());
        return true;
    }

    private String trigger() {
        if (EntityUtil.healthAtOrBelow(health.getValue())) {
            return "(low health)";
        }
        if (whenTrapped.isOn() && boxedIn()) {
            return "(trapped)";
        }
        return null;
    }

    // A block over the head keeps you from jumping and every side is shut at the feet or the head.
    // A roof you placed yourself is no trap.
    private boolean boxedIn() {
        BlockPos feet = mc.player.blockPosition();
        BlockPos roof = feet.above(2);
        if (!BlockUtil.isSolid(roof) || placed.containsKey(roof)) {
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

    // A block of yours that has shown is forgotten the first tick it is gone and leaves its spot to
    // whoever fills it next.
    private void forgetLostBlocks() {
        syncWorld();
        placed.replaceAll((spot, missingUntil) -> BlockUtil.isSolid(spot) ? ticks : missingUntil);
        placed.values().removeIf(missingUntil -> missingUntil < ticks);
    }

    // Blocks placed in another world mean nothing here.
    private void syncWorld() {
        if (world.changed()) {
            placed.clear();
        }
    }

    private void collectEnemies() {
        enemies.clear();
        for (Player player : mc.level.players()) {
            if (EntityUtil.isEnemy(player) && player.distanceTo(mc.player) <= ENEMY_SEARCH) {
                enemies.add(player);
            }
        }
    }

    // Further from the nearest enemy is better. A landing short of the safe distance is no escape.
    private double escapeScore(Vec3 settled) {
        double gap = Double.MAX_VALUE;
        for (Player enemy : enemies) {
            gap = Math.min(gap, enemy.position().distanceTo(settled));
        }
        return gap < safeDistance.getValue() ? Double.NEGATIVE_INFINITY : gap;
    }

    // The fruit can always be eaten and throws you somewhere close by at random.
    private void eatChorus() {
        if (Modules.feeding(this) || !InventoryUtil.inventoryFree()
            || mc.player.getCooldowns().isOnCooldown(Items.CHORUS_FRUIT.getDefaultInstance())) {
            return;
        }
        int slot = slotOf(Items.CHORUS_FRUIT);
        if (slot == -1) {
            return;
        }
        status = "(eating chorus)";
        feeding.begin(slot);
    }

    private void keepEating() {
        status = "(eating chorus)";
        if (mc.gui.screen() != null || !mc.player.getInventory().getSelectedItem().is(Items.CHORUS_FRUIT)) {
            feeding.stop();
            return;
        }
        if (!feeding.tick()) {
            feeding.finish(FRUIT_SETTLE, false);
        }
    }

    // Chases the first enemy pearl in sight that lands within range. A pearl is judged once
    // unless your own pearl was not ready for it.
    private void counter() {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || judged.contains(pearl.getId())) {
                continue;
            }
            Vec3 target = enemyLanding(pearl);
            if (target == null) {
                judged.add(pearl.getId());
                continue;
            }
            if (!pearlReady() || pearlFlying() || pearlKills()) {
                return;
            }
            judged.add(pearl.getId());
            Throw best = bestChase(target);
            if (best == null) {
                status = "(cannot follow)";
                return;
            }
            if (throwPearl(best.yaw(), best.pitch())) {
                show(best.landing());
            }
            return;
        }
    }

    // Where an enemy pearl puts its thrower. Null for a pearl nobody need chase or one landing
    // within the spread of where you stand. A thrower out of sight is judged by name.
    private Vec3 enemyLanding(ThrownEnderpearl pearl) {
        Entity owner = pearl.getOwner();
        if (owner != null ? !EntityUtil.isEnemy(owner) : !enemyName(EntityUtil.knownThrower(pearl))) {
            return null;
        }
        if (ProjectileUtil.inBubbles(pearl)) {
            return null;
        }
        Path flight = ProjectilePath.of(pearl, FLIGHT_TICKS);
        if (flight == null || flight.type() != HitResult.Type.BLOCK) {
            return null;
        }
        Vec3 spot = flight.lastStart();
        double away = spot.distanceTo(mc.player.position());
        return away > landingSpread.getValue() && away <= counterRange.getValue() ? spot : null;
    }

    private boolean enemyName(String name) {
        return name != null && !name.equals(EntityUtil.nameOf(mc.player))
            && !OfflineClient.INSTANCE.getFriendManager().isFriend(name);
    }

    // Every pitch along the bearing first and then a finer net round the nearest landing.
    private Throw bestChase(Vec3 target) {
        float bearing = RotationManager.yawTo(target);
        ToDoubleFunction<Vec3> nearest = settled -> -settled.distanceTo(target);
        Throw rough = search(Sweep.only(bearing), new Sweep(HIGHEST_PITCH, LOWEST_PITCH, COARSE_PITCH), nearest);
        if (rough == null) {
            return null;
        }
        double spread = landingSpread.getValue();
        return search(new Sweep(bearing - FINE_YAW, bearing + FINE_YAW, FINE_YAW_STEP),
            new Sweep(rough.pitch() - FINE_PITCH, rough.pitch() + FINE_PITCH, FINE_PITCH_STEP),
            settled -> settled.distanceTo(target) <= spread ? -settled.distanceTo(target) : Double.NEGATIVE_INFINITY);
    }

    // The throw whose safe landing scores highest. Null when none lands safely or every landing
    // is ruled out.
    private Throw search(Sweep yaws, Sweep pitches, ToDoubleFunction<Vec3> score) {
        Throw best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Vec3 origin = mc.player.position();
        for (int y = 0; y <= yaws.steps(); y++) {
            float yaw = yaws.at(y);
            for (int p = 0; p <= pitches.steps(); p++) {
                float pitch = RotationManager.clampPitch(pitches.at(p));
                Path path = ProjectilePath.fly(mc.player, ProjectilePath.THROWABLE,
                    ProjectilePath.leaveHand(mc.player, ProjectilePath.THROWABLE, origin, yaw, pitch),
                    0, FLIGHT_TICKS);
                if (path.type() != HitResult.Type.BLOCK) {
                    continue;
                }
                Vec3 settled = SafeDrop.below(mc.player, path.lastStart());
                if (settled == null) {
                    continue;
                }
                double value = score.applyAsDouble(settled);
                if (value > bestScore) {
                    bestScore = value;
                    best = new Throw(yaw, pitch, settled);
                }
            }
        }
        return best;
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
        return slotOf(Items.ENDER_PEARL) == -1 ? null : InteractionHand.MAIN_HAND;
    }

    private int slotOf(Item item) {
        int limit = fromInventory.isOn() ? InventoryUtil.WHOLE_INVENTORY : InventoryUtil.HOTBAR_SIZE;
        return InventoryUtil.findSlot(item, limit);
    }

    // The use packet carries the angle and the server throws along it.
    private boolean throwPearl(float yaw, float pitch) {
        InteractionHand hand = pearlHand();
        if (hand == null || hand == InteractionHand.MAIN_HAND && !loan.select(slotOf(Items.ENDER_PEARL))) {
            return false;
        }
        boolean thrown = RotationManager.whileFacing(yaw, pitch,
            () -> mc.gameMode.useItem(mc.player, hand).consumesAction());
        loan.giveBack();
        if (thrown) {
            SwingMode.of(swing.isOn()).use(hand);
        }
        return thrown;
    }

    private boolean pearlKills() {
        return DamageUtil.reduce(PEARL_DAMAGE, mc.player, mc.level.damageSources().enderPearl())
            >= EntityUtil.totalHealth(mc.player);
    }

    // A pearl already in the air moves you again when it lands. One of yours held in a stasis
    // chamber waits for you.
    private static boolean pearlFlying() {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof ThrownEnderpearl pearl && pearl.getOwner() == mc.player
                && !ProjectileUtil.inBubbles(pearl)) {
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
