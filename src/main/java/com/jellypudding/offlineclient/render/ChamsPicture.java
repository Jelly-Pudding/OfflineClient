package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Map;

// The picture the Chams picture fill shows through each model. The client comes with one and a PNG
// of the player's own stands in for it whilst one is named. The picture is fixed to the screen and
// the model only decides where it shows.
public final class ChamsPicture {

    private static final Identifier BUILT_IN = Identifier.parse("offlineclient:textures/chams_picture.png");
    private static final Identifier OWN = Identifier.parse("offlineclient:chams_picture");

    // Model textures come from every skin the server sends. The type least lately used goes first.
    private static final int MOST_TYPES = 256;

    private record Key(Identifier texture, Identifier picture, boolean throughWalls, boolean solid) {
    }

    // Render thread only.
    private static final Map<Key, RenderType> TYPES = new BoundedMap<>(MOST_TYPES, true);

    private static Identifier picture = BUILT_IN;
    private static boolean warned;

    private ChamsPicture() {
    }

    // The type that paints a model with the picture. Null when the picture shader cannot be made and
    // the model keeps its own fill.
    public static RenderType renderType(Identifier texture, boolean throughWalls) {
        if (ShaderPipelines.compiled(pipeline(throughWalls)) == null) {
            if (!warned) {
                warned = true;
                ChatUtil.error("The Chams picture could not be drawn. Models keep their own skin until the game restarts.");
            }
            return null;
        }
        return TYPES.computeIfAbsent(new Key(texture, picture, throughWalls, EntityPipelines.solidPhase()),
            ChamsPicture::create);
    }

    // Shows the player's own picture in place of the one the client comes with. Null goes back to that one.
    public static void show(NativeImage pixels) {
        if (pixels == null) {
            if (picture == OWN) {
                OfflineClient.MC.getTextureManager().release(OWN);
                picture = BUILT_IN;
            }
            return;
        }
        OfflineClient.MC.getTextureManager().register(OWN, new DynamicTexture(() -> "OfflineClient Chams picture", pixels));
        picture = OWN;
    }

    // The model texture cuts out the shape and gives the outline pass the model.
    private static RenderType create(Key key) {
        RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(pipeline(key.throughWalls()))
            .withTexture("Sampler0", key.texture())
            .withTexture(ShaderPipelines.PICTURE_SAMPLER, key.picture(),
                () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
            .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
            .sortOnUpload();
        if (key.solid()) {
            setup.withForcedSolidModelPhase();
        }
        return RenderType.create("offlineclient:chams_picture", setup.createRenderSetup());
    }

    private static RenderPipeline pipeline(boolean throughWalls) {
        return throughWalls ? ShaderPipelines.CHAMS_PICTURE_THROUGH_WALLS : ShaderPipelines.CHAMS_PICTURE;
    }
}
