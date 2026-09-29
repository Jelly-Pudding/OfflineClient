package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.NearFade;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.Carried;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityColors;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.GearRule;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.Sightings;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Paper empties shulker boxes and bundles in the items it sends for dropped stacks and item
// frames and gear. Only the item itself can be told apart. Banners arrive whole with their
// patterns and names.
public final class Collectibles extends Module {

    // One entity showing a picked item. The kind groups its messages and the words follow found.
    private record Find(Entity entity, String kind, String label, String words) {
    }

    // A banner with the corners of its cloth in order round the edge. The first two run along the top.
    private record Banner(BlockPos pos, Vec3[] cloth, int color, String label, String words) {

        Vec3 centre() {
            return cloth[0].add(cloth[2]).scale(HALF);
        }

        Vec3 top() {
            return cloth[0].add(cloth[1]).scale(HALF);
        }
    }

    // A pattern laid over a banner in one dye.
    private record Motif(ResourceKey<BannerPattern> pattern, DyeColor color) {

        boolean matches(BannerPatternLayers.Layer layer) {
            return layer.pattern().is(pattern) && layer.color() == color;
        }
    }

    private static final String MUSIC_DISC = "music_disc_";
    private static final String NETHERITE = "netherite";

    // Finds are gold until the player picks another colour.
    private static final float GOLD_HUE = 45;

    // Besides the raid banner of outposts two structures hang patterned banners. Mansions show
    // a white flower on light grey and end city towers two black bands on magenta.
    private static final List<Motif> MANSION_FLOWER = List.of(new Motif(BannerPatterns.FLOWER, DyeColor.WHITE));
    private static final List<Motif> END_CITY_BANDS = List.of(new Motif(BannerPatterns.TRIANGLE_TOP, DyeColor.BLACK),
        new Motif(BannerPatterns.TRIANGLE_BOTTOM, DyeColor.BLACK));

    // Twice a second is quick enough for a banner that was just hung.
    private static final int SCAN_TICKS = 10;

    // The banner renderer draws its model in sixteenths of a block at two thirds size.
    private static final double BANNER_SCALE = 2.0 / 3 / 16;
    // The cloth is twenty model units across and forty tall.
    private static final double CLOTH_HALF_WIDTH = 10;
    private static final double CLOTH_HEIGHT = 40;
    // Where the cloth starts and the middle of its depth in the standing and the hanging model.
    private static final double STANDING_CLOTH_Y = -44;
    private static final double STANDING_CLOTH_Z = -1.5;
    private static final double WALL_CLOTH_Y = -20.5;
    private static final double WALL_CLOTH_Z = 9;
    private static final double HALF = 0.5;

    // A dropped item is tiny. Its box is grown to be seen from afar.
    private static final double ITEM_GROW = 0.1;
    private static final double LABEL_LIFT = 0.3;
    // Text darker than this cannot be read on the label background. A black banner is labelled in grey.
    private static final float DARK_TEXT = 0.2f;

    private final RegistryListSetting<Item> items = new RegistryListSetting<>("Items",
        "The rare items to look for. Click to pick them.", BuiltInRegistries.ITEM, rareItems());
    private final GearRule gear = GearRule.filter();
    private final BoolSetting dropped = new BoolSetting("Dropped",
        "Looks at items lying on the ground.", true);
    private final BoolSetting frames = new BoolSetting("Item frames",
        "Looks at items shown in item frames.", true);
    private final BoolSetting stands = new BoolSetting("Stands and mannequins",
        "Looks at what armour stands and mannequins hold and wear.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Looks at what mobs hold and wear. Your own pets and the mounts you have ridden are left out.", true);
    private final BoolSetting banners = new BoolSetting("Banners",
        "Marks banners in the shape and colour of their cloth.", true);
    private final BoolSetting structureBanners = new BoolSetting("Structure banners",
        "Also marks the banners villages and outposts and mansions and end cities are built with.", false)
        .under(banners);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draws a line from you to each find.", false);
    private final BoolSetting labels = new BoolSetting("Labels",
        "Writes the name of each find above it.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1).under(labels);
    private final Notice notice = new Notice(this, Notice.Where.CHAT);
    private final EntityColors colors = new EntityColors(EntityColors.Mode.SINGLE, GOLD_HUE);
    // A find at your feet would hide under its own box.
    private final NearFade fade = new NearFade(3);

    // Rebuilt once a tick.
    private List<Find> found = List.of();
    // Rebuilt by every banner scan.
    private List<Banner> hung = List.of();
    private int wait;
    private final Sightings sightings = new Sightings();
    private final WorldWatch world = new WorldWatch();

    public Collectibles() {
        super("Collectibles", "Marks rare items and banners wherever they show in the world.", Category.HUNTING);
        addSettings(items);
        addSettings(gear.settings());
        addSettings(dropped, frames, stands, mobs, banners, structureBanners);
        addSettings(style.settings());
        addSettings(tracers, labels, scale);
        addSettings(notice.settings());
        addSettings(colors.settings());
        addSettings(fade.setting());
        searchTags("rare items", "dragon egg", "heads", "music disc", "item frame", "armour stand",
            "mannequin", "banner", "shulker");
    }

    // Music discs and netherite are gathered by name and shulker boxes by their block because
    // tags are not loaded when this runs.
    private static List<Item> rareItems() {
        List<Item> picked = new ArrayList<>(List.of(Items.DRAGON_EGG, Items.DRAGON_HEAD,
            Items.PLAYER_HEAD, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD, Items.PIGLIN_HEAD,
            Items.SKELETON_SKULL, Items.WITHER_SKELETON_SKULL, Items.ENCHANTED_GOLDEN_APPLE,
            Items.ELYTRA, Items.NETHER_STAR, Items.BEACON, Items.CONDUIT, Items.HEART_OF_THE_SEA,
            Items.HEAVY_CORE, Items.MACE, Items.TRIDENT, Items.SNIFFER_EGG, Items.TOTEM_OF_UNDYING));
        BuiltInRegistries.ITEM.stream()
            .filter(item -> {
                String path = BuiltInRegistries.ITEM.getKey(item).getPath();
                return path.startsWith(MUSIC_DISC) || path.startsWith(NETHERITE) || ItemUtil.isShulkerBox(item);
            })
            .forEach(picked::add);
        return picked;
    }

    @Override
    public String getSuffix() {
        return count(found.size() + shownBanners().size());
    }

    @Override
    protected void onEnable() {
        clear();
    }

    @Override
    protected void onDisable() {
        clear();
    }

    private void clear() {
        found = List.of();
        hung = List.of();
        wait = 0;
        sightings.clear();
        world.forget();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            // Positions mean something different in every world.
            hung = List.of();
            wait = 0;
        }
        found = findEntities();
        if (--wait <= 0) {
            wait = SCAN_TICKS;
            hung = banners.isOn() ? findBanners() : List.of();
        }
    }

    private List<Find> findEntities() {
        List<Find> finds = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            Find find = findOn(entity);
            if (find == null) {
                continue;
            }
            finds.add(find);
            if (sightings.firstTime(entity)) {
                notice.tell(find.kind(), find.words(), entity.blockPosition());
            }
        }
        return finds;
    }

    // Null when the entity shows nothing picked or its kind is switched off.
    private Find findOn(Entity entity) {
        return switch (entity) {
            case ItemEntity item -> dropped.isOn() ? shown(item, item.getItem(), "dropped item", " on the ground") : null;
            case ItemFrame frame -> frames.isOn() ? shown(frame, frame.getItem(), "item frame", " in an item frame") : null;
            case ArmorStand stand -> stands.isOn() ? carried(stand, "stand") : null;
            case Mannequin mannequin -> stands.isOn() ? carried(mannequin, "stand") : null;
            case Mob mob -> mobs.isOn() && !EntityUtil.isYours(mob) ? carried(mob, "mob") : null;
            default -> null;
        };
    }

    private boolean picked(ItemStack stack) {
        return !stack.isEmpty() && items.contains(stack.getItem()) && gear.passes(stack);
    }

    // Null unless the stack is one of the picked items.
    private Find shown(Entity entity, ItemStack stack, String kind, String where) {
        if (!picked(stack)) {
            return null;
        }
        String name = stack.getHoverName().getString();
        return new Find(entity, kind, name, ChatUtil.withArticle(name) + where);
    }

    // Null unless the holder holds or wears a picked item.
    private Find carried(LivingEntity holder, String kind) {
        List<Carried.Piece> pieces = Carried.pieces(holder,
            stack -> picked(stack) ? stack.getHoverName().getString() : null);
        if (pieces.isEmpty()) {
            return null;
        }
        return new Find(holder, kind, Carried.label(pieces),
            ChatUtil.withArticle(ChatUtil.words(holder.getType())) + " " + Carried.describe(pieces));
    }

    private List<Banner> findBanners() {
        List<Banner> scanned = new ArrayList<>();
        LoadedChunks.forEachBlockEntity(blockEntity -> {
            if (!(blockEntity instanceof BannerBlockEntity banner)
                || !structureBanners.isOn() && builtIn(banner)) {
                return;
            }
            Banner found = bannerOf(banner);
            scanned.add(found);
            if (sightings.firstTime(found.pos())) {
                notice.tell("banner", found.words(), found.pos());
            }
        });
        return scanned;
    }

    // True for a banner a structure is built with. Every one hangs on a wall with no name in
    // one of six designs. Outposts fly the raid banner.
    private boolean builtIn(BannerBlockEntity banner) {
        if (!(banner.getBlockState().getBlock() instanceof WallBannerBlock) || banner.getCustomName() != null) {
            return false;
        }
        List<BannerPatternLayers.Layer> layers = banner.getPatterns().layers();
        return switch (banner.getBaseColor()) {
            case BROWN, BLACK, GRAY -> layers.isEmpty();
            case LIGHT_GRAY -> shows(layers, MANSION_FLOWER);
            case MAGENTA -> shows(layers, END_CITY_BANDS);
            case WHITE -> banner.getPatterns().equals(raidBanner());
            default -> false;
        };
    }

    private static boolean shows(List<BannerPatternLayers.Layer> layers, List<Motif> motifs) {
        if (layers.size() != motifs.size()) {
            return false;
        }
        for (int i = 0; i < layers.size(); i++) {
            if (!motifs.get(i).matches(layers.get(i))) {
                return false;
            }
        }
        return true;
    }

    private BannerPatternLayers raidBanner() {
        return Raid.getOminousBannerInstance(mc.level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN))
            .get(DataComponents.BANNER_PATTERNS);
    }

    // Named after its item such as a light gray banner with 2 patterns named Home. The label
    // is the name a player gave it when there is one.
    private static Banner bannerOf(BannerBlockEntity banner) {
        BlockPos pos = banner.getBlockPos();
        int layers = banner.getPatterns().layers().size();
        String looks = ChatUtil.words(banner.getItem().getItem())
            + (layers == 0 ? "" : " with " + Tally.counted(layers, "pattern"));
        Component name = banner.getCustomName();
        String words = ChatUtil.withArticle(looks) + (name == null ? "" : " named " + name.getString());
        return new Banner(pos, cloth(pos, banner.getBlockState()), banner.getBaseColor().getTextureDiffuseColor(),
            name == null ? looks : name.getString(), words);
    }

    // The cloth as the renderer draws it. A standing banner turns in steps of a sixteenth of
    // a circle and a hanging one faces away from its wall.
    private static Vec3[] cloth(BlockPos pos, BlockState state) {
        boolean wall = state.getBlock() instanceof WallBannerBlock;
        double yaw = wall ? state.getValue(WallBannerBlock.FACING).toYRot()
            : RotationSegment.convertToDegrees(state.getValue(BannerBlock.ROTATION));
        double top = wall ? WALL_CLOTH_Y : STANDING_CLOTH_Y;
        double depth = wall ? WALL_CLOTH_Z : STANDING_CLOTH_Z;
        return new Vec3[] {
            clothPoint(pos, yaw, -CLOTH_HALF_WIDTH, top, depth),
            clothPoint(pos, yaw, CLOTH_HALF_WIDTH, top, depth),
            clothPoint(pos, yaw, CLOTH_HALF_WIDTH, top + CLOTH_HEIGHT, depth),
            clothPoint(pos, yaw, -CLOTH_HALF_WIDTH, top + CLOTH_HEIGHT, depth)};
    }

    // A point of the banner model in the world. The renderer scales the model upside down and
    // back to front and turns it about the middle of the block.
    private static Vec3 clothPoint(BlockPos pos, double yaw, double x, double y, double z) {
        double turn = Math.toRadians(-yaw);
        double across = x * BANNER_SCALE;
        double depth = -z * BANNER_SCALE;
        return Vec3.atBottomCenterOf(pos).add(across * Math.cos(turn) + depth * Math.sin(turn), -y * BANNER_SCALE,
            depth * Math.cos(turn) - across * Math.sin(turn));
    }

    // The banners to draw. Turning the switch off hides them before the next scan.
    private List<Banner> shownBanners() {
        return banners.isOn() ? hung : List.of();
    }

    private static AABB boxOf(Entity entity, float partialTicks) {
        AABB box = EntityUtil.lerpedBox(entity, partialTicks);
        return entity instanceof ItemEntity ? box.inflate(ITEM_GROW) : box;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Find find : found) {
            if (find.entity().isRemoved()) {
                continue;
            }
            AABB box = boxOf(find.entity(), event.getPartialTicks());
            float strength = fade.strengthAt(box.getCenter());
            if (strength > 0) {
                int color = ColorUtil.fade(colors.colorOf(find.entity()), strength);
                style.draw(batch, box, color, true);
                tracer(batch, box.getCenter(), color);
            }
        }
        for (Banner banner : shownBanners()) {
            float strength = fade.strengthAt(banner.centre());
            if (strength > 0) {
                int color = ColorUtil.fade(banner.color(), strength);
                Vec3[] cloth = banner.cloth();
                style.drawQuad(batch, cloth[0], cloth[1], cloth[2], cloth[3], color, true);
                tracer(batch, banner.centre(), color);
            }
        }
    }

    private void tracer(DrawBatch batch, Vec3 to, int color) {
        if (tracers.isOn()) {
            batch.tracer(to, color, true);
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!labels.isOn() || !inGame() || !WorldToScreen.update()) {
            return;
        }
        for (Find find : found) {
            if (find.entity().isRemoved()) {
                continue;
            }
            AABB box = boxOf(find.entity(), event.getPartialTicks());
            label(event, new Vec3((box.minX + box.maxX) / 2, box.maxY + LABEL_LIFT, (box.minZ + box.maxZ) / 2),
                find.label(), colors.colorOf(find.entity()));
        }
        for (Banner banner : shownBanners()) {
            int text = ColorUtil.luminance(banner.color()) < DARK_TEXT ? RenderUtil.MUTED_TEXT : banner.color();
            label(event, banner.top().add(0, LABEL_LIFT, 0), banner.label(), text);
        }
    }

    private void label(Render2DEvent event, Vec3 at, String text, int color) {
        Vec3 screen = WorldToScreen.project(at);
        if (screen != null) {
            RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                List.of(text), List.of(color));
        }
    }
}
