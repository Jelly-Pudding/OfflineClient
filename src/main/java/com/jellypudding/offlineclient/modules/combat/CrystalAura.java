package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Every candidate spot is scored with the game's own explosion maths.
public final class CrystalAura extends Module {

    // How far ahead of a moving target the damage is scored.
    private static final double LEAD_TICKS = 1;

    // Ticks a placed spot stays on the own crystal list.
    private static final int OWN_MEMORY = 100;

    // Ticks between attempts to pull crystals up from the backpack.
    private static final int REFILL_DELAY = 4;

    // Ticks a crystal is left alone after a hit before it is tried again.
    private static final int ATTACK_MEMORY = 3;

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 10, 2, 16, 0.5, " blocks");
    private final NumberSetting range = new NumberSetting("Range",
        "Reach for placing and hitting crystals.", 4.5, 1, 6, 0.1);
    private final NumberSetting wallsRange = new NumberSetting("Walls range",
        "Reach for crystals hidden behind blocks.", 3.5, 0, 6, 0.1);
    private final BoolSetting doPlace = new BoolSetting("Place",
        "Place crystals near the target.", true);
    private final NumberSetting placeDelay = new NumberSetting("Place delay",
        "Ticks to wait between placements.", 2, 0, 10, 1, " ticks");
    private final BoolSetting doBreak = new BoolSetting("Break",
        "Hit placed crystals to set them off.", true);
    private final NumberSetting breakDelay = new NumberSetting("Break delay",
        "Ticks to wait between hits.", 1, 0, 10, 1, " ticks");
    private final NumberSetting minDamage = new NumberSetting("Min damage",
        "Only act when the enemy would take at least this much.", 6, 0, 20, 0.5);
    private final NumberSetting maxSelfDamage = new NumberSetting("Max self damage",
        "Never take more than this from your own crystal.", 8, 0, 20, 0.5);
    private final BoolSetting antiSuicide = new BoolSetting("Anti suicide",
        "Never touch a crystal that could kill you.", true);
    private final BoolSetting predict = new BoolSetting("Predict",
        "Score the damage where a moving target is heading.", true);
    private final BoolSetting facePlace = new BoolSetting("Finisher",
        "Drops the minimum damage once the target is nearly dead or their armour is about to give out.", true);
    private final NumberSetting facePlaceHealth = new NumberSetting("Finisher health",
        "Health plus absorption at or below this counts as nearly dead.", 8, 1, 20, 0.5)
        .under(facePlace);
    private final BoolSetting onlyOwn = new BoolSetting("Only own",
        "Only hit crystals you placed yourself.", false);
    private final BoolSetting oldPlacement = new BoolSetting("Old placement",
        "Require two air blocks above the base like older servers.", false);
    private final BoolSetting support = new BoolSetting("Support",
        "Places obsidian under the target when there is nothing to crystal. Taken from the inventory when the hotbar has none.", true);
    private final BoolSetting smartDelay = new BoolSetting("Smart delay",
        "Skip a hit whilst the target is still in damage immunity.", true);
    private final BoolSetting antiWeakness = new BoolSetting("Anti weakness",
        "Swap to a tool that still breaks crystals whilst you have weakness.", true);
    private final BoolSetting pauseOnUse = new BoolSetting("Pause on use",
        "Hold off whilst eating or drawing a bow.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward the crystal spot.", true);
    private final BoolSetting render = new BoolSetting("Show placement",
        "Outline the base block the next crystal goes on.", true);

    // Ticks that other combat modules stand aside for after a real action.
    private static final int BUSY_TICKS = 8;

    // How far around the target a support block may be laid.
    private static final int SUPPORT_REACH = 3;
    private static final int SUPPORT_RISE = 2;

    private int placeTimer;
    private int breakTimer;
    private int refillTimer;
    private int busyTimer;
    private final SlotSwap slots = new SlotSwap();

    // Obsidian borrowed from the inventory for a support block.
    private final HotbarLoan loan = new HotbarLoan();
    private BlockPos planned;
    private String targetName;
    private String status;

    // True for the tick when the target is weak enough to ignore Min damage.
    private boolean facing;

    // Everything worth looking at this tick.
    private final List<Entity> entities = new ArrayList<>();

    // Spots placed on with the ticks left before they are forgotten.
    private final Map<BlockPos, Integer> ownSpots = new HashMap<>();

    // Crystal entity ids already hit with the ticks left before a retry.
    private final Map<Integer, Integer> attacked = new HashMap<>();

    public CrystalAura() {
        super("CrystalAura", "Places end crystals near enemies and blows them up.", Category.COMBAT);
        addSettings(targetRange, range, wallsRange, doPlace, placeDelay, doBreak, breakDelay,
            minDamage, maxSelfDamage, antiSuicide, predict, facePlace, facePlaceHealth,
            onlyOwn, oldPlacement, support, smartDelay, antiWeakness, pauseOnUse,
            rotate, render);
        searchTags("end crystal", "cpvp", "ca");
    }

    @Override
    public String getSuffix() {
        return targetName == null ? null : suffix(targetName, status);
    }

    public boolean hasTarget() {
        return targetName != null;
    }

    /**
     * True only for a short window after a real place or break. Other combat
     * modules stand aside on that window.
     */
    public boolean isActing() {
        return busyTimer > 0;
    }

    @Override
    protected void onEnable() {
        placeTimer = 0;
        breakTimer = 0;
        refillTimer = 0;
        busyTimer = 0;
        slots.forget();
        planned = null;
        targetName = null;
        entities.clear();
        ownSpots.clear();
        attacked.clear();
    }

    @Override
    protected void onDisable() {
        busyTimer = 0;
        slots.restore();
        loan.giveBack();
        planned = null;
        targetName = null;
        entities.clear();
        ownSpots.clear();
        attacked.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        planned = null;
        // Dropping last tick's entities stops an unloaded world being held alive.
        entities.clear();
        if (!inGame() || mc.player.isSpectator()) {
            targetName = null;
            return;
        }
        if (placeTimer > 0) {
            placeTimer--;
        }
        if (breakTimer > 0) {
            breakTimer--;
        }
        if (refillTimer > 0) {
            refillTimer--;
        }
        if (busyTimer > 0) {
            busyTimer--;
        }
        forgetOldSpots();

        Player target = EntityUtil.nearestEnemy(targetRange.getValue());
        targetName = EntityUtil.nameOf(target);
        status = null;
        if (target == null) {
            slots.restore();
            return;
        }
        if (pauseOnUse.isOn() && mc.player.isUsingItem()) {
            slots.restore();
            status = "(paused)";
            return;
        }

        collectEntities();
        facing = facePlacing(target);

        if (doBreak.isOn() && breakTimer == 0) {
            breakBest(target);
        }
        if (doPlace.isOn() && placeTimer == 0) {
            placeBest(target);
        }
        slots.restore();
    }

    private void collectEntities() {
        entities.clear();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.isSpectator()) {
                continue;
            }
            entities.add(entity);
        }
    }

    private void breakBest(Player target) {
        // Damage during immunity drops to the difference. The crystal is wasted.
        if (smartDelay.isOn() && target.hurtTime > 0) {
            return;
        }
        EndCrystal best = null;
        float bestDamage = 0;
        for (Entity entity : entities) {
            if (!(entity instanceof EndCrystal crystal)) {
                continue;
            }
            if (attacked.containsKey(crystal.getId())) {
                continue;
            }
            if (onlyOwn.isOn() && !ownSpots.containsKey(crystal.blockPosition())) {
                continue;
            }
            Vec3 center = crystal.getBoundingBox().getCenter();
            double reach = canSee(center) ? range.getValue() : wallsRange.getValue();
            if (EntityUtil.reachDistance(mc.player, crystal) > reach) {
                continue;
            }
            if (!selfSafe(crystal.position())) {
                continue;
            }
            float damage = ExplosionUtil.crystalDamage(target, crystal.position(), lead(target));
            if (worthIt(damage) && damage > bestDamage) {
                bestDamage = damage;
                best = crystal;
            }
        }
        if (best == null) {
            return;
        }
        if (rotate.isOn() && !RotationManager.look(best.getBoundingBox().getCenter(),
            RotationPriority.AURA, RotationManager.BLOCK_TOLERANCE)) {
            status = "(turning)";
            return;
        }
        if (antiWeakness.isOn() && !armWeakHand()) {
            status = "(weakness)";
            return;
        }
        mc.player.connection.send(new ServerboundAttackPacket(best.getId()));
        mc.player.swing(InteractionHand.MAIN_HAND);
        breakTimer = breakDelay.getInt();
        busyTimer = BUSY_TICKS;
        attacked.put(best.getId(), ATTACK_MEMORY);
    }

    private void placeBest(Player target) {
        BlockPos base = bestBase(target);
        planned = base;
        if (base == null) {
            if (support.isOn()) {
                placeSupport(target);
            } else {
                status = "(no safe spot)";
            }
            return;
        }
        InteractionHand hand = crystalHand();
        if (hand == null) {
            status = "(no crystals)";
            return;
        }
        Vec3 hit = Vec3.atCenterOf(base).add(0, 0.5, 0);
        if (rotate.isOn() && !RotationManager.look(hit, RotationPriority.AURA,
            RotationManager.BLOCK_TOLERANCE)) {
            status = "(turning)";
            return;
        }
        BlockHitResult result = new BlockHitResult(hit, Direction.UP, base, false);
        if (mc.gameMode.useItemOn(mc.player, hand, result).consumesAction()) {
            mc.player.swing(hand);
            ownSpots.put(base.above().immutable(), OWN_MEMORY);
            placeTimer = placeDelay.getInt();
            busyTimer = BUSY_TICKS;
        }
    }

    private BlockPos bestBase(Player target) {
        BlockPos feet = target.blockPosition();
        BlockPos best = null;
        float bestDamage = 0;
        Vec3 eye = mc.player.getEyePosition();
        double furthest = Math.max(range.getValue(), wallsRange.getValue());
        int r = (int) Math.ceil(furthest);
        for (BlockPos base : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
            BlockPos above = base.above();
            Vec3 crystalPos = Vec3.atBottomCenterOf(above);
            if (eye.distanceTo(crystalPos) > furthest) {
                continue;
            }
            if (!BlockUtil.state(above).isAir()) {
                continue;
            }
            Block block = BlockUtil.state(base).getBlock();
            if (block != Blocks.OBSIDIAN && block != Blocks.BEDROCK) {
                continue;
            }
            if (oldPlacement.isOn() && !BlockUtil.state(above.above()).isAir()) {
                continue;
            }
            double reach = canSee(crystalPos) ? range.getValue() : wallsRange.getValue();
            if (eye.distanceTo(crystalPos) > reach) {
                continue;
            }

            // The crystal model needs its whole space free of entities.
            AABB space = new AABB(above.getX(), above.getY(), above.getZ(),
                above.getX() + 1, above.getY() + 2, above.getZ() + 1);
            if (entityBlocks(space)) {
                continue;
            }
            if (!selfSafe(crystalPos)) {
                continue;
            }
            float damage = ExplosionUtil.crystalDamage(target, crystalPos, lead(target));
            if (worthIt(damage) && damage > bestDamage) {
                bestDamage = damage;
                best = base.immutable();
            }
        }
        return best;
    }

    // How far the target travels before the blast lands. Scores the hit where
    // the target is heading.
    private Vec3 lead(Player target) {
        return predict.isOn() ? EntityUtil.velocityOf(target).scale(LEAD_TICKS) : Vec3.ZERO;
    }

    private boolean worthIt(float damage) {
        return damage > 0 && (facing || damage >= minDamage.getFloat());
    }

    // True when the target is nearly dead or their armour is falling apart.
    private boolean facePlacing(Player target) {
        if (!facePlace.isOn()) {
            return false;
        }
        if (EntityUtil.totalHealth(target) <= facePlaceHealth.getFloat()) {
            return true;
        }
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            ItemStack piece = target.getItemBySlot(slot);
            if (piece.isEmpty() || !piece.isDamageableItem()) {
                continue;
            }
            int left = piece.getMaxDamage() - piece.getDamageValue();
            if (left < piece.getMaxDamage() * 0.15f) {
                return true;
            }
        }
        return false;
    }

    private void forgetOldSpots() {
        ownSpots.entrySet().removeIf(entry -> {
            entry.setValue(entry.getValue() - 1);
            return entry.getValue() <= 0;
        });
        attacked.entrySet().removeIf(entry -> {
            entry.setValue(entry.getValue() - 1);
            return entry.getValue() <= 0;
        });
    }

    /**
     * Puts something with attack damage in hand when weakness has taken it to
     * zero. The server refuses the whole attack at zero and the crystal survives.
     */
    private boolean armWeakHand() {
        if (mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE) > 0) {
            return true;
        }
        ItemStack held = mc.player.getInventory().getSelectedItem();
        double heldBonus = ItemUtil.attributeValue(held, Attributes.ATTACK_DAMAGE,
            EquipmentSlot.MAINHAND);
        double base = mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE) - heldBonus;

        int bestSlot = -1;
        double bestBonus = 0;
        for (int i = 0; i < InventoryUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            double bonus = ItemUtil.attributeValue(stack, Attributes.ATTACK_DAMAGE,
                EquipmentSlot.MAINHAND);
            if (bonus > bestBonus) {
                bestBonus = bonus;
                bestSlot = i;
            }
        }
        if (bestSlot == -1 || base + bestBonus <= 0) {
            return false;
        }
        slots.select(bestSlot);
        return true;
    }

    private boolean selfSafe(Vec3 source) {
        return ExplosionUtil.selfSafe(source, ExplosionUtil.CRYSTAL_POWER,
            maxSelfDamage.getFloat(), antiSuicide.isOn());
    }

    private boolean canSee(Vec3 point) {
        BlockHitResult hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), point,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() != HitResult.Type.BLOCK;
    }

    private boolean entityBlocks(AABB space) {
        for (Entity entity : entities) {
            if (entity.getBoundingBox().intersects(space)) {
                return true;
            }
        }
        return false;
    }

    // The hand holding a crystal. Switches the hotbar over if needed.
    private InteractionHand crystalHand() {
        if (mc.player.getOffhandItem().is(Items.END_CRYSTAL)) {
            return InteractionHand.OFF_HAND;
        }
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.END_CRYSTAL));
        if (slot != -1) {
            slots.select(slot);
            return InteractionHand.MAIN_HAND;
        }
        refillCrystals();
        return null;
    }

    private void refillCrystals() {
        if (refillTimer > 0 || mc.player.containerMenu != mc.player.inventoryMenu) {
            return;
        }
        int free = InventoryUtil.freeHotbarSlot();
        if (free == -1) {
            return;
        }
        for (int i = InventoryUtil.HOTBAR_SIZE; i < InventoryUtil.WHOLE_INVENTORY; i++) {
            if (!mc.player.getInventory().getItem(i).is(Items.END_CRYSTAL)) {
                continue;
            }
            refillTimer = REFILL_DELAY;
            InventoryUtil.swap(InventoryUtil.networkSlot(i), InventoryUtil.networkSlot(free));
            return;
        }
    }

    /**
     * Places an obsidian block from the hotbar on the best open spot.
     * True whenever the support has taken this tick.
     */
    private boolean placeSupport(Player target) {
        int slot = InventoryUtil.findSlot(Items.OBSIDIAN, InventoryUtil.WHOLE_INVENTORY);
        if (slot == -1) {
            status = "(no obsidian)";
            return false;
        }

        BlockPos feet = target.blockPosition();
        BlockPos best = null;
        float bestDamage = 0;
        BlockPos low = feet.offset(-SUPPORT_REACH, -SUPPORT_RISE, -SUPPORT_REACH);
        BlockPos high = feet.offset(SUPPORT_REACH, SUPPORT_RISE, SUPPORT_REACH);
        for (BlockPos pos : BlockPos.betweenClosed(low, high)) {
            if (!BlockUtil.isReplaceable(pos) || !BlockUtil.state(pos.above()).isAir()) {
                continue;
            }
            if (BlockUtil.intersectsPlayer(pos) || entityBlocks(new AABB(pos))) {
                continue;
            }
            Vec3 crystalPos = Vec3.atBottomCenterOf(pos.above());
            if (mc.player.getEyePosition().distanceTo(crystalPos) > range.getValue()) {
                continue;
            }
            if (!selfSafe(crystalPos)) {
                continue;
            }
            float damage = ExplosionUtil.crystalDamage(target, crystalPos, lead(target));
            if (worthIt(damage) && damage > bestDamage) {
                bestDamage = damage;
                best = pos.immutable();
            }
        }
        if (best == null) {
            return false;
        }

        if (rotate.isOn() && !RotationManager.look(Vec3.atCenterOf(best), RotationPriority.AURA,
            RotationManager.BLOCK_TOLERANCE)) {
            status = "(turning)";
            return true;
        }

        if (slot < InventoryUtil.HOTBAR_SIZE) {
            slots.select(slot);
        } else if (!loan.select(slot)) {
            status = "(no room for obsidian)";
            return true;
        }
        boolean placed = BlockUtil.placeAny(best, false, true);
        if (placed) {
            status = "(placing support)";
            placeTimer = placeDelay.getInt();
            busyTimer = BUSY_TICKS;
        }
        return placed;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || planned == null) {
            return;
        }
        event.getBatch().outlineBlock(planned, 0xFFB040FF, false);
        event.getBatch().outlineBox(new AABB(planned.above()).inflate(-0.2, 0, -0.2), 0x80B040FF, false);
    }
}
