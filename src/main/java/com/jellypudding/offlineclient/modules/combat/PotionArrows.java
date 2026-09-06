package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.Swap;
import com.jellypudding.offlineclient.util.RotationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Draws the bow straight up just far enough for the arrow to fall back on your head.
public final class PotionArrows extends Module {

    private static final double HEART = 2;

    // The weakest draw that still fires. The arrow barely leaves the bow.
    private static final float RELEASE_POWER = 0.12f;
    private static final float STRAIGHT_UP = -90;

    private final RegistryListSetting<MobEffect> effects = new RegistryListSetting<>("Effects",
        "Effects to shoot yourself with in this order.", BuiltInRegistries.MOB_EFFECT,
        List.of(MobEffects.STRENGTH.value()));
    private final NumberSetting cooldown = new NumberSetting("Cooldown",
        "Ticks to wait between shots.", 10, 0, 40, 1, " ticks");
    private final BoolSetting skipActive = new BoolSetting("Skip active",
        "Leaves out effects you already have.", true);
    private final BoolSetting silentBow = new BoolSetting("Silent bow",
        "Borrows a bow from the main inventory when none is on the hotbar.", true);
    private final BoolSetting chatInfo = new BoolSetting("Chat info",
        "Says in chat why it stopped.", false);
    private final BoolSetting onlyInHoles = new BoolSetting("Only in holes",
        "Only shoots whilst you stand in a hole.", true);
    private final BoolSetting onlyOnGround = new BoolSetting("Only on ground",
        "Only shoots whilst you stand on the ground.", true);
    private final NumberSetting minHealth = new NumberSetting("Min health",
        "Stops once your health plus absorption drops under this many hearts.",
        5, 0, 18, 0.5, " hearts");

    private final InventoryUtil.HotbarLoan bow = new InventoryUtil.HotbarLoan();

    // Inventory indexes of the arrow stacks still to shoot.
    private final Deque<Integer> arrows = new ArrayDeque<>();

    // Network slots swapped so the next arrow is the one the bow reads first.
    private int movedFrom = -1;
    private int movedTo = -1;
    private boolean drawing;
    private int timer;

    public PotionArrows() {
        super("PotionArrows", "Shoots tipped arrows into yourself to take their effects.",
            Category.COMBAT);
        addSettings(effects, cooldown, skipActive, silentBow, chatInfo, onlyInHoles, onlyOnGround,
            minHealth);
        searchTags("quiver", "tipped arrow", "self shoot");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return count(arrows.size());
    }

    @Override
    protected void onEnable() {
        arrows.clear();
        movedFrom = -1;
        movedTo = -1;
        drawing = false;
        timer = 0;
        bow.forget();
        if (!inGame() || mc.player.isSpectator()) {
            setEnabled(false);
            return;
        }
        if (!allowed()) {
            return;
        }
        int slot = findBow();
        if (slot == -1) {
            stop("No usable bow.");
            return;
        }
        // A held bow keeps its draw and would fire the wrong arrow.
        InputUtil.release(mc.options.keyUse);
        mc.gameMode.releaseUsingItem(mc.player);
        if (!bow.select(slot)) {
            stop("Could not pick up the bow.");
            return;
        }
        collectArrows();
        if (arrows.isEmpty()) {
            stop("No tipped arrows for the chosen effects.");
        }
    }

    @Override
    protected void onDisable() {
        if (!inGame()) {
            return;
        }
        if (drawing) {
            InputUtil.release(mc.options.keyUse);
            mc.gameMode.releaseUsingItem(mc.player);
            drawing = false;
        }
        restoreArrow();
        bow.giveBack();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || !allowed()) {
            return;
        }
        if (arrows.isEmpty()) {
            setEnabled(false);
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        if (!drawing) {
            startDraw();
        } else if (BowItem.getPowerForTime(mc.player.getTicksUsingItem()) >= RELEASE_POWER) {
            fire();
        }
    }

    private void startDraw() {
        if (!bow.stillMine() && !bow.select(findBow())) {
            stop("Lost the bow.");
            return;
        }
        if (!bringForward(arrows.peekFirst())) {
            return;
        }
        InputUtil.hold(mc.options.keyUse);
        drawing = true;
    }

    // The look packet goes out on its own so the release lands whilst the server
    // still believes the bow points at the sky.
    private void fire() {
        mc.player.connection.send(new ServerboundMovePlayerPacket.Rot(
            RotationManager.serverYaw(), STRAIGHT_UP, mc.player.onGround(),
            mc.player.horizontalCollision));
        InputUtil.release(mc.options.keyUse);
        mc.gameMode.releaseUsingItem(mc.player);
        drawing = false;
        restoreArrow();
        arrows.pollFirst();
        timer = cooldown.getInt();
    }

    // Every check that ends the module. The reason goes to chat when asked for.
    private boolean allowed() {
        if (!headroom()) {
            return stop("No room above your head for the arrow.");
        }
        if (EntityUtil.totalHealth(mc.player) < minHealth.getValue() * HEART) {
            return stop("Health is too low.");
        }
        if (onlyOnGround.isOn() && !mc.player.onGround()) {
            return stop("Not on the ground.");
        }
        if (onlyInHoles.isOn() && !BlockUtil.playerInHole()) {
            return stop("Not in a hole.");
        }
        return true;
    }

    private boolean stop(String reason) {
        if (chatInfo.isOn()) {
            ChatUtil.error("PotionArrows stopped. " + reason);
        }
        setEnabled(false);
        return false;
    }

    // The arrow needs the two blocks above the feet clear to rise and fall back.
    private boolean headroom() {
        BlockPos feet = mc.player.blockPosition();
        return BlockUtil.state(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty()
            && BlockUtil.state(feet.above(2)).getCollisionShape(mc.level, feet.above(2)).isEmpty();
    }

    // Inventory index of a bow the settings allow. Minus one when there is none.
    private int findBow() {
        int hotbar = InventoryUtil.findSlot(Items.BOW, InventoryUtil.HOTBAR_SIZE);
        if (hotbar != -1 || !silentBow.isOn()) {
            return hotbar;
        }
        return InventoryUtil.findSlot(Items.BOW, InventoryUtil.WHOLE_INVENTORY);
    }

    // One arrow stack per wanted effect in the order the list gives them.
    private void collectArrows() {
        Set<Integer> taken = new HashSet<>();
        for (Identifier id : effects.getValue()) {
            if (!BuiltInRegistries.MOB_EFFECT.containsKey(id)) {
                continue;
            }
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.getValue(id);
            if (skipActive.isOn() && hasEffect(effect)) {
                continue;
            }
            for (int i = 0; i < InventoryUtil.WHOLE_INVENTORY; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (!taken.contains(i) && stack.is(Items.TIPPED_ARROW) && carries(stack, effect)) {
                    taken.add(i);
                    arrows.addLast(i);
                    break;
                }
            }
        }
    }

    private boolean hasEffect(MobEffect effect) {
        for (MobEffectInstance active : mc.player.getActiveEffects()) {
            if (active.getEffect().value() == effect) {
                return true;
            }
        }
        return false;
    }

    private static boolean carries(ItemStack stack, MobEffect effect) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return false;
        }
        for (MobEffectInstance instance : contents.getAllEffects()) {
            if (instance.getEffect().value() == effect) {
                return true;
            }
        }
        return false;
    }

    // Swaps the arrow into the slot the bow reads first. False whilst the click cannot land.
    private boolean bringForward(int index) {
        if (!mc.player.getInventory().getItem(index).is(Items.TIPPED_ARROW)) {
            // The stack was moved or used up since the list was built.
            arrows.pollFirst();
            return false;
        }
        int first = firstArrowSlot();
        int wanted = InventoryUtil.networkSlot(index);
        if (first == -1 || first == wanted) {
            return true;
        }
        if (!InventoryUtil.canClick() || !InventoryUtil.carried().isEmpty()) {
            return false;
        }
        if (InventoryUtil.swap(wanted, first) != Swap.DONE) {
            return false;
        }
        movedFrom = wanted;
        movedTo = first;
        return true;
    }

    // Puts the two swapped stacks back where they were. A shot that used up the
    // whole stack leaves the front slot empty so the other stack goes home on its own.
    private void restoreArrow() {
        if (movedFrom == -1) {
            return;
        }
        if (InventoryUtil.canClick() && InventoryUtil.carried().isEmpty()
            && InventoryUtil.swap(movedTo, movedFrom) == Swap.REFUSED) {
            InventoryUtil.swap(movedFrom, movedTo);
        }
        movedFrom = -1;
        movedTo = -1;
    }

    // The network slot of the arrow the bow would fire. The offhand beats everything.
    private int firstArrowSlot() {
        if (isArrow(mc.player.getOffhandItem())) {
            return InventoryUtil.OFFHAND_SLOT;
        }
        int slot = InventoryUtil.findSlot(PotionArrows::isArrow, InventoryUtil.WHOLE_INVENTORY);
        return slot == -1 ? -1 : InventoryUtil.networkSlot(slot);
    }

    private static boolean isArrow(ItemStack stack) {
        return stack.is(Items.ARROW) || stack.is(Items.TIPPED_ARROW)
            || stack.is(Items.SPECTRAL_ARROW);
    }
}
