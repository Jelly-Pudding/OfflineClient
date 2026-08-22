package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.gui.GuiTheme;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class HudModule extends Module {

    private final BoolSetting watermark = new BoolSetting("Watermark",
        "Client name and version in the top left.", false);
    private final BoolSetting moduleList = new BoolSetting("Module list",
        "Enabled modules listed in the top right.", false);
    private final BoolSetting info = new BoolSetting("Info bar",
        "Coordinates and direction and speed and FPS in the bottom left.", false);

    public HudModule() {
        super("HUD", "The overlay you see while playing.", Category.MISC);
        addSettings(watermark, moduleList, info);
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        GuiGraphicsExtractor context = event.getContext();
        Font font = mc.font;

        if (watermark.isOn()) {
            RenderUtil.gradientText(context, font, OfflineClient.NAME, 3, 3,
                GuiTheme.accent(), GuiTheme.accent(12));
            int offset = font.width(OfflineClient.NAME) + 6;
            context.text(font, "v" + OfflineClient.VERSION, 3 + offset, 3, 0xFFB0B0C0, true);
        }

        if (moduleList.isOn()) {
            renderModuleList(context, font);
        }

        if (info.isOn() && inGame()) {
            renderInfoBar(context, font);
        }
    }

    private void renderModuleList(GuiGraphicsExtractor context, Font font) {
        List<Module> enabled = new ArrayList<>(OfflineClient.INSTANCE.getModuleManager().getEnabled());
        enabled.removeIf(m -> m instanceof HudModule);
        enabled.sort(Comparator.comparingInt((Module m) -> font.width(m.getDisplayName())).reversed());

        int y = 3;
        int i = 0;
        for (Module module : enabled) {
            String name = module.getDisplayName();
            int x = context.guiWidth() - font.width(name) - 3;
            context.text(font, name, x, y, GuiTheme.accent(i * 3), true);
            y += 10;
            i++;
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
        context.text(font, line, 3, y, 0xFFB0B0C0, true);
    }
}
