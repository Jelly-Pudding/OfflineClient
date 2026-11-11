package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.ChamsPicture;
import com.jellypudding.offlineclient.render.PictureFile;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.AwayView;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityColors;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

// EntityRendererMixin flags the target and LivingEntityRendererMixin swaps the
// render type. The crystal and avatar mixins reshape crystals and your own arm.
public final class Chams extends Module {

    public enum Fill { MODEL, FLAT, PICTURE }

    // A plain white texture that leaves only the tint on a model.
    public static final Identifier BLANK = Identifier.parse("offlineclient:textures/blank.png");

    private final EntityFilter filter = EntityFilter.living("Draws", "drawn", true,
        EntityFilter.Pick.NONE, List.of());
    private final BoolSetting self = new BoolSetting("Self",
        "Shows your own body in third person and whilst Freecam or RemoteView has the camera.", false);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Draws the model over blocks instead of only tinting it.", true);
    private final EntityColors colors = new EntityColors(EntityColors.Mode.SINGLE, 300).withoutItems();
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How solid the model looks.", 0.7, 0.1, 1, 0.05).min(0.05).max(1);
    private final EnumSetting<Fill> playerFill = fill("Player fill", "What players are painted with.");
    private final EnumSetting<Fill> mobFill = fill("Mob fill", "What mobs are painted with.");
    private final TextSetting pictureFile = new TextSetting("Picture file", "The picture the Picture fill shows. Type the "
        + "name of a PNG you have put in the " + PictureFile.FOLDER_WORDS + " folder such as stars. Empty shows the "
        + "picture the client comes with.", "")
        .visibleWhen(this::paintsPicture);
    private final NumberSetting playerScale = new NumberSetting("Player scale",
        "Makes player models bigger or smaller.", 1, 0.25, 3, 0.05, "x").min(0.05);

    private final BoolSetting crystals = new BoolSetting("Crystals",
        "Reshapes and recolours end crystals.", false);
    private final NumberSetting crystalScale = new NumberSetting("Crystal scale",
        "How big a crystal is drawn.", 0.6, 0.1, 2, 0.05, "x").min(0.05)
        .under(crystals);
    private final NumberSetting crystalBounce = new NumberSetting("Crystal bounce",
        "How high a crystal bobs up and down.", 0.6, 0, 2, 0.05, "x")
        .under(crystals);
    private final NumberSetting crystalSpin = new NumberSetting("Crystal spin",
        "How fast a crystal turns.", 0.3, 0, 2, 0.05, "x")
        .under(crystals);
    private final BoolSetting crystalTexture = new BoolSetting("Crystal texture",
        "Keeps the crystal texture under the colour. Off paints it flat.", true)
        .under(crystals);
    private final ColorSetting crystalColor = new ColorSetting("Crystal colour",
        "Colour tinted over a crystal.", 272, 0.47f, 1f, false)
        .under(crystals);

    private final BoolSetting hand = new BoolSetting("Hand",
        "Recolours your own arm in first person.", false);
    private final BoolSetting handTexture = new BoolSetting("Hand texture",
        "Keeps your skin on the arm under the colour. Off paints it flat.", false)
        .under(hand);
    private final ColorSetting handColor = new ColorSetting("Hand colour",
        "Colour of the arm.", 272, 0.47f, 1f, false)
        .under(hand);
    private final NumberSetting handOpacity = new NumberSetting("Hand opacity",
        "How solid the arm looks.", 0.6, 0.1, 1, 0.05).min(0.05).max(1)
        .under(hand);

    private final PictureFile picture = new PictureFile(this::takePicture);

    public Chams() {
        super("Chams", "Draws player and mob models through walls.", Category.RENDER);
        addSettings(filter.settings());
        addSettings(self, throughWalls);
        addSettings(colors.settings());
        addSettings(opacity, playerFill, mobFill, pictureFile, playerScale,
            crystals, crystalScale, crystalBounce, crystalSpin, crystalTexture, crystalColor,
            hand, handTexture, handColor, handOpacity);
        searchTags("see through", "models", "wallhack", "picture", "galaxy");
    }

    private static EnumSetting<Fill> fill(String name, String description) {
        return new EnumSetting<>(name, description, Fill.MODEL)
            .describe(Fill.MODEL, "The skin or texture under the colour.")
            .describe(Fill.FLAT, "One flat colour.")
            .describe(Fill.PICTURE, "A picture seen through the model and tinted by the colour.");
    }

    @Override
    protected void onDisable() {
        letGoOfPicture();
    }

    // The named picture is looked at for changes only whilst a fill shows it.
    @Subscribe
    private void onTick(TickEvent event) {
        if (paintsPicture()) {
            picture.look(pictureFile.getValue());
        } else {
            letGoOfPicture();
        }
    }

    private void letGoOfPicture() {
        picture.release();
        ChamsPicture.show(null);
    }

    private void takePicture(NativeImage pixels, String problem) {
        ChamsPicture.show(pixels);
        if (problem != null) {
            ChatUtil.error(problem + " Chams shows the picture it comes with.");
        }
    }

    private boolean paintsPicture() {
        return playerFill.is(Fill.PICTURE) || mobFill.is(Fill.PICTURE);
    }

    public boolean applies(Entity entity) {
        if (!isEnabled() || !(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (entity == mc.player) {
            return self.isOn() && AwayView.ownBodyVisible();
        }
        return filter.matches(entity);
    }

    public boolean throughWalls() {
        return throughWalls.isOn();
    }

    // True when an entity loses its texture for a flat colour.
    public boolean flat(Entity entity) {
        return fillOf(entity) == Fill.FLAT;
    }

    // True when an entity shows the picture in place of its texture.
    public boolean picture(Entity entity) {
        return fillOf(entity) == Fill.PICTURE;
    }

    private Fill fillOf(Entity entity) {
        return (entity instanceof Player ? playerFill : mobFill).getValue();
    }

    public int tintFor(Entity entity) {
        return ColorUtil.fade(colors.colorOf(entity), opacity.getFloat());
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
