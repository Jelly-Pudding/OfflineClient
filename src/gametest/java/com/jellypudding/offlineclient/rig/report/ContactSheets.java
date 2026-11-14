package com.jellypudding.offlineclient.rig.report;

import com.jellypudding.offlineclient.rig.core.Outcome;
import com.jellypudding.offlineclient.rig.core.Verdict;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Puts the pictures of a run on sheets of six with the name of each above it. One sheet shows
// what six separate pictures would.
final class ContactSheets {

    private static final String FOLDER = "sheets";
    private static final String FORMAT = "png";
    private static final int COLUMNS = 2;
    private static final int ROWS = 3;
    private static final int CELL_WIDTH = 960;
    private static final int CELL_HEIGHT = 540;
    private static final int LABEL_HEIGHT = 30;
    private static final int GAP = 6;
    private static final int LABEL_SIZE = 20;
    private static final int LABEL_INSET = 6;
    private static final Color PAPER = new Color(24, 24, 28);
    private static final Color INK = new Color(235, 235, 235);
    private static final Color FAILED_INK = new Color(255, 120, 110);

    private record Picture(String label, Path file, boolean failed) {
    }

    private ContactSheets() {
    }

    // One run of sheets for each suite in the order the pictures were taken.
    static List<Path> write(Path out, List<Outcome> outcomes) throws IOException {
        Map<String, List<Picture>> suites = new LinkedHashMap<>();
        for (Outcome each : outcomes) {
            for (String shot : each.shots()) {
                String file = Path.of(shot).getFileName().toString();
                suites.computeIfAbsent(each.suite(), suite -> new ArrayList<>()).add(new Picture(
                    file.substring(0, file.lastIndexOf('.')) + "  " + each.name(), out.resolve(shot),
                    each.verdict() != Verdict.PASSED));
            }
        }
        List<Path> sheets = new ArrayList<>();
        if (suites.isEmpty()) {
            return sheets;
        }
        Files.createDirectories(out.resolve(FOLDER));
        for (Map.Entry<String, List<Picture>> suite : suites.entrySet()) {
            List<Picture> pictures = suite.getValue();
            int perSheet = COLUMNS * ROWS;
            for (int from = 0; from < pictures.size(); from += perSheet) {
                Path sheet = out.resolve(FOLDER).resolve(String.format(Locale.ROOT, "%s_%02d.png",
                    suite.getKey().toLowerCase(Locale.ROOT), from / perSheet + 1));
                draw(pictures.subList(from, Math.min(pictures.size(), from + perSheet)), sheet);
                sheets.add(sheet);
            }
        }
        return sheets;
    }

    private static void draw(List<Picture> pictures, Path sheet) throws IOException {
        int rows = (pictures.size() + COLUMNS - 1) / COLUMNS;
        int width = COLUMNS * CELL_WIDTH + (COLUMNS + 1) * GAP;
        int height = rows * (CELL_HEIGHT + LABEL_HEIGHT) + (rows + 1) * GAP;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D pen = image.createGraphics();
        pen.setColor(PAPER);
        pen.fillRect(0, 0, width, height);
        pen.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        pen.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        pen.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, LABEL_SIZE));
        for (int i = 0; i < pictures.size(); i++) {
            Picture picture = pictures.get(i);
            int left = GAP + i % COLUMNS * (CELL_WIDTH + GAP);
            int top = GAP + i / COLUMNS * (CELL_HEIGHT + LABEL_HEIGHT + GAP);
            pen.setColor(picture.failed() ? FAILED_INK : INK);
            pen.drawString(fitted(pen, picture.label()), left + LABEL_INSET, top + LABEL_HEIGHT - LABEL_INSET - 2);
            if (!Files.isRegularFile(picture.file())) {
                continue;
            }
            BufferedImage shot = ImageIO.read(picture.file().toFile());
            double scale = Math.min(CELL_WIDTH / (double) shot.getWidth(), CELL_HEIGHT / (double) shot.getHeight());
            pen.drawImage(shot, left, top + LABEL_HEIGHT, (int) Math.round(shot.getWidth() * scale),
                (int) Math.round(shot.getHeight() * scale), null);
        }
        pen.dispose();
        ImageIO.write(image, FORMAT, sheet.toFile());
    }

    // A label too long for its cell loses its end.
    private static String fitted(Graphics2D pen, String label) {
        String fitted = label;
        while (fitted.length() > 1 && pen.getFontMetrics().stringWidth(fitted) > CELL_WIDTH - 2 * LABEL_INSET) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted;
    }
}
