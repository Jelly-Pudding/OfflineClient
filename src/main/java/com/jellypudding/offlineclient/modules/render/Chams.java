package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

// EntityRendererMixin flags the target and LivingEntityRendererMixin swaps the
// render type. The crystal and avatar mixins reshape crystals and your own arm.
public final class Chams extends Module {

    // A plain white texture that leaves only the tint on a model.
    public static final Identifier BLANK = Identifier.parse("offlineclient:textures/blank.png");

    private static volatile Chams instance;

    private final EntityFilter filter = EntityFilter.living("Draw", "drawn", true,
        EntityFilter.Pick.NONE, List.of());
    private final BoolSetting self = new BoolSetting("Self",
        "Show your own body in third person.", false);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Draw the model over blocks instead of only tinting it.", true);
    private final ColorSetting color = new ColorSetting("Colour",
        "Model colour.", 300, false);
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How solid the model looks.", 0.7, 0.1, 1, 0.05).min(0.05).max(1);
    private final BoolSetting friendColor = new BoolSetting("Friend colour",
        "Paint friends blue instead.", true);
    private final BoolSetting playerSkin = new BoolSetting("Player skin",
        "Keep the skin on players. Off paints them as one flat colour.", true);
    private final NumberSetting playerScale = new NumberSetting("Player scale",
        "Makes player models bigger or smaller.", 1, 0.25, 3, 0.05, "x").min(0.05).max(10);

    private final BoolSetting crystals = new BoolSetting("Crystals",
        "Reshape and recolour end crystals.", false);
    private final NumberSetting crystalScale = new NumberSetting("Crystal scale",
        "How big a crystal is drawn.", 0.6, 0.1, 2, 0.05, "x").min(0.05).max(5)
        .under(crystals);
    private final NumberSetting crystalBounce = new NumberSetting("Crystal bounce",
        "How high a crystal bobs up and down.", 0.6, 0, 2, 0.05, "x").min(0).max(5)
        .under(crystals);
    private final NumberSetting crystalSpin = new NumberSetting("Crystal spin",
        "How fast a crystal turns.", 0.3, 0, 2, 0.05, "x").min(0).max(10)
        .under(crystals);
    private final BoolSetting crystalTexture = new BoolSetting("Crystal texture",
        "Keep the crystal texture under the colour. Off paints it flat.", true)
        .under(crystals);
    private final ColorSetting crystalColor = new ColorSetting("Crystal colour",
        "Colour tinted over a crystal.", 272, 0.47f, 1f, false)
        .under(crystals);

    private final BoolSetting hand = new BoolSetting("Hand",
        "Recolour your own arm in first person.", false);
    private final BoolSetting handTexture = new BoolSetting("Hand texture",
        "Keep your skin on the arm under the colour. Off paints it flat.", false)
        .under(hand);
    private final ColorSetting handColor = new ColorSetting("Hand colour",
        "Colour of the arm.", 272, 0.47f, 1f, false)
        .under(hand);
    private final NumberSetting handOpacity = new NumberSetting("Hand opacity",
        "How solid the arm looks.", 0.6, 0.1, 1, 0.05).min(0.05).max(1)
        .under(hand);

    public Chams() {
        super("Chams", "Draws player and mob models through walls.", Category.RENDER);
        addSettings(filter.settings());
        addSettings(self, throughWalls, color, opacity, friendColor, playerSkin, playerScale,
            crystals, crystalScale, crystalBounce, crystalSpin, crystalTexture, crystalColor,
            hand, handTexture, handColor, handOpacity);
        searchTags("see through", "models", "wallhack");
        instance = this;
    }

    // Null before the client has started.
    public static Chams get() {
        return instance;
    }

    public boolean applies(Entity entity) {
        if (!isEnabled() || !(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (entity == mc.player) {
            return self.isOn() && showsSelf();
        }
        return filter.matches(entity);
    }

    // The player's own model is only on screen in third person or Freecam.
    private boolean showsSelf() {
        return Modules.enabled(Freecam.class) || !mc.options.getCameraType().isFirstPerson();
    }

    public boolean throughWalls() {
        return throughWalls.isOn();
    }

    // True when a player loses its skin for a flat colour.
    public boolean flat(Entity entity) {
        return entity instanceof Player && !playerSkin.isOn();
    }

    public int tintFor(Entity entity) {
        int base = color.getColor();
        if (friendColor.isOn() && entity instanceof Player player
            && EntityUtil.isFriend(player)) {
            base = EntityUtil.FRIEND_COLOR;
        }
        return ColorUtil.fade(base, opacity.getFloat());
    }

    // One for anything that is not a marked player.
    public float scaleFor(Entity entity) {
        return entity instanceof Player && applies(entity) ? playerScale.getFloat() : 1;
    }

    public boolean reshapesCrystals() {
        return isEnabled() && crystals.isOn();
    }

    public float crystalScale() {
        return crystalScale.getFloat();
    }

    public float crystalBounce() {
        return crystalBounce.getFloat();
    }

    public float crystalSpin() {
        return crystalSpin.getFloat();
    }

    public Identifier crystalTexture(Identifier vanilla) {
        return crystalTexture.isOn() ? vanilla : BLANK;
    }

    public int crystalColor() {
        return crystalColor.getColor();
    }

    public boolean paintsHand() {
        return isEnabled() && hand.isOn();
    }

    public Identifier handTexture(Identifier skin) {
        return handTexture.isOn() ? skin : BLANK;
    }

    public int handColor() {
        return ColorUtil.fade(handColor.getColor(), handOpacity.getFloat());
    }
}
