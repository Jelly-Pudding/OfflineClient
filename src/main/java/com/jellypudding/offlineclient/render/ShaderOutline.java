package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

// The shader outline style of a module. Each frame the module hands in what it marks with a colour
// each and the shader draws a line round their silhouettes with a soft glow past the line and
// a see through fill inside. The box style of the module says whether lines or fill or both show.
public final class ShaderOutline {

    // The widest reach the shaders search. Matches MOST_REACH in shaders/include/outline.glsl.
    public static final int MOST_REACH = 32;
    // Four colours fit in each slot. Matches PALETTE_QUADS in the same file.
    private static final int PALETTE_QUADS = 512;
    private static final int MOST_COLORS = PALETTE_QUADS * 4;
    // Four numbers then the palette. Every slot of the palette is sixteen bytes.
    private static final int CONFIG_BYTES = 16 + PALETTE_QUADS * 16;

    // The screen sized targets are shared by every module that draws the style. The last of them to
    // let go frees them.
    private static RenderTarget mask;
    private static RenderTarget rows;
    private static int holders;
    // Set for good once a shader or a draw fails. Boxes are drawn from then on.
    private static boolean broken;

    private final BoxStyle style;
    private final NumberSetting width = new NumberSetting("Outline width",
        "How many pixels wide the outline is.", 2, 1, 8, 1, " px").max(MOST_REACH);
    private final NumberSetting glow = new NumberSetting("Glow width",
        "How many pixels past the outline a soft glow fades out over. Nought draws none.", 4, 0, 16, 1, " px")
        .max(MOST_REACH);

    private final List<Entity> entities = new ArrayList<>();
    private final IntArrayList entityIds = new IntArrayList();
    private final List<AABB> boxes = new ArrayList<>();
    private final IntArrayList boxIds = new IntArrayList();
    // The colours of this frame. The number of a silhouette is the place of its colour.
    private final IntArrayList palette = new IntArrayList();
    private final Int2IntOpenHashMap idOfColor = new Int2IntOpenHashMap();

    // Kept from frame to frame whilst the module draws the style.
    private MappableRingBuffer config;
    private StagedVertexBuffer boxVertices;
    private boolean holding;

    public ShaderOutline(BoxStyle style) {
        this.style = style;
        idOfColor.defaultReturnValue(-1);
    }

    // The widths show under one choice of the module's look whilst the box style draws lines.
    public <E extends Enum<E>> ShaderOutline under(EnumSetting<E> parent, E value) {
        width.under(parent, () -> parent.is(value) && style.drawsLines());
        glow.under(parent, () -> parent.is(value) && style.drawsLines());
        return this;
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {width, glow};
    }

    // False once the shaders failed. The module then draws its boxes.
    public static boolean works() {
        return !broken;
    }

    public void entity(Entity entity, int color) {
        entities.add(entity);
        entityIds.add(idFor(color));
    }

    public void box(AABB box, int color) {
        boxes.add(box);
        boxIds.add(idFor(color));
    }

    // Draws everything handed in this frame and forgets it. Called from a Render3DEvent handler.
    public void draw(float partialTicks) {
        try {
            RenderTarget main = OfflineClient.MC.gameRenderer.mainRenderTarget();
            if ((entities.isEmpty() && boxes.isEmpty()) || broken || Conceal.hides(Conceal.Part.WORLD)
                || main.width <= 0 || main.height <= 0) {
                return;
            }
            if (!ShaderPipelines.ready(ShaderPipelines.SILHOUETTE_BOXES, ShaderPipelines.OUTLINE_ROWS,
                ShaderPipelines.OUTLINE_EDGES)) {
                fail(null);
                return;
            }
            hold();
            mask = sized(mask, "OfflineClient silhouettes", main);
            rows = sized(rows, "OfflineClient outline rows", main);
            SilhouetteMask.draw(mask, boxVertices, entities, entityIds, boxes, boxIds, partialTicks);
            outline(main);
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            entities.clear();
            entityIds.clear();
            boxes.clear();
            boxIds.clear();
            palette.clear();
            idOfColor.clear();
        }
    }

    // Lets go of the buffers whilst the module does not draw the style. The next draw makes them again.
    public void release() {
        if (!holding) {
            return;
        }
        holding = false;
        if (config != null) {
            config.close();
            config = null;
        }
        if (boxVertices != null) {
            boxVertices.close();
            boxVertices = null;
        }
        holders--;
        if (holders == 0) {
            free(mask);
            free(rows);
            mask = null;
            rows = null;
        }
    }

    // Takes a share of the shared targets and makes the buffers of this module.
    private void hold() {
        if (holding) {
            return;
        }
        holding = true;
        holders++;
        config = new MappableRingBuffer(() -> "OfflineClient outline", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE,
            CONFIG_BYTES);
        // The vertex memory starts small and grows to fit the most boxes a frame has had.
        boxVertices = new StagedVertexBuffer(() -> "OfflineClient silhouette boxes", RenderType.TRANSIENT_BUFFER_SIZE);
    }

    // A target the size of the screen made or resized on demand.
    private static RenderTarget sized(RenderTarget target, String label, RenderTarget screen) {
        if (target == null) {
            return new TextureTarget(label, screen.width, screen.height, GpuFormat.RGBA8_UNORM, null);
        }
        if (target.width != screen.width || target.height != screen.height) {
            target.resize(screen.width, screen.height);
        }
        return target;
    }

    private static void free(RenderTarget target) {
        if (target != null) {
            target.destroyBuffers();
        }
    }

    // The last colour stands in for the rest once the palette is full.
    private int idFor(int color) {
        int id = idOfColor.get(color);
        if (id >= 0) {
            return id;
        }
        if (palette.size() == MOST_COLORS) {
            return MOST_COLORS - 1;
        }
        id = palette.size();
        palette.add(color);
        idOfColor.put(color, id);
        return id;
    }

    // Finds the nearest silhouette along each row and then down each column and lays the line and
    // the glow and the fill over the screen.
    private void outline(RenderTarget main) {
        GpuBuffer settings = config.currentBuffer();
        writeConfig(settings);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "OfflineClient outline rows", rows.getColorTextureView(),
            Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(ShaderPipelines.compiled(ShaderPipelines.OUTLINE_ROWS));
            pass.setUniform(ShaderPipelines.OUTLINE_CONFIG, settings);
            pass.setUniform(ShaderPipelines.MASK_SAMPLER, mask.getColorTextureView(), nearest);
            pass.draw(3, 1, 0, 0);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "OfflineClient outline edges", main.getColorTextureView(),
            Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(ShaderPipelines.compiled(ShaderPipelines.OUTLINE_EDGES));
            pass.setUniform(ShaderPipelines.OUTLINE_CONFIG, settings);
            pass.setUniform(ShaderPipelines.MASK_SAMPLER, mask.getColorTextureView(), nearest);
            pass.setUniform(ShaderPipelines.ROWS_SAMPLER, rows.getColorTextureView(), nearest);
            pass.draw(3, 1, 0, 0);
        }
        config.rotate();
    }

    // The order and the layout are those of the OutlineConfig block in the shaders.
    private void writeConfig(GpuBuffer settings) {
        boolean lines = style.drawsLines();
        int line = lines ? Math.min(width.getInt(), MOST_REACH) : 0;
        int reach = lines ? Math.min(line + glow.getInt(), MOST_REACH) : 0;
        float fill = style.drawsSides() ? style.fillShare() : 0;
        try (GpuBufferSlice.MappedView view = settings.map(false, true)) {
            Std140Builder builder = Std140Builder.intoBuffer(view.data())
                .putInt(reach)
                .putInt(line)
                .putInt(reach - line)
                .putFloat(fill);
            for (int quad = 0; quad * 4 < palette.size(); quad++) {
                builder.putIVec4(colorAt(quad * 4), colorAt(quad * 4 + 1), colorAt(quad * 4 + 2), colorAt(quad * 4 + 3));
            }
        }
    }

    private int colorAt(int id) {
        return id < palette.size() ? palette.getInt(id) : 0;
    }

    private static void fail(RuntimeException problem) {
        broken = true;
        if (problem != null) {
            OfflineClient.LOG.error("The outline shader failed", problem);
        }
        ChatUtil.error("The outline shader could not be drawn. Boxes are drawn in its place until the game restarts.");
    }
}
