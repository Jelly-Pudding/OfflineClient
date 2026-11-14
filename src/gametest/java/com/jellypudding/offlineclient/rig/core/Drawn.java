package com.jellypudding.offlineclient.rig.core;

// One text or one plain rectangle of a frame in the pixels a vanilla screen is laid out in. The
// clip is the part of the screen it may show in and is null when nothing clips it.
public record Drawn(boolean isText, String text, Box box, Box clip, int colour) {

    private static final int ALPHA_SHIFT = 24;
    private static final int ALPHA_MASK = 0xFF;

    // A rectangle with its right and bottom edges left out.
    public record Box(int left, int top, int right, int bottom) {

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }

        public boolean isEmpty() {
            return right <= left || bottom <= top;
        }

        public boolean holds(int x, int y) {
            return x >= left && x < right && y >= top && y < bottom;
        }

        public boolean holds(Box other) {
            return other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom;
        }

        public Box cut(Box other) {
            return new Box(Math.max(left, other.left), Math.max(top, other.top), Math.min(right, other.right),
                Math.min(bottom, other.bottom));
        }

        public long area() {
            return isEmpty() ? 0 : (long) width() * height();
        }

        @Override
        public String toString() {
            return left + " " + top + " " + right + " " + bottom;
        }
    }

    public int alpha() {
        return colour >>> ALPHA_SHIFT & ALPHA_MASK;
    }

    // The part that can be seen.
    public Box shown() {
        return clip == null ? box : box.cut(clip);
    }
}
