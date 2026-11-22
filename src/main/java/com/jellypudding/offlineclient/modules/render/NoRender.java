package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChunkRebuild;
import com.jellypudding.offlineclient.util.SignWords;
import net.minecraft.client.resources.model.EquipmentClientInfo.LayerType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignText;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Each item is skipped in its own renderer mixin. ClearView covers what is drawn over the screen.
public final class NoRender extends Module {

    public enum Banners { SHOW, POLE_ONLY, HIDE }

    private static final int SIGN_CACHE = 4096;

    // The worn armour of people and mobs and of wolves and horses and llamas and nautiluses.
    // Saddles and harnesses and wings stay drawn.
    private static final Set<LayerType> ARMOUR_LAYERS = EnumSet.of(LayerType.HUMANOID, LayerType.HUMANOID_LEGGINGS,
        LayerType.HUMANOID_BABY, LayerType.WOLF_BODY, LayerType.HORSE_BODY, LayerType.LLAMA_BODY,
        LayerType.NAUTILUS_BODY);

    private final BoolSetting armour = new BoolSetting("Armour",
        "Nobody is drawn wearing armour.", false);
    private final BoolSetting beaconBeams = new BoolSetting("Beacon beams",
        "Beacons keep their block and lose the beam.", true);
    private final BoolSetting fallingBlocks = new BoolSetting("Falling blocks",
        "Sand and gravel and anvils vanish whilst they fall.", false);
    private final BoolSetting worldBorder = new BoolSetting("World border",
        "The border wall is not drawn.", false);
    private final BoolSetting glint = new BoolSetting("Enchantment glint",
        "Enchanted items lose their shimmer.", false);
    private final BoolSetting fireworks = new BoolSetting("Firework explosions",
        "Rockets still fly but the burst is not drawn.", false);
    private final BoolSetting signText = new BoolSetting("Sign text",
        "Signs are drawn blank.", false);
    private final TextSetting signWords = new TextSetting("Only with words",
        "Only a sign with one of these words on it is drawn blank. Separate the words with spaces. Leave it"
            + " empty to blank every sign.", "")
        .under(signText);
    private final EnumSetting<Banners> banners = new EnumSetting<>("Banners",
        "How banners are drawn.", Banners.SHOW)
        .describe(Banners.SHOW, "Banners are drawn as normal.")
        .describe(Banners.POLE_ONLY, "The pole stays and the cloth goes.")
        .describe(Banners.HIDE, "Banners are not drawn at all.");
    private final BoolSetting itemFrames = new BoolSetting("Item frames",
        "Item frames and whatever they hold are not drawn.", false);
    private final BoolSetting enchantingBook = new BoolSetting("Enchanting book",
        "The book floating above an enchanting table is not drawn.", false);
    private final BoolSetting blockCracks = new BoolSetting("Block cracks",
        "The crack texture on a block being mined is not drawn.", false);
    private final BoolSetting caveCulling = new BoolSetting("Cave culling",
        "Chunks behind solid walls are drawn instead of being skipped.", false);
    private final BoolSetting mapMarkers = new BoolSetting("Map markers",
        "Player and banner markers are left off maps.", false);
    private final BoolSetting mapContents = new BoolSetting("Map contents",
        "Maps are drawn blank.", false);
    private final BoolSetting barriers = new BoolSetting("Barriers",
        "Barrier blocks show their marker without one in hand.", false);
    private final BoolSetting textureRotations = new BoolSetting("Texture rotations",
        "Every block uses the same texture rotation and offset instead of a random one.", false);
    private final RegistryListSetting<Block> blockEntities = new RegistryListSetting<>("Block entities",
        "Blocks whose special renderer is skipped such as chests and shulker boxes.",
        BuiltInRegistries.BLOCK, List.of());
    private final RegistryListSetting<EntityType<?>> entities = new RegistryListSetting<>("Entities",
        "Kinds of entity that are not drawn at all.", BuiltInRegistries.ENTITY_TYPE, List.of());
    private final BoolSetting dropSpawnPackets = new BoolSetting("Drop spawn packets",
        "Throws the chosen entities away as they arrive. They never exist on your side.", false);
    private final BoolSetting glowing = new BoolSetting("Glowing",
        "The glowing outline effect is removed.", false);
    private final BoolSetting spawnerMobs = new BoolSetting("Spawner mobs",
        "The spinning mob inside a spawner is not drawn.", false);
    private final BoolSetting deadEntities = new BoolSetting("Dead entities",
        "Entities vanish as soon as they die instead of playing the death animation.", false);
    private final BoolSetting nametags = new BoolSetting("Nametags",
        "The vanilla name labels above entities are not drawn.", false);

    private final ChunkRebuild rebuild = new ChunkRebuild();
    private int meshKey;

    // A sign keeps the same text object until it is edited and the answer is worked out once for
    // each.
    private final Map<SignText, Boolean> wordy = new IdentityHashMap<>();
    private List<String> words = List.of();
    private String wordsTyped = "";

    public NoRender() {
        super("NoRender", "Leaves out things in the world you do not need drawn.",
            Category.RENDER);
        addSettings(armour, beaconBeams, fallingBlocks, worldBorder, glint, fireworks,
            signText, signWords, banners, itemFrames, enchantingBook, blockCracks, caveCulling, mapMarkers,
            mapContents, barriers, textureRotations, blockEntities, entities, dropSpawnPackets,
            glowing, spawnerMobs, deadEntities, nametags);
        searchTags("hide", "no armor", "beacon", "glint", "banner", "sign", "censor signs");
    }

    // Culling and rotations are baked into the chunk meshes. They need a rebuild.
    @Override
    protected void onEnable() {
        meshKey = meshKey();
        rebuildChunks();
    }

    @Override
    protected void onDisable() {
        rebuildChunks();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        int key = meshKey();
        rebuild.tick(key != meshKey);
        meshKey = key;
    }

    private int meshKey() {
        return (caveCulling.isOn() ? 1 : 0) | (textureRotations.isOn() ? 2 : 0);
    }

    private void rebuildChunks() {
        if (meshKey() != 0) {
            ChunkRebuild.now();
        }
    }

    public boolean hidesArmour(LayerType layer) {
        return isEnabled() && armour.isOn() && ARMOUR_LAYERS.contains(layer);
    }

    public boolean hidesBeaconBeams() {
        return isEnabled() && beaconBeams.isOn();
    }

    public boolean hidesFallingBlocks() {
        return isEnabled() && fallingBlocks.isOn();
    }

    public boolean hidesWorldBorder() {
        return isEnabled() && worldBorder.isOn();
    }

    public boolean hidesGlint() {
        return isEnabled() && glint.isOn();
    }

    public boolean hidesFireworks() {
        return isEnabled() && fireworks.isOn();
    }

    public boolean blanksSign(SignText front, SignText back) {
        if (!isEnabled() || !signText.isOn()) {
            return false;
        }
        String typed = signWords.getValue();
        if (typed.isBlank()) {
            return true;
        }
        if (!typed.equals(wordsTyped) || wordy.size() > SIGN_CACHE) {
            wordsTyped = typed;
            words = SignWords.words(typed);
            wordy.clear();
        }
        return carriesWord(front) || carriesWord(back);
    }

    private boolean carriesWord(SignText side) {
        return side != null && wordy.computeIfAbsent(side, text -> {
            List<String> lines = new ArrayList<>();
            SignWords.addLines(text, lines);
            return SignWords.hasAnyWord(lines, words);
        });
    }

    public Banners bannerMode() {
        return isEnabled() ? banners.getValue() : Banners.SHOW;
    }

    public boolean hidesItemFrames() {
        return isEnabled() && itemFrames.isOn();
    }

    public boolean hidesEnchantingBook() {
        return isEnabled() && enchantingBook.isOn();
    }

    public boolean hidesBlockCracks() {
        return isEnabled() && blockCracks.isOn();
    }

    public boolean skipsCaveCulling() {
        return isEnabled() && caveCulling.isOn();
    }

    public boolean hidesMapMarkers() {
        return isEnabled() && mapMarkers.isOn();
    }

    public boolean hidesMapContents() {
        return isEnabled() && mapContents.isOn();
    }

    public boolean showsBarriers() {
        return isEnabled() && barriers.isOn();
    }

    public boolean fixesTextureRotations() {
        return isEnabled() && textureRotations.isOn();
    }

    public boolean hidesBlockEntity(Block block) {
        return isEnabled() && blockEntities.contains(block);
    }

    public boolean hidesEntity(EntityType<?> type) {
        return isEnabled() && entities.contains(type);
    }

    public boolean hidesEntity(Entity entity) {
        if (!isEnabled()) {
            return false;
        }
        if (entities.contains(entity.getType())) {
            return true;
        }
        return deadEntities.isOn() && entity instanceof LivingEntity living && living.isDeadOrDying();
    }

    public boolean dropsSpawnPacket(EntityType<?> type) {
        return isEnabled() && dropSpawnPackets.isOn() && entities.contains(type);
    }

    public boolean hidesGlowing() {
        return isEnabled() && glowing.isOn();
    }

    public boolean hidesSpawnerMobs() {
        return isEnabled() && spawnerMobs.isOn();
    }

    public boolean hidesNametags() {
        return isEnabled() && nametags.isOn();
    }
}
