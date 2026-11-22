package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.gui.PeekScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import com.jellypudding.offlineclient.render.BannerPreview;
import com.jellypudding.offlineclient.render.BookPreview;
import com.jellypudding.offlineclient.render.ContainerPreview;
import com.jellypudding.offlineclient.render.EntityPreview;
import com.jellypudding.offlineclient.render.MapPreview;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.EffectText;
import com.jellypudding.offlineclient.util.EnderChest;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientBundleTooltip;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Bucketable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

// AbstractContainerScreenMixin hands over the stack of a hovered slot and GuiGraphicsExtractorMixin
// any stack a tooltip is set from. GuiGraphicsExtractorMixin then asks for the extra rows. The
// container and tooltip display mixins ask what to hide.
public final class BetterTooltips extends Module {

    public enum When { KEY_HELD, ALWAYS }

    public enum OpenWith { MIDDLE_CLICK, KEY }

    public enum SizeUnit { BYTES, KILOBYTES, MEGABYTES, AUTO }

    private enum Preview { CONTAINER, ENDER_CHEST, MAP, BOOK, BANNER, BUCKET, BUNDLE }

    private static final int COLUMNS = 9;
    private static final int COMPACT_ROWS = 5;
    private static final int SLOT = 16;
    private static final int ICON = 10;
    private static final int MIXED_COLOR = 0xFFFFFF55;
    // A screen never shows as many boxes. The ones drawn least of late go first.
    private static final int MOST_ICONS = 512;

    private record BoxIcon(ItemStack icon, boolean mixed) {

        private static final BoxIcon NONE = new BoxIcon(ItemStack.EMPTY, false);
    }
    private static final int KILOBYTE = 1024;
    private static final int MEGABYTE = KILOBYTE * KILOBYTE;

    // The server sends a fresh stack whenever the item changes but a click fills a bundle in place.
    private record Sized(ItemStack stack, BundleContents bundle, int bytes) {

        private static final Sized NONE = new Sized(ItemStack.EMPTY, null, 0);
        private static final int UNWRITABLE = -1;

        private boolean holds(ItemStack other) {
            return stack == other && bundle == other.get(DataComponents.BUNDLE_CONTENTS);
        }
    }

    private final EnumSetting<When> show = new EnumSetting<>("Show previews",
        "When the picture previews appear in a tooltip.", When.KEY_HELD)
        .describe(When.KEY_HELD, "Only whilst the preview key is held.")
        .describe(When.ALWAYS, "On every tooltip.");
    private final KeybindSetting previewKey = new KeybindSetting("Preview key",
        "The key to hold for a preview.", InputConstants.KEY_LALT)
        .under(show, When.KEY_HELD);
    private final BoolSetting containers = new BoolSetting("Containers",
        "Shows what is packed inside a shulker box you hover in any inventory.", true);
    private final BoolSetting compactList = new BoolSetting("Compact list",
        "Replaces the vanilla shulker item lines with the five biggest stacks by count.", true)
        .under(containers);
    private final BoolSetting contentsIcon = new BoolSetting("Contents icon",
        "Draws the item a shulker box holds most of over the box in every inventory."
            + " A plus marks a box with more than one kind inside.", false);
    private final BoolSetting enderChest = new BoolSetting("Ender chest",
        "Shows what your ender chest held when you last opened it as you hover an ender chest item.", true);
    private final BoolSetting maps = new BoolSetting("Maps",
        "Shows the map itself as you hover a filled map.", true);
    private final NumberSetting mapSize = new NumberSetting("Map size",
        "How big the map preview is drawn.", 128, 64, 256, 8, " px").min(16)
        .under(maps);
    private final BoolSetting books = new BoolSetting("Books",
        "Shows the first page of a book you hover.", true);
    private final BoolSetting banners = new BoolSetting("Banners",
        "Shows the flag a banner or pattern or shield makes as you hover it.", true);
    private final BoolSetting buckets = new BoolSetting("Bucket mobs",
        "Shows the creature in a bucket of fish or axolotl as you hover it.", true);
    private final BoolSetting bundles = new BoolSetting("Bundles",
        "Shows every item inside a bundle you hover rather than the vanilla few.", true);
    private final BoolSetting foodInfo = new BoolSetting("Food info",
        "Adds a line with the hunger and saturation of food.", true);
    private final BoolSetting effects = new BoolSetting("Effects",
        "Lists the potion effects a food or stew gives with their length.", true);
    private final BoolSetting byteSize = new BoolSetting("Byte size",
        "Adds a line with how many bytes the item takes up.", true);
    private final EnumSetting<SizeUnit> sizeUnit = new EnumSetting<>("Size unit",
        "The unit the byte size is written in.", SizeUnit.AUTO)
        .describe(SizeUnit.AUTO, "Whichever unit fits the number best.")
        .under(byteSize);
    private final BoolSetting hiddenTooltips = new BoolSetting("Show hidden tooltips",
        "Shows a tooltip even when the item asks for it to be hidden.", false);
    private final BoolSetting hiddenLines = new BoolSetting("Show hidden lines",
        "Shows lines the item hides such as enchantments and lore.", false);
    private final BoolSetting openContents = new BoolSetting("Open contents",
        "Opens a window showing what is inside a container or book you click in an inventory.", true);
    private final EnumSetting<OpenWith> openWith = new EnumSetting<>("Open with",
        "What opens the window.", OpenWith.MIDDLE_CLICK)
        .describe(OpenWith.MIDDLE_CLICK, "The middle mouse button.")
        .describe(OpenWith.KEY, "The key picked below.")
        .under(openContents);
    private final KeybindSetting openKey = new KeybindSetting("Open key",
        "The key that opens the window.", InputConstants.KEY_P)
        .under(openWith, OpenWith.KEY);
    private final BoolSetting pauseInCreative = new BoolSetting("Pause in creative",
        "Leaves middle click alone in creative where it clones the item.", true)
        .under(openContents);

    private ItemStack hovered = ItemStack.EMPTY;
    // One tooltip shows at a time and the size line is asked for every frame it is up.
    private Sized sized = Sized.NONE;
    // What a box holds is worked out once for each stack. The slots are drawn every frame and
    // the server sends a new stack whenever a box changes.
    private final Map<ItemStack, BoxIcon> icons = new BoundedMap<>(MOST_ICONS, true);

    public BetterTooltips() {
        super("BetterTooltips", "Previews and extra facts in item tooltips.", Category.RENDER);
        addSettings(show, previewKey, containers, compactList, contentsIcon, enderChest, maps, mapSize, books, banners,
            buckets, bundles, foodInfo, effects, byteSize, sizeUnit, hiddenTooltips, hiddenLines,
            openContents, openWith, openKey, pauseInCreative);
        searchTags("tooltip", "shulker", "preview", "peek");
    }

    @Override
    protected void onDisable() {
        hovered = ItemStack.EMPTY;
        sized = Sized.NONE;
        icons.clear();
    }

    // Called from the mixin before the game draws the count and the bar of an item. The icon
    // goes over the box and under those.
    public void drawContentsIcon(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y) {
        if (!isEnabled() || !contentsIcon.isOn() || !stack.has(DataComponents.CONTAINER) || concealed()) {
            return;
        }
        BoxIcon box = icons.computeIfAbsent(stack, BetterTooltips::iconFor);
        if (box.icon().isEmpty()) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + SLOT - ICON, y + SLOT - ICON);
        graphics.pose().scale((float) ICON / SLOT, (float) ICON / SLOT);
        graphics.item(box.icon(), 0, 0);
        graphics.pose().popMatrix();
        if (box.mixed()) {
            graphics.text(font, "+", x, y, MIXED_COLOR, true);
        }
    }

    // The first stack of the kind the box holds most of. A potion keeps its colour that way.
    private static BoxIcon iconFor(ItemStack stack) {
        Iterable<ItemStackTemplate> held = stack.get(DataComponents.CONTAINER).nonEmptyItems();
        Map<Item, Integer> counts = counts(held);
        Item most = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        for (ItemStackTemplate template : held) {
            if (template.item().value() == most) {
                return new BoxIcon(template.withCount(1).create(), counts.size() > 1);
            }
        }
        return BoxIcon.NONE;
    }

    private static Map<Item, Integer> counts(Iterable<ItemStackTemplate> held) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ItemStackTemplate template : held) {
            if (template.count() > 0) {
                counts.merge(template.item().value(), template.count(), Integer::sum);
            }
        }
        return counts;
    }

    // Called from the mixins just before the game sets an item tooltip.
    public void setHovered(ItemStack stack) {
        hovered = stack;
    }

    // The hovered stack is used up.
    public List<ClientTooltipComponent> decorate(List<ClientTooltipComponent> rows) {
        ItemStack stack = hovered;
        hovered = ItemStack.EMPTY;
        if (!isEnabled() || stack.isEmpty() || concealed()) {
            return rows;
        }
        List<ClientTooltipComponent> out = new ArrayList<>(rows);
        // An empty tooltip is one the item hides. It only gets the preview or the hint.
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
        Preview kind = previewOf(stack);
        if (kind == null) {
            return out;
        }
        if (previewing()) {
            ClientTooltipComponent preview = preview(stack, kind);
            if (preview != null) {
                if (kind == Preview.BUNDLE) {
                    out.removeIf(row -> row instanceof ClientBundleTooltip);
                }
                out.add(preview);
            }
        } else {
            if (!hidden) {
                out.add(text(Component.empty()));
            }
            out.add(text(Component.literal("Hold ")
                .append(Component.literal(previewKey.getValueString()).withStyle(ChatFormatting.YELLOW))
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

    // With no screen open the item sits in the hotbar which is part of the overlay.
    private boolean concealed() {
        return Conceal.hides(mc.gui.screen() == null ? Conceal.Part.OVERLAY : Conceal.Part.SCREENS);
    }

    public boolean skipsContainerLines() {
        return isEnabled() && containers.isOn() && previewing() && !concealed();
    }

    public boolean compactsContainerLines() {
        return isEnabled() && containers.isOn() && compactList.isOn() && !concealed();
    }

    public boolean showsHiddenTooltips() {
        return isEnabled() && hiddenTooltips.isOn();
    }

    public boolean showsHiddenLines() {
        return isEnabled() && hiddenLines.isOn();
    }

    public void compactLines(List<Optional<ItemStackTemplate>> slots, Consumer<Component> out) {
        Map<Item, Integer> counts = counts(slots.stream().flatMap(Optional::stream).toList());
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

    private static Component effectLine(MobEffectInstance effect) {
        return Component.literal(EffectText.name(effect) + " (" + EffectText.timeLeft(effect) + ")")
            .withStyle(effect.getEffect().value().isBeneficial() ? ChatFormatting.BLUE : ChatFormatting.RED);
    }

    private Component byteSizeLine(ItemStack stack) {
        if (!byteSize.isOn() || mc.player == null) {
            return null;
        }
        if (!sized.holds(stack)) {
            sized = new Sized(stack, stack.get(DataComponents.BUNDLE_CONTENTS), bytesOf(stack));
        }
        if (sized.bytes() == Sized.UNWRITABLE) {
            return Component.literal("Size unknown").withStyle(ChatFormatting.RED);
        }
        return Component.literal(sizeText(sized.bytes())).withStyle(ChatFormatting.DARK_GRAY);
    }

    private int bytesOf(ItemStack stack) {
        return ItemStack.CODEC
            .encodeStart(mc.player.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
            .result().map(Tag::sizeInBytes).orElse(Sized.UNWRITABLE);
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

    private Preview previewOf(ItemStack stack) {
        Item item = stack.getItem();
        if (containers.isOn() && hasContents(stack)) {
            return Preview.CONTAINER;
        }
        if (enderChest.isOn() && item == Items.ENDER_CHEST) {
            return Preview.ENDER_CHEST;
        }
        // A map the server has not sent yet has no picture.
        if (maps.isOn() && item == Items.FILLED_MAP && MapPreview.dataOf(stack.get(DataComponents.MAP_ID)) != null) {
            return Preview.MAP;
        }
        if (books.isOn() && pageCount(stack) > 0) {
            return Preview.BOOK;
        }
        if (banners.isOn() && isBannerLike(stack)) {
            return Preview.BANNER;
        }
        if (buckets.isOn() && item instanceof MobBucketItem) {
            return Preview.BUCKET;
        }
        if (bundles.isOn() && item instanceof BundleItem && hasBundleItems(stack)) {
            return Preview.BUNDLE;
        }
        return null;
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

    private ClientTooltipComponent preview(ItemStack stack, Preview kind) {
        return switch (kind) {
            case CONTAINER -> new ContainerPreview(ContainerPreview.contentsOf(stack), COLUMNS,
                ContainerPreview.tintOf(stack));
            case ENDER_CHEST -> enderChestPreview(stack);
            case MAP -> new MapPreview(MapPreview.stateOf(stack), mapSize.getInt());
            case BOOK -> bookPreview(stack);
            case BANNER -> bannerPreview(stack);
            case BUCKET -> bucketPreview(stack, (MobBucketItem) stack.getItem());
            case BUNDLE -> new ContainerPreview(stack.get(DataComponents.BUNDLE_CONTENTS).itemCopies().toList(),
                COLUMNS);
        };
    }

    private static ClientTooltipComponent enderChestPreview(ItemStack stack) {
        List<ItemStack> chest = EnderChest.INSTANCE.contents();
        return chest.isEmpty()
            ? text(Component.literal("Unknown inventory.").withStyle(ChatFormatting.DARK_RED))
            : new ContainerPreview(chest, COLUMNS, ContainerPreview.tintOf(stack));
    }

    private static int pageCount(ItemStack stack) {
        if (stack.getItem() == Items.WRITABLE_BOOK) {
            return stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY).pages().size();
        }
        return stack.getItem() == Items.WRITTEN_BOOK
            ? stack.getOrDefault(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent.EMPTY).pages().size() : 0;
    }

    // Only asked for a book with a page.
    private static ClientTooltipComponent bookPreview(ItemStack stack) {
        Component first = stack.getItem() == Items.WRITABLE_BOOK
            ? Component.literal(stack.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getFirst().get(false))
            : stack.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().getFirst().get(false);
        int pages = pageCount(stack);
        String count = pages == 1 ? " (1 page)" : " (" + pages + " pages)";
        return new BookPreview(BookPreview.head(first).append(Component.literal(count).withStyle(ChatFormatting.GRAY)));
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

    public boolean wantsToOpen(MouseButtonEvent event) {
        return canOpen() && (openWith.is(OpenWith.MIDDLE_CLICK)
            ? event.button() == InputConstants.MOUSE_BUTTON_MIDDLE : openKey.answers(event));
    }

    public boolean wantsToOpen(KeyEvent event) {
        return canOpen() && openWith.is(OpenWith.KEY) && openKey.answers(event);
    }

    private boolean canOpen() {
        if (!isEnabled() || !openContents.isOn() || mc.player == null) {
            return false;
        }
        return !pauseInCreative.isOn() || !mc.player.hasInfiniteMaterials();
    }

    public boolean openContents(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK) {
            mc.gui.setScreen(new BookViewScreen(BookViewScreen.BookAccess.fromItem(stack)));
            return true;
        }
        if (item instanceof BundleItem && hasBundleItems(stack)) {
            open(stack, stack.get(DataComponents.BUNDLE_CONTENTS).itemCopies().toList(),
                ContainerPreview.tintOf(stack));
            return true;
        }
        if (hasContents(stack)) {
            open(stack, ContainerPreview.contentsOf(stack), ContainerPreview.tintOf(stack));
            return true;
        }
        List<ItemStack> chest = EnderChest.INSTANCE.contents();
        if (item == Items.ENDER_CHEST && !chest.isEmpty()) {
            open(stack, chest, ContainerPreview.tintOf(stack));
            return true;
        }
        return false;
    }

    // A chest screen closes for good because the server owns it. Your own
    // inventory and another peek window return once this one shuts.
    private void open(ItemStack stack, List<ItemStack> items, int tint) {
        Screen current = mc.gui.screen();
        Screen parent = current instanceof InventoryScreen || current instanceof PeekScreen ? current : null;
        if (parent == null && current instanceof AbstractContainerScreen<?> container) {
            container.onClose();
        }
        mc.gui.setScreen(new PeekScreen(parent, stack.getHoverName(), items, tint));
    }
}
