package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.Sightings;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// The server sends every axolotl with its colour. Each marked one is boxed in that colour.
public final class AxolotlHunter extends Module {

    // Ticks before the same axolotl is tried again.
    private static final int COOLDOWN = 20;

    private static final int PINK = 0xFFF7A1C4;
    private static final int BROWN = 0xFF8A5A3C;
    private static final int GOLD = 0xFFF5C542;
    private static final int CYAN = 0xFF5CE1E6;
    private static final int BLUE = 0xFF4169FF;

    private final Map<Axolotl.Variant, BoolSetting> marked = new EnumMap<>(Axolotl.Variant.class);
    private final BoxStyle shape = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Also draws a line from you to each marked axolotl.", true);
    private final BoolSetting chat = new BoolSetting("Chat",
        "Posts each marked axolotl in chat with where it swims the first time you see it.", true);
    private final BoolSetting catchThem = new BoolSetting("Catch",
        "Scoops up marked axolotls in reach with a water bucket from the hotbar.", false);
    private final NumberSetting range = new NumberSetting("Range",
        "How close an axolotl has to be to your eyes to catch it.", 5, 1, 6, 0.1).under(catchThem);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the axolotl on the server side.", true).under(catchThem);

    // Rebuilt once a tick.
    private List<Axolotl> found = List.of();
    private final Sightings sightings = new Sightings();
    private final Cooldowns<Integer> tried = new Cooldowns<>();
    private final SlotSwap slots = new SlotSwap();

    public AxolotlHunter() {
        super("AxolotlHunter", "Marks axolotls of the colours you pick and can catch them in a bucket.",
            Category.WORLD);
        marked.put(Axolotl.Variant.LUCY, new BoolSetting("Pink", "Marks pink axolotls.", false));
        marked.put(Axolotl.Variant.WILD, new BoolSetting("Brown", "Marks brown axolotls.", false));
        marked.put(Axolotl.Variant.GOLD, new BoolSetting("Gold", "Marks gold axolotls.", false));
        marked.put(Axolotl.Variant.CYAN, new BoolSetting("Cyan", "Marks cyan axolotls.", false));
        marked.put(Axolotl.Variant.BLUE, new BoolSetting("Blue",
            "Marks blue axolotls. They never spawn in the wild and come from breeding at one in twelve hundred.",
            true));
        addSettings(marked.values().toArray(BoolSetting[]::new));
        addSettings(shape.settings());
        addSettings(tracers, chat, catchThem, range, rotate);
        searchTags("axolotl", "blue axolotl", "bucket", "rare");
    }

    @Override
    public String getSuffix() {
        return count(found.size());
    }

    @Override
    protected void onEnable() {
        found = List.of();
        sightings.clear();
        tried.clear();
    }

    @Override
    protected void onDisable() {
        found = List.of();
        sightings.clear();
        slots.restoreIfMine();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        List<Axolotl> seen = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Axolotl axolotl && wanted(axolotl)) {
                seen.add(axolotl);
                if (chat.isOn() && sightings.firstTime(axolotl)) {
                    ChatUtil.message("§bAxolotlHunter §7" + colourName(axolotl) + " axolotl at §f"
                        + BlockUtil.text(axolotl.blockPosition()) + "§7.");
                }
            }
        }
        found = seen;
        if (catchThem.isOn()) {
            catchNearest();
        }
    }

    private void catchNearest() {
        if (mc.player.isSpectator() || mc.gui.screen() != null || mc.player.isUsingItem()
            || mc.player.isHandsBusy()) {
            return;
        }
        tried.tick();
        // The server measures reach from the eyes and an axolotl it refuses still vanishes from your screen.
        Entity target = EntityUtil.bestInReach(range.getValue(), TargetPriority.NEAREST, this::catchable);
        int slot = target == null ? -1 : InventoryUtil.hotbarSlot(stack -> stack.is(Items.WATER_BUCKET));
        if (slot == -1) {
            slots.restoreIfMine();
            return;
        }
        slots.select(slot);
        EntityUtil.interact(target, InteractionHand.MAIN_HAND, rotate.isOn());
        tried.put(target.getId(), COOLDOWN);
        slots.restoreIfMine();
    }

    // One on your own lead would only be let off it.
    private boolean catchable(Entity entity) {
        return entity instanceof Axolotl axolotl && wanted(axolotl) && axolotl.getLeashHolder() != mc.player
            && !tried.contains(entity.getId());
    }

    private boolean wanted(Axolotl axolotl) {
        return axolotl.isAlive() && marked.get(axolotl.getVariant()).isOn();
    }

    private String colourName(Axolotl axolotl) {
        return marked.get(axolotl.getVariant()).getName();
    }

    private static int colourOf(Axolotl axolotl) {
        return switch (axolotl.getVariant()) {
            case LUCY -> PINK;
            case WILD -> BROWN;
            case GOLD -> GOLD;
            case CYAN -> CYAN;
            case BLUE -> BLUE;
        };
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || found.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Axolotl axolotl : found) {
            if (axolotl.isRemoved()) {
                continue;
            }
            AABB box = EntityUtil.lerpedBox(axolotl, event.getPartialTicks());
            int colour = colourOf(axolotl);
            shape.draw(batch, box, colour, true);
            if (tracers.isOn()) {
                batch.tracer(box.getCenter(), colour, true);
            }
        }
    }
}
