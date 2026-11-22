package com.jellypudding.offlineclient.rig.core;

import com.jellypudding.offlineclient.rig.mixin.GuiTextRenderStateAccessor;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.joml.Matrix3x2fc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Pictures of the game and what each one holds. A picture is drawn at the size the game thinks
// its window has however small the real window is.
public final class Frames {

    public record Frame(Path picture, List<Drawn> drawn, int screenWidth, int screenHeight) {

        public List<String> texts() {
            return drawn.stream().filter(Drawn::isText).filter(each -> !each.shown().isEmpty()).map(Drawn::text)
                .toList();
        }

        public boolean shows(String words) {
            return texts().stream().anyMatch(text -> text.contains(words));
        }

        public List<String> problems() {
            return TextAudit.problems(drawn, screenWidth, screenHeight);
        }
    }

    private static final String TEXT_LIST = ".txt";
    private static final String UNSAFE = "[^a-z0-9_.]+";
    private static final int RESIZE_TICKS = 3;

    private final Rig rig;

    Frames(Rig rig) {
        this.rig = rig;
    }

    // Makes the game draw at this size. The real window keeps the size it has.
    public void drawAt(int width, int height) {
        rig.act(mc -> {
            Window window = mc.getWindow();
            if (window.getWidth() == width && window.getHeight() == height) {
                return;
            }
            window.setWidth(width);
            window.setHeight(height);
            mc.gameRenderer.resize(width, height);
            mc.resizeGui();
        });
        rig.ticks(RESIZE_TICKS);
    }

    public void guiScale(int scale) {
        rig.act(mc -> {
            mc.options.guiScale().set(scale);
            mc.resizeGui();
        });
        rig.ticks(RESIZE_TICKS);
    }

    // A picture of the game as it is this moment. It joins the result of the running case.
    public Path shot(String name) {
        int[] size = rig.ask(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        Path picture = rig.context().takeScreenshot(TestScreenshotOptions.of(fileName(name))
            .withDestinationDir(rig.results().shots()).withSize(size[0], size[1]));
        rig.addShot(rig.results().out().relativize(picture).toString().replace('\\', '/'));
        return picture;
    }

    // A picture together with every text and plain rectangle the interface drew for it. The
    // texts are also written to a file beside the picture.
    public Frame capture(String name) {
        FrameTap.arm();
        Path picture = shot(name);
        Frame frame = rig.ask(mc -> new Frame(picture, read(FrameTap.taken()), mc.getWindow().getGuiScaledWidth(),
            mc.getWindow().getGuiScaledHeight()));
        writeTexts(frame);
        return frame;
    }

    private static String fileName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll(UNSAFE, "_");
    }

    private static List<Drawn> read(List<Object> taken) {
        List<Drawn> drawn = new ArrayList<>();
        for (Object each : taken) {
            if (each instanceof GuiTextRenderState text) {
                GuiTextRenderStateAccessor parts = (GuiTextRenderStateAccessor) (Object) text;
                StringBuilder letters = new StringBuilder();
                parts.rigText().accept((index, style, codePoint) -> {
                    letters.appendCodePoint(codePoint);
                    return true;
                });
                int width = parts.rigFont().width(parts.rigText());
                drawn.add(new Drawn(true, letters.toString(), box(parts.rigX(), parts.rigY(), parts.rigX() + width,
                    parts.rigY() + parts.rigFont().lineHeight, text.pose), box(text.scissor), parts.rigColor()));
            } else if (each instanceof ColoredRectangleRenderState paint) {
                drawn.add(new Drawn(false, "", box(paint.x0(), paint.y0(), paint.x1(), paint.y1(), paint.pose()),
                    box(paint.scissorArea()), paint.col1()));
            }
        }
        return drawn;
    }

    private static Drawn.Box box(int left, int top, int right, int bottom, Matrix3x2fc pose) {
        return box(new ScreenRectangle(Math.min(left, right), Math.min(top, bottom), Math.abs(right - left),
            Math.abs(bottom - top)).transformMaxBounds(pose));
    }

    private static Drawn.Box box(ScreenRectangle area) {
        return area == null ? null : new Drawn.Box(area.left(), area.top(), area.right(), area.bottom());
    }

    private static void writeTexts(Frame frame) {
        StringBuilder lines = new StringBuilder();
        for (Drawn each : frame.drawn()) {
            if (each.isText() && !each.shown().isEmpty() && !each.text().isBlank()) {
                lines.append(each.box()).append('\t').append(each.text()).append('\n');
            }
        }
        String picture = frame.picture().toString();
        Path file = Path.of(picture.substring(0, picture.lastIndexOf('.')) + TEXT_LIST);
        try {
            Files.writeString(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RigFailure("The texts of a picture could not be written to " + file, e);
        }
    }
}
