package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

// Holds pictures of the game against each other.
final class Pictures {

    // A colour channel must move this far before a pixel counts as changed.
    private static final int CLEARLY = 32;
    private static final int CHANNELS = 3;
    private static final int CHANNEL_BITS = 8;
    private static final int CHANNEL_MASK = 0xFF;
    // Pixels this near to one that moves by itself count as moving too.
    private static final int RESTLESS_REACH = 3;

    private record Picture(int width, int[] pixels) {
    }

    private Pictures() {
    }

    // The share of the picture that differs between two shots. A third shot of the scene as it was
    // in the first says what moves by itself there such as a spinning item. Those pixels and
    // the ones round them are left out. One for pictures of two sizes.
    static double changed(Path before, Path after, Path still) {
        Picture first = read(before);
        Picture second = read(after);
        Picture third = read(still);
        if (first.pixels().length != second.pixels().length || first.pixels().length != third.pixels().length) {
            return 1;
        }
        boolean[] restless = spread(differing(first, third), first.width());
        boolean[] moved = differing(first, second);
        int counted = 0;
        for (int at = 0; at < moved.length; at++) {
            if (moved[at] && !restless[at]) {
                counted++;
            }
        }
        return (double) counted / moved.length;
    }

    private static boolean[] differing(Picture one, Picture two) {
        boolean[] differing = new boolean[one.pixels().length];
        for (int at = 0; at < differing.length; at++) {
            differing[at] = differs(one.pixels()[at], two.pixels()[at]);
        }
        return differing;
    }

    private static boolean differs(int one, int two) {
        for (int channel = 0; channel < CHANNELS; channel++) {
            int shift = channel * CHANNEL_BITS;
            if (Math.abs((one >> shift & CHANNEL_MASK) - (two >> shift & CHANNEL_MASK)) > CLEARLY) {
                return true;
            }
        }
        return false;
    }

    // Every marked pixel marks the square of pixels round it as well.
    private static boolean[] spread(boolean[] marked, int width) {
        int height = marked.length / width;
        boolean[] wide = new boolean[marked.length];
        for (int at = 0; at < marked.length; at++) {
            if (!marked[at]) {
                continue;
            }
            int column = at % width;
            int row = at / width;
            for (int y = Math.max(0, row - RESTLESS_REACH); y <= Math.min(height - 1, row + RESTLESS_REACH); y++) {
                for (int x = Math.max(0, column - RESTLESS_REACH); x <= Math.min(width - 1, column + RESTLESS_REACH);
                     x++) {
                    wide[y * width + x] = true;
                }
            }
        }
        return wide;
    }

    private static Picture read(Path picture) {
        try (InputStream file = Files.newInputStream(picture); NativeImage image = NativeImage.read(file)) {
            return new Picture(image.getWidth(), image.getPixels());
        } catch (IOException e) {
            throw new RigFailure("The picture " + picture + " could not be read", e);
        }
    }
}
