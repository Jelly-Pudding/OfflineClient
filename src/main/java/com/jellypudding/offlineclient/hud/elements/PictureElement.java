package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.render.PictureFile;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;

// Read off the game thread and read again whenever the file changes.
public final class PictureElement extends HudElement {

    private static final int SIDE = 64;

    private static final int SAMPLE_FILL = 0x60808890;
    private static final int SAMPLE_EDGE = 0xC0B0B8C0;

    private final TextSetting file;
    private final PictureFile picture = new PictureFile(this::take);

    private DynamicTexture texture;
    private int across = SIDE;
    private int down = SIDE;
    private String problem;

    public PictureElement(int number) {
        super("Picture" + ordinal(number), "A picture from a file drawn on the screen.", false, 50, 50);
        file = new TextSetting(getName() + " file", "The picture it draws. Type the name of a PNG you have put in the "
            + PictureFile.FOLDER_WORDS + " folder such as logo.", "");
        add(file);
    }

    @Override
    public boolean visible() {
        if (!isActive()) {
            release();
            return false;
        }
        picture.look(file.getValue());
        return texture != null || problem != null || editing();
    }

    // On the game thread whenever what the file holds has changed.
    private void take(NativeImage pixels, String found) {
        closeTexture();
        problem = found;
        if (pixels != null) {
            double scale = (double) SIDE / Math.max(pixels.getWidth(), pixels.getHeight());
            across = Math.max(1, (int) Math.round(pixels.getWidth() * scale));
            down = Math.max(1, (int) Math.round(pixels.getHeight() * scale));
            texture = new DynamicTexture(() -> "OfflineClient picture", pixels);
        }
    }

    // A picture drawn smaller than it is gets smoothed. One drawn larger keeps its pixels sharp.
    private GpuSampler sampler() {
        boolean shrunk = texture.getPixels().getWidth() > boxWidth() * OfflineClient.MC.getWindow().getGuiScale();
        return RenderSystem.getSamplerCache().getClampToEdge(shrunk ? FilterMode.LINEAR : FilterMode.NEAREST);
    }

    private void closeTexture() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }

    private void release() {
        closeTexture();
        problem = null;
        picture.release();
    }

    @Override
    public void discard() {
        release();
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        if (texture != null) {
            context.blit(texture.getTextureView(), sampler(), 0, 0, across, down, 0, 1, 0, 1);
        } else if (problem != null) {
            write(context, font, problem, 0, 0, PROBLEM_INK);
        } else {
            context.fill(0, 0, SIDE, SIDE, SAMPLE_FILL);
            context.outline(0, 0, SIDE, SIDE, SAMPLE_EDGE);
        }
    }

    @Override
    public int width(Font font) {
        if (texture != null) {
            return across;
        }
        return problem == null ? SIDE : font.width(problem);
    }

    @Override
    public int height(Font font) {
        if (texture != null) {
            return down;
        }
        return problem == null ? SIDE : font.lineHeight;
    }
}
