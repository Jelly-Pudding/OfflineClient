package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

// A banner flag with its patterns drawn inside a tooltip.
public final class BannerPreview implements ClientTooltipComponent {

    private static final int WIDTH = 40;
    private static final int HEIGHT = 80;

    private final DyeColor base;
    private final BannerPatternLayers patterns;
    private final BannerFlagModel flag;

    public BannerPreview(DyeColor base, BannerPatternLayers patterns) {
        this.base = base;
        this.patterns = patterns;
        flag = new BannerFlagModel(OfflineClient.MC.getEntityModels().bakeLayer(ModelLayers.STANDING_BANNER_FLAG));
    }

    @Override
    public int getWidth(Font font) {
        return WIDTH;
    }

    @Override
    public int getHeight(Font font) {
        return HEIGHT;
    }

    @Override
    public void extractImage(Font font, int x, int y, int tooltipWidth, int tooltipHeight,
                             GuiGraphicsExtractor context) {
        int left = x + (tooltipWidth - WIDTH) / 2;
        context.bannerPattern(flag, base, patterns, left, y, left + WIDTH, y + HEIGHT);
    }
}
