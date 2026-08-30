package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.gui.WindowGuiScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class HudModule extends Module {

    private static final int EDGE = 3;
    private static final int LINE = 10;

    // The floor for the automatic shrink. Text below this is unreadable.
    private static final float MIN_SCALE = 0.5f;

    private final BoolSetting watermark = new BoolSetting("Watermark",
        "Client name and version in the top left.", false);
    private final ColorSetting watermarkColor = new ColorSetting("Watermark colour",
        "Colour of the client name.", 200, false)
        .under(watermark);
    private final NumberSetting watermarkScale = new NumberSetting("Watermark scale",
        "Text size of the watermark.", 0.85, 0.5, 2, 0.05, "x")
        .under(watermark);
    private final BoolSetting moduleList = new BoolSetting("Module list",
        "Enabled modules listed in the top right.", false);
    private final ColorSetting moduleListColor = new ColorSetting("List colour",
        "Colour of the module names.", 200, false)
        .under(moduleList);
    private final NumberSetting moduleListScale = new NumberSetting("List scale",
        "Text size of the module list.", 0.85, 0.5, 2, 0.05, "x")
        .under(moduleList);
    private final BoolSetting info = new BoolSetting("Info bar",
        "Coordinates and direction and speed and FPS in the bottom left.", false);
    private final NumberSetting infoScale = new NumberSetting("Info scale",
        "Text size of the info bar.", 0.85, 0.5, 2, 0.05, "x")
        .under(info);
    private final BoolSetting hideInChat = new BoolSetting("Hide whilst typing",
        "Takes the info bar away whilst the chat box is open.", true)
        .under(info);

    public HudModule() {
        super("HUD", "The overlay you see whilst playing.", Category.MISC);
        addSettings(watermark, watermarkColor, watermarkScale,
            moduleList, moduleListColor, moduleListScale, info, infoScale, hideInChat);
    }

    // The windowed ClickGUI draws the client name in this same corner.
    private static boolean cornerTaken() {
        return OfflineClient.MC.gui.screen() instanceof WindowGuiScreen;
    }

    /**
     * Scales the drawing about a fixed point. A corner anchor keeps its margin
     * at any scale. True when a matrix was pushed and the caller owes a
     * popScaled.
     */
    private static boolean pushScaled(GuiGraphicsExtractor context, float scale,
                                      float anchorX, float anchorY) {
        if (scale == 1f) {
            return false;
        }
        context.pose().pushMatrix();
        context.pose().translate(anchorX, anchorY);
        context.pose().scale(scale, scale);
        context.pose().translate(-anchorX, -anchorY);
        return true;
    }

    private static void popScaled(GuiGraphicsExtractor context, boolean pushed) {
        if (pushed) {
            context.pose().popMatrix();
        }
    }

    // The chat box covers the bottom left corner the info bar sits in.
    private static boolean typing() {
        return OfflineClient.MC.gui.screen() instanceof ChatScreen;
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        GuiGraphicsExtractor context = event.getContext();
        Font font = mc.font;

        if (watermark.isOn() && !cornerTaken()) {
            renderWatermark(context, font);
        }

        if (moduleList.isOn()) {
            renderModuleList(context, font);
        }

        if (info.isOn() && inGame() && !(hideInChat.isOn() && typing())) {
            renderInfoBar(context, font);
        }
    }

    private void renderWatermark(GuiGraphicsExtractor context, Font font) {
        boolean pushed = pushScaled(context, watermarkScale.getFloat(), EDGE, EDGE);

        if (watermarkColor.isRainbow()) {
            RenderUtil.rainbowText(context, font, OfflineClient.NAME, EDGE, EDGE);
        } else {
            context.text(font, OfflineClient.NAME, EDGE, EDGE, watermarkColor.getColor(), true);
        }
        int offset = font.width(OfflineClient.NAME) + 6;
        context.text(font, "v" + OfflineClient.VERSION, EDGE + offset, EDGE, RenderUtil.MUTED_TEXT, true);

        popScaled(context, pushed);
    }

    private void renderModuleList(GuiGraphicsExtractor context, Font font) {
        List<Module> enabled = new ArrayList<>(OfflineClient.INSTANCE.getModuleManager().getEnabled());
        enabled.removeIf(m -> m instanceof HudModule);
        if (enabled.isEmpty()) {
            return;
        }
        enabled.sort(Comparator.comparingInt((Module m) -> font.width(m.getDisplayName())).reversed());

        int width = context.guiWidth();
        int room = context.guiHeight() - EDGE * 2;
        float chosen = moduleListScale.getFloat();
        // The largest scale whose stack of lines still fits the screen height.
        float fit = (float) room / (enabled.size() * LINE);
        // The automatic shrink only ever goes below the chosen size.
        float scale = Math.max(Math.min(chosen, fit), Math.min(MIN_SCALE, chosen));

        boolean pushed = pushScaled(context, scale, width - EDGE, EDGE);

        int color = moduleListColor.getColor();
        int y = EDGE;
        for (Module module : enabled) {
            String name = module.getDisplayName();
            context.text(font, name, width - font.width(name) - EDGE, y, color, true);
            y += LINE;
        }

        popScaled(context, pushed);
    }

    private void renderInfoBar(GuiGraphicsExtractor context, Font font) {
        Vec3 pos = mc.player.position();
        Vec3 velocity = mc.player.getDeltaMovement();
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z) * 20;

        String coords = String.format(Locale.ROOT, "XYZ §f%.0f %.0f %.0f", pos.x, pos.y, pos.z);
        String direction = mc.player.getDirection().getName().toUpperCase(Locale.ROOT);
        String line = coords + " §8| §7" + direction
            + " §8| §7" + String.format(Locale.ROOT, "%.1f m/s", speed)
            + " §8| §7" + mc.getFps() + " fps";

        // Anchored on the bottom edge. Scaling about the top would lift the bar
        // off the corner as it shrank.
        int floor = context.guiHeight() - EDGE;
        int y = floor - font.lineHeight;
        boolean pushed = pushScaled(context, infoScale.getFloat(), EDGE, floor);
        context.text(font, line, EDGE, y, RenderUtil.MUTED_TEXT, true);
        popScaled(context, pushed);
    }
}
