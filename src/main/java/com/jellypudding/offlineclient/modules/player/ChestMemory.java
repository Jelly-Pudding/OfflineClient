package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.config.RememberedContainers;
import com.jellypudding.offlineclient.config.RememberedContainers.Spot;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.render.ContainerPreview;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Flash;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.OpenedContainers;
import com.jellypudding.offlineclient.util.PlacedBox;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ChestMemory extends Module {

    public record Hit(BlockPos pos, int count, long seen) {
    }

    private static final int OPEN_WAIT = 20;
    private static final int PEEK_GAP = 12;
    private static final int PEEK_COLUMNS = 9;
    private static final int PEEK_TEXT = 0xFFAAAAAA;

    private final BoolSetting peek = new BoolSetting("Peek on look",
        "Shows what a remembered container holds whilst your crosshair rests on it.", true);
    private final BoolSetting mark = new BoolSetting("Mark results",
        "Outlines the containers holding the item you last searched for.", true);
    private final NumberSetting markTime = new NumberSetting("Mark time",
        "Seconds the outlines stay after a search.", 30, 5, 300, 5, "s").min(1)
        .under(mark);
    private final BoxStyle markStyle = new BoxStyle("Mark", BoxStyle.Shape.BOTH, 120)
        .under(mark);
    private final BoolSetting countShulkers = new BoolSetting("Count shulkers",
        "Also counts what the shulker boxes inside each container hold.", true);
    private final ActionSetting forget = new ActionSetting("Forget containers",
        "Forgets every container remembered on this server.", this::forgetServer).confirm();

    private final RememberedContainers store = new RememberedContainers();

    // The click may come from the network thread.
    private volatile BlockPos clicked;
    private volatile int clickedMenu;
    private int waited;
    private Spot watching;
    private int watchedMenu;
    // The container whose screen shut last. It is forgotten once its block is loaded and holds no
    // container such as a box you set down and mine back.
    private Spot left;

    private List<BlockPos> marked = List.of();
    private long markedUntil;

    public ChestMemory() {
        super("ChestMemory", "Remembers what each container you open holds and finds an item among them.",
            Category.PLAYER);
        addSettings(peek, mark, markTime);
        addSettings(markStyle.settings());
        addSettings(countShulkers, forget);
        searchTags("chest search", "find items", "storage", "where is");
    }

    @Override
    protected void onDisable() {
        clicked = null;
        watching = null;
        left = null;
        marked = List.of();
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        BlockPos pos = OpenedContainers.clickedOn(event.getPacket(), ChestMemory::remembers);
        if (pos != null && mc.player != null) {
            clickedMenu = mc.player.containerMenu.containerId;
            waited = 0;
            clicked = pos;
        }
    }

    // A box set down for a moment to be packed or emptied is left out.
    private static boolean remembers(BlockEntity blockEntity) {
        return blockEntity instanceof BaseContainerBlockEntity && !PlacedBox.working(blockEntity.getBlockPos());
    }

    // The first screen of storage to open after the click shows the clicked container.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!inGame()) {
            clicked = null;
            watching = null;
            left = null;
            return;
        }
        // The file is read as you arrive. A container seen before it is in is not kept.
        store.ready();
        if (clicked != null && ++waited > OPEN_WAIT) {
            clicked = null;
        }
        AbstractContainerMenu menu = openStorage();
        if (watching != null && (menu == null || menu.containerId != watchedMenu)) {
            left = watching;
            watching = null;
        }
        if (left != null && gone(left)) {
            store.forget(left);
            left = null;
        }
        if (menu == null) {
            return;
        }
        if (watching != null) {
            store.update(watching, MenuClicks.containerItems(menu));
            return;
        }
        BlockPos pos = clicked;
        if (pos == null || InventoryUtil.openedSince(clickedMenu) != menu) {
            return;
        }
        clicked = null;
        watching = Spot.here(filedAt(pos));
        watchedMenu = menu.containerId;
        store.opened(watching, MenuClicks.containerItems(menu));
    }

    private AbstractContainerMenu openStorage() {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || InventoryUtil.isEnderChest(screen)) {
            return null;
        }
        AbstractContainerMenu menu = screen.getMenu();
        return InventoryUtil.isStorage(menu) ? menu : null;
    }

    // A double chest is filed under the same half whichever half was clicked.
    private BlockPos filedAt(BlockPos pos) {
        BlockPos other = BlockUtil.otherChestHalf(pos, mc.level.getBlockState(pos));
        return other == null || pos.compareTo(other) < 0 ? pos.immutable() : other.immutable();
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!peek.isOn() || !inGame() || mc.gui.screen() != null) {
            return;
        }
        BlockHitResult aimed = BlockUtil.aimedBlock();
        RememberedContainers.Container seen = aimed == null ? null : store.get(Spot.here(filedAt(aimed.getBlockPos())));
        if (seen == null) {
            return;
        }
        GuiGraphicsExtractor context = event.getContext();
        Font font = ClientFonts.hud();
        int x = context.guiWidth() / 2 + PEEK_GAP;
        int y = context.guiHeight() / 2 + PEEK_GAP;
        context.text(font, "Seen " + ChatUtil.ago(System.currentTimeMillis() - seen.seen()), x, y, PEEK_TEXT, true);
        new ContainerPreview(seen.items(), PEEK_COLUMNS)
            .drawAt(context, font, x, y + font.lineHeight + 1, -1, -1);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!mark.isOn() || marked.isEmpty() || !inGame()) {
            return;
        }
        float strength = Flash.strength(markedUntil);
        if (strength <= 0) {
            marked = List.of();
            return;
        }
        for (BlockPos pos : marked) {
            markStyle.drawFading(event.getBatch(), box(pos), strength, true);
        }
    }

    private AABB box(BlockPos pos) {
        AABB box = DrawBatch.blockBox(pos);
        BlockPos other = BlockUtil.otherChestHalf(pos, mc.level.getBlockState(pos));
        return other == null ? box : box.minmax(DrawBatch.blockBox(other));
    }

    public List<Hit> search(Item item) {
        List<Hit> hits = new ArrayList<>();
        for (RememberedContainers.Container container : store.here()) {
            if (gone(container.spot())) {
                store.forget(container.spot());
                continue;
            }
            int count = count(container.items(), item);
            if (count > 0) {
                hits.add(new Hit(container.spot().pos(), count, container.seen()));
            }
        }
        hits.sort(Comparator.comparingDouble(hit -> hit.pos().distToCenterSqr(mc.player.position())));
        marked = hits.stream().map(Hit::pos).toList();
        markedUntil = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(markTime.getInt());
        return hits;
    }

    public int count(List<ItemStack> stacks, Item item) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (stack.is(item)) {
                total += stack.getCount();
            } else if (countShulkers.isOn() && ItemUtil.isShulkerBox(stack.getItem())) {
                ItemContainerContents inside = stack.get(DataComponents.CONTAINER);
                if (inside != null) {
                    total += inside.nonEmptyItemCopyStream().filter(held -> held.is(item))
                        .mapToInt(ItemStack::getCount).sum();
                }
            }
        }
        return total;
    }

    private boolean gone(Spot spot) {
        BlockPos pos = spot.pos();
        return spot.dimension().equals(ServerInfo.dimension()) && mc.level.isLoaded(pos)
            && !(mc.level.getBlockEntity(pos) instanceof BaseContainerBlockEntity);
    }

    private String forgetServer() {
        if (!inGame()) {
            return "Join a world first";
        }
        store.forgetAll();
        marked = List.of();
        return "Forgot every container on this server";
    }
}
