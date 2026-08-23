package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.gui.WindowGuiScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class HudModule extends Module {

    private static final int EDGE = 3;
    private static final int LINE = 10;

    // A long list shrinks rather than running off the bottom of the screen.
    private static final float MIN_SCALE = 0.5f;

    private final BoolSetting watermark = new BoolSetting("Watermark",
        "Client name and version in the top left.", false);
    private final ColorSetting watermarkColor = new ColorSetting("Watermark colour",
        "Colour of the client name.", 200, true)
        .visibleWhen(watermark::isOn);
    private final BoolSetting moduleList = new BoolSetting("Module list",
        "Enabled modules listed in the top right.", false);
    private final ColorSetting moduleListColor = new ColorSetting("List colour",
        "Colour of the module names.", 200, true)
        .visibleWhen(moduleList::isOn);
    private final BoolSetting info = new BoolSetting("Info bar",
        "Coordinates and direction and speed and FPS in the bottom left.", false);

    public HudModule() {
        super("HUD", "The overlay you see whilst playing.", Category.MISC);
        addSettings(watermark, watermarkColor, moduleList, moduleListColor, info);
    }

    public boolean isWatermarkOn() {
        return isEnabled() && watermark.isOn();
    }

    // The windowed ClickGUI puts the client name in this exact corner so the two
    // would stack up. Asked each frame rather than pushed so no screen switch can
    // leave the watermark hidden.
    private static boolean cornerTaken() {
        return OfflineClient.MC.gui.screen() instanceof WindowGuiScreen;
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

        if (info.isOn() && inGame()) {
            renderInfoBar(context, font);
        }
    }

    private void renderWatermark(GuiGraphicsExtractor context, Font font) {
        if (watermarkColor.isRainbow()) {
            RenderUtil.rainbowText(context, font, OfflineClient.NAME, EDGE, EDGE);
        } else {
            context.text(font, OfflineClient.NAME, EDGE, EDGE, watermarkColor.getColor(), true);
        }
        int offset = font.width(OfflineClient.NAME) + 6;
        context.text(font, "v" + OfflineClient.VERSION, EDGE + offset, EDGE, 0xFFB0B0C0, true);
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
        float scale = Math.clamp((float) room / (enabled.size() * LINE), MIN_SCALE, 1f);

        boolean scaled = scale < 1f;
        if (scaled) {
            // Shrink about the top right corner so the list stays pinned there.
            context.pose().pushMatrix();
            context.pose().translate(width, EDGE);
            context.pose().scale(scale, scale);
            context.pose().translate(-width, -EDGE);
        }

        // One colour for the whole list. A per row offset reads as a mistake.
        int color = moduleListColor.getColor();
        int y = EDGE;
        for (Module module : enabled) {
            String name = module.getDisplayName();
            context.text(font, name, width - font.width(name) - EDGE, y, color, true);
            y += LINE;
        }

        if (scaled) {
            context.pose().popMatrix();
        }
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

        int y = context.guiHeight() - 12;
        context.text(font, line, EDGE, y, 0xFFB0B0C0, true);
    }
}
