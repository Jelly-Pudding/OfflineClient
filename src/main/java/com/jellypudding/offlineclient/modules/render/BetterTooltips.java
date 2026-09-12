package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.gui.PeekScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BannerPreview;
import com.jellypudding.offlineclient.render.BookPreview;
import com.jellypudding.offlineclient.render.ContainerPreview;
import com.jellypudding.offlineclient.render.EntityPreview;
import com.jellypudding.offlineclient.render.MapPreview;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientBundleTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.core.HolderSet;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.Bucketable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

// GuiGraphicsExtractorMixin remembers the hovered stack and asks for the extra
// tooltip rows. The container and tooltip display mixins ask what to hide.
public final class BetterTooltips extends Module {

    public enum When { KEY_HELD, ALWAYS }

    public enum OpenWith { MIDDLE_CLICK, KEY }

    public enum SizeUnit { BYTES, KILOBYTES, MEGABYTES, AUTO }

    private static final int SHULKER_SLOTS = 27;
    private static final int ENDER_CHEST_SLOTS = 27;
    private static final int COLUMNS = 9;
    private static final int COMPACT_ROWS = 5;
    private static final int KILOBYTE = 1024;
    private static final int MEGABYTE = KILOBYTE * KILOBYTE;
    private static final int ENDER_TINT = 0xC0003232;
    private static final int PREVIEW_ALPHA = 0xC0;
    private static final float TINT_SHADE = 0.45f;

    private final EnumSetting<When> show = new EnumSetting<>("Show previews",
        "When the picture previews appear in a tooltip.", When.KEY_HELD)
        .describe(When.KEY_HELD, "Only whilst the preview key is held.")
        .describe(When.ALWAYS, "On every tooltip.");
    private final KeybindSetting previewKey = new KeybindSetting("Preview key",
        "The key to hold for a preview.", GLFW.GLFW_KEY_LEFT_ALT)
        .under(show, When.KEY_HELD);
    private final BoolSetting containers = new BoolSetting("Containers",
        "Hover a shulker box in any inventory to see what is packed inside it.", true);
    private final BoolSetting compactList = new BoolSetting("Compact list",
        "Replace the vanilla shulker item lines with the five biggest stacks by count.", true)
        .under(containers);
    private final BoolSetting enderChest = new BoolSetting("Ender chest",
        "Hover an ender chest item to see what yours held when you last opened one.", true);
    private final BoolSetting maps = new BoolSetting("Maps",
        "Hover a filled map to see the map itself.", true);
    private final NumberSetting mapSize = new NumberSetting("Map size",
        "How big the map preview is drawn.", 128, 64, 256, 8, " px").min(16).max(512)
        .under(maps);
    private final BoolSetting books = new BoolSetting("Books",
        "Hover a book to read its first page.", true);
    private final BoolSetting banners = new BoolSetting("Banners",
        "Hover a banner or pattern or shield to see the flag it makes.", true);
    private final BoolSetting buckets = new BoolSetting("Bucket mobs",
        "Hover a bucket with a fish or axolotl inside to see the creature.", true);
    private final BoolSetting bundles = new BoolSetting("Bundles",
        "Hover a bundle to see every item inside it rather than the vanilla few.", true);
    private final BoolSetting foodInfo = new BoolSetting("Food info",
        "Add a line with the hunger and saturation of food.", true);
    private final BoolSetting effects = new BoolSetting("Effects",
        "List the potion effects a food or stew gives with their length.", true);
    private final BoolSetting byteSize = new BoolSetting("Byte size",
        "Add a line with how many bytes the item takes up.", true);
    private final EnumSetting<SizeUnit> sizeUnit = new EnumSetting<>("Size unit",
        "The unit the byte size is written in.", SizeUnit.AUTO)
        .describe(SizeUnit.AUTO, "Whichever unit fits the number best.")
        .under(byteSize);
    private final BoolSetting hiddenTooltips = new BoolSetting("Show hidden tooltips",
        "Show a tooltip even when the item asks for it to be hidden.", false);
    private final BoolSetting hiddenLines = new BoolSetting("Show hidden lines",
        "Show lines the item hides such as enchantments and lore.", false);
    private final BoolSetting openContents = new BoolSetting("Open contents",
        "Click a container or book in an inventory to open a window showing what is inside.", true);
    private final EnumSetting<OpenWith> openWith = new EnumSetting<>("Open with",
        "What opens the window.", OpenWith.MIDDLE_CLICK)
        .describe(OpenWith.MIDDLE_CLICK, "The middle mouse button.")
        .describe(OpenWith.KEY, "The key picked below.")
        .under(openContents);
    private final KeybindSetting openKey = new KeybindSetting("Open key",
        "The key that opens the window.", GLFW.GLFW_KEY_P)
        .under(openWith, OpenWith.KEY);
    private final BoolSetting pauseInCreative = new BoolSetting("Pause in creative",
        "Leave the click alone in creative so middle click still clones the item.", true)
        .under(openContents);

    // What the ender chest held the last time it was open this session.
    private final List<ItemStack> remembered = new ArrayList<>();
    private ItemStack hovered = ItemStack.EMPTY;

    public BetterTooltips() {
        super("BetterTooltips", "Previews and extra facts in item tooltips.", Category.RENDER);
        addSettings(show, previewKey, containers, compactList, enderChest, maps, mapSize, books, banners,
            buckets, bundles, foodInfo, effects, byteSize, sizeUnit, hiddenTooltips, hiddenLines,
            openContents, openWith, openKey, pauseInCreative);
        searchTags("tooltip", "shulker", "preview", "peek");
    }

    @Override
    protected void onDisable() {
        hovered = ItemStack.EMPTY;
        // The copy belongs to the server it was taken on.
        remembered.clear();
    }

    // Called from the mixin whilst the game builds an item tooltip.
    public void setHovered(ItemStack stack) {
        hovered = stack;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level == null) {
            remembered.clear();
            return;
        }
        if (!enderChest.isOn() || !(mc.gui.screen() instanceof ContainerScreen screen)) {
            return;
        }
        if (!screen.getTitle().getString().equals(Blocks.ENDER_CHEST.getName().getString())) {
            return;
        }
        ChestMenu menu = screen.getMenu();
        Container container = menu.getContainer();
        if (container.getContainerSize() < ENDER_CHEST_SLOTS) {
            return;
        }
        remembered.clear();
        for (int i = 0; i < ENDER_CHEST_SLOTS; i++) {
            remembered.add(container.getItem(i).copy());
        }
    }

    // The tooltip rows with our lines and preview worked in. The hovered stack is used up.
    public List<ClientTooltipComponent> decorate(List<ClientTooltipComponent> rows) {
        ItemStack stack = hovered;
        hovered = ItemStack.EMPTY;
        if (!isEnabled() || stack.isEmpty()) {
            return rows;
        }
        List<ClientTooltipComponent> out = new ArrayList<>(rows);
        // An empty tooltip is one the item hides and it only ever gets the hint.
        boolean hidden = rows.isEmpty();
        if (!hidden) {
            int at = Math.min(1, out.size());
            for (Component line : startLines(stack)) {
                out.add(at++, text(line));
            }
            Component size = byteSizeLine(stack);
            if (size != null) {
                out.add(text(size));
            }
        }
        if (previewing()) {
            ClientTooltipComponent preview = preview(stack);
            if (preview != null) {
                if (stack.getItem() instanceof BundleItem) {
                    out.removeIf(row -> row instanceof ClientBundleTooltip);
                }
                out.add(preview);
            }
        } else if (previewable(stack)) {
            if (!hidden) {
                out.add(text(Component.empty()));
            }
            out.add(text(Component.literal("Hold ")
                .append(Component.literal(previewKey.getKeyName()).withStyle(ChatFormatting.YELLOW))
                .append(" to preview")));
        }
        return out;
    }

    private static ClientTooltipComponent text(Component line) {
        return ClientTooltipComponent.create(line.getVisualOrderText());
    }

    private boolean previewing() {
        return show.is(When.ALWAYS) || previewKey.isHeld();
    }

    // True whilst a preview is up. The vanilla shulker item lines can then go.
    public boolean skipsContainerLines() {
        return isEnabled() && containers.isOn() && previewing();
    }

    public boolean compactsContainerLines() {
        return isEnabled() && containers.isOn() && compactList.isOn();
    }

    public boolean showsHiddenTooltips() {
        return isEnabled() && hiddenTooltips.isOn();
    }

    public boolean showsHiddenLines() {
        return isEnabled() && hiddenLines.isOn();
    }

    // The five biggest item types by count and how many more kinds there are.
    public void compactLines(List<Optional<ItemStackTemplate>> slots, Consumer<Component> out) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (Optional<ItemStackTemplate> slot : slots) {
            if (slot.isEmpty() || slot.get().count() == 0) {
                continue;
            }
            counts.merge(slot.get().item().value(), slot.get().count(), Integer::sum);
        }
        counts.entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<Item, Integer> entry) -> entry.getValue()).reversed())
            .limit(COMPACT_ROWS)
            .forEach(entry -> out.accept(entry.getKey().getName(new ItemStack(entry.getKey())).copy()
                .append(Component.literal(" x" + entry.getValue()).withStyle(ChatFormatting.GRAY))));
        if (counts.size() > COMPACT_ROWS) {
            out.accept(Component.translatable("item.container.more_items", counts.size() - COMPACT_ROWS)
                .withStyle(ChatFormatting.ITALIC));
        }
    }

    // Lines that go right under the item name.
    private List<Component> startLines(ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        if (effects.isOn()) {
            for (MobEffectInstance effect : effectsOf(stack)) {
                lines.add(effectLine(effect));
            }
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (foodInfo.isOn() && food != null) {
            lines.add(Component.literal(String.format(Locale.ROOT, "Hunger %d and saturation %.1f",
                food.nutrition(), food.saturation())).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private static List<MobEffectInstance> effectsOf(ItemStack stack) {
        List<MobEffectInstance> found = new ArrayList<>();
        SuspiciousStewEffects stew = stack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
        if (stew != null) {
            for (SuspiciousStewEffects.Entry entry : stew.effects()) {
                found.add(entry.createEffectInstance());
            }
            return found;
        }
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null) {
            return found;
        }
        consumable.onConsumeEffects().forEach(effect -> {
            if (effect instanceof ApplyStatusEffectsConsumeEffect apply) {
                found.addAll(apply.effects());
            }
        });
        return found;
    }

    // Blue for a helpful effect and red for a harmful one.
    private Component effectLine(MobEffectInstance effect) {
        float tickRate = mc.level == null ? 20 : mc.level.tickRateManager().tickrate();
        MutableComponent line = Component.translatable(effect.getDescriptionId());
        if (effect.getAmplifier() != 0) {
            line.append(" " + (effect.getAmplifier() + 1));
        }
        line.append(" (").append(MobEffectUtil.formatDuration(effect, 1, tickRate)).append(")");
        return line.withStyle(effect.getEffect().value().isBeneficial() ? ChatFormatting.BLUE : ChatFormatting.RED);
    }

    private Component byteSizeLine(ItemStack stack) {
        if (!byteSize.isOn() || mc.player == null) {
            return null;
        }
        Optional<Tag> tag = ItemStack.CODEC
            .encodeStart(mc.player.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
            .result();
        if (tag.isEmpty()) {
            return Component.literal("Size unknown").withStyle(ChatFormatting.RED);
        }
        return Component.literal(sizeText(tag.get().sizeInBytes())).withStyle(ChatFormatting.DARK_GRAY);
    }

    private String sizeText(int bytes) {
        SizeUnit unit = sizeUnit.getValue();
        if (unit == SizeUnit.AUTO) {
            unit = bytes >= MEGABYTE ? SizeUnit.MEGABYTES : bytes >= KILOBYTE ? SizeUnit.KILOBYTES : SizeUnit.BYTES;
        }
        return switch (unit) {
            case BYTES, AUTO -> bytes + " bytes";
            case KILOBYTES -> String.format(Locale.ROOT, "%.2f kB", bytes / (float) KILOBYTE);
            case MEGABYTES -> String.format(Locale.ROOT, "%.4f MB", bytes / (float) MEGABYTE);
        };
    }

    // True for anything that would get a preview whilst the key is held.
    private boolean previewable(ItemStack stack) {
        Item item = stack.getItem();
        if (containers.isOn() && hasContents(stack)) {
            return true;
        }
        if (enderChest.isOn() && item == Items.ENDER_CHEST) {
            return true;
        }
        if (maps.isOn() && item == Items.FILLED_MAP) {
            return true;
        }
        if (books.isOn() && (item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK)) {
            return true;
        }
        if (banners.isOn() && isBannerLike(stack)) {
            return true;
        }
        if (buckets.isOn() && item instanceof MobBucketItem) {
            return true;
        }
        return bundles.isOn() && item instanceof BundleItem && hasBundleItems(stack);
    }

    private static boolean hasContents(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        return contents != null && contents.nonEmptyItems().iterator().hasNext();
    }

    private static boolean hasBundleItems(ItemStack stack) {
        BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
        return contents != null && !contents.isEmpty();
    }

    private static boolean isBannerLike(ItemStack stack) {
        if (stack.getItem() instanceof BannerItem || stack.has(DataComponents.PROVIDES_BANNER_PATTERNS)) {
            return true;
        }
        return stack.getItem() == Items.SHIELD
            && !stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers().isEmpty();
    }

    // Null when there is nothing worth previewing.
    private ClientTooltipComponent preview(ItemStack stack) {
        Item item = stack.getItem();
        if (containers.isOn() && hasContents(stack)) {
            return new ContainerPreview(contentsOf(stack), COLUMNS, tintOf(stack));
        }
        if (enderChest.isOn() && item == Items.ENDER_CHEST) {
            return remembered.isEmpty()
                ? text(Component.literal("Unknown inventory.").withStyle(ChatFormatting.DARK_RED))
                : new ContainerPreview(new ArrayList<>(remembered), COLUMNS, ENDER_TINT);
        }
        if (maps.isOn() && item == Items.FILLED_MAP) {
            return mapPreview(stack);
        }
        if (books.isOn() && (item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK)) {
            return bookPreview(stack);
        }
        if (banners.isOn() && isBannerLike(stack)) {
            return bannerPreview(stack);
        }
        if (buckets.isOn() && item instanceof MobBucketItem bucket) {
            return bucketPreview(stack, bucket);
        }
        if (bundles.isOn() && item instanceof BundleItem && hasBundleItems(stack)) {
            return new ContainerPreview(stack.get(DataComponents.BUNDLE_CONTENTS).itemCopyStream().toList(), COLUMNS);
        }
        return null;
    }

    // What your ender chest held when you last opened it. Empty until then.
    public List<ItemStack> rememberedEnderChest() {
        return List.copyOf(remembered);
    }

    public static List<ItemStack> contentsOf(ItemStack stack) {
        NonNullList<ItemStack> items = NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY);
        stack.get(DataComponents.CONTAINER).copyInto(items);
        return items;
    }

    // A shulker box previews on a dark shade of its own dye.
    public static int tintOf(ItemStack stack) {
        if (!(Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock box) || box.getColor() == null) {
            return ColorUtil.withAlpha(0x100010, PREVIEW_ALPHA);
        }
        int dye = box.getColor().getTextureDiffuseColor();
        int shaded = ColorUtil.lerp(0xFF000000, dye | 0xFF000000, TINT_SHADE);
        return ColorUtil.withAlpha(shaded, PREVIEW_ALPHA);
    }

    private ClientTooltipComponent mapPreview(ItemStack stack) {
        MapId id = stack.get(DataComponents.MAP_ID);
        if (id == null || mc.level == null) {
            return null;
        }
        MapItemSavedData data = MapItem.getSavedData(id, mc.level);
        if (data == null) {
            return null;
        }
        MapRenderState state = new MapRenderState();
        mc.getMapRenderer().extractRenderState(id, data, state);
        return new MapPreview(state, mapSize.getInt());
    }

    private static ClientTooltipComponent bookPreview(ItemStack stack) {
        Component first = null;
        int pages = 0;
        WritableBookContent writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (writable != null && !writable.pages().isEmpty()) {
            first = Component.literal(writable.pages().getFirst().get(false));
            pages = writable.pages().size();
        } else if (written != null && !written.pages().isEmpty()) {
            first = written.pages().getFirst().get(false);
            pages = written.pages().size();
        }
        if (first == null) {
            return null;
        }
        String count = pages == 1 ? " (1 page)" : " (" + pages + " pages)";
        return new BookPreview(first.copy().append(Component.literal(count).withStyle(ChatFormatting.GRAY)));
    }

    private static ClientTooltipComponent bannerPreview(ItemStack stack) {
        BannerPatternLayers layers = stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        if (stack.getItem() instanceof BannerItem banner) {
            return new BannerPreview(banner.getColor(), layers);
        }
        HolderSet<BannerPattern> provided = stack.get(DataComponents.PROVIDES_BANNER_PATTERNS);
        if (provided != null) {
            BannerPatternLayers sample = provided.size() == 0 ? BannerPatternLayers.EMPTY
                : new BannerPatternLayers.Builder().add(provided.get(0), DyeColor.WHITE).build();
            return new BannerPreview(DyeColor.GRAY, sample);
        }
        return new BannerPreview(stack.getOrDefault(DataComponents.BASE_COLOR, DyeColor.WHITE), layers);
    }

    private ClientTooltipComponent bucketPreview(ItemStack stack, MobBucketItem bucket) {
        CustomData data = stack.get(DataComponents.BUCKET_ENTITY_DATA);
        if (data == null || mc.level == null) {
            return null;
        }
        Entity entity = bucket.type.create(mc.level, EntitySpawnReason.BUCKET);
        if (!(entity instanceof LivingEntity living)) {
            return null;
        }
        living.applyComponentsFromItemStack(stack);
        if (living instanceof Bucketable bucketable) {
            bucketable.loadFromBucketTag(data.copyTag());
        }
        // A fish that thinks it is on land flops about.
        living.wasTouchingWater = true;
        return new EntityPreview(living);
    }

    // True for the click or key the player picked to open a window.
    public boolean wantsToOpen(MouseButtonEvent event) {
        return canOpen() && openWith.is(OpenWith.MIDDLE_CLICK) && event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
    }

    public boolean wantsToOpen(KeyEvent event) {
        return canOpen() && openWith.is(OpenWith.KEY) && openKey.isBound() && event.key() == openKey.getValue();
    }

    private boolean canOpen() {
        if (!isEnabled() || !openContents.isOn() || mc.player == null) {
            return false;
        }
        return !pauseInCreative.isOn() || !mc.player.hasInfiniteMaterials();
    }

    // Opens a window for the item and says whether one opened.
    public boolean openContents(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK) {
            mc.gui.setScreen(new BookViewScreen(BookViewScreen.BookAccess.fromItem(stack)));
            return true;
        }
        if (item instanceof BundleItem && hasBundleItems(stack)) {
            open(stack, stack.get(DataComponents.BUNDLE_CONTENTS).itemCopyStream().toList(), tintOf(stack));
            return true;
        }
        if (hasContents(stack)) {
            open(stack, contentsOf(stack), tintOf(stack));
            return true;
        }
        if (item == Items.ENDER_CHEST && !remembered.isEmpty()) {
            open(stack, new ArrayList<>(remembered), ENDER_TINT);
            return true;
        }
        return false;
    }

    // A chest screen closes for good because the server owns it. Your own
    // inventory and another peek window come back when this one shuts.
    private void open(ItemStack stack, List<ItemStack> items, int tint) {
        Screen current = mc.gui.screen();
        Screen parent = current instanceof InventoryScreen || current instanceof PeekScreen ? current : null;
        if (parent == null && current instanceof AbstractContainerScreen<?> container) {
            container.onClose();
        }
        mc.gui.setScreen(new PeekScreen(parent, stack.getHoverName(), items, tint));
    }
}
