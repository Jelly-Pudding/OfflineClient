package com.jellypudding.offlineclient.render;

// Where a map sits on the screen and the land it shows. The middle of the box shows the centre
// spot and the map turns about it. With no turn north points up.
public final class MapView {

    private final double centreX;
    private final double centreZ;
    private final double zoom;
    private final float turn;
    private final int left;
    private final int top;
    private final int width;
    private final int height;
    private final double cos;
    private final double sin;

    // The zoom is how many blocks each pixel covers and the turn is in radians.
    public MapView(double centreX, double centreZ, double zoom, float turn, int left, int top, int width,
                   int height) {
        this.centreX = centreX;
        this.centreZ = centreZ;
        this.zoom = zoom;
        this.turn = turn;
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        cos = Math.cos(turn);
        sin = Math.sin(turn);
    }

    // The turn that puts the way you face at the top. Yaw nought looks south.
    public static float facingUp(float yaw) {
        return (float) (Math.PI - Math.toRadians(yaw));
    }

    public double centreX() {
        return centreX;
    }

    public double centreZ() {
        return centreZ;
    }

    public double zoom() {
        return zoom;
    }

    public float turn() {
        return turn;
    }

    public int left() {
        return left;
    }

    public int top() {
        return top;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public float middleX() {
        return left + width / 2f;
    }

    public float middleY() {
        return top + height / 2f;
    }

    // How many pixels the land must reach from the middle across and down. A turning map needs
    // its corners in every direction.
    public double reachAcross() {
        return turn == 0 ? width / 2.0 : Math.hypot(width, height) / 2;
    }

    public double reachDown() {
        return turn == 0 ? height / 2.0 : Math.hypot(width, height) / 2;
    }

    // Where a world spot lands on the screen.
    public float screenX(double x, double z) {
        return middleX() + (float) turnedX((x - centreX) / zoom, (z - centreZ) / zoom);
    }

    public float screenY(double x, double z) {
        return middleY() + (float) turnedY((x - centreX) / zoom, (z - centreZ) / zoom);
    }

    // A world direction as it points on the screen.
    public double turnedX(double dx, double dz) {
        return dx * cos - dz * sin;
    }

    public double turnedY(double dx, double dz) {
        return dx * sin + dz * cos;
    }

    // The world spot under a screen spot.
    public double worldX(double screenX, double screenY) {
        return centreX + ((screenX - middleX()) * cos + (screenY - middleY()) * sin) * zoom;
    }

    public double worldZ(double screenX, double screenY) {
        return centreZ + ((screenY - middleY()) * cos - (screenX - middleX()) * sin) * zoom;
    }

    // True for a screen spot this many pixels or more inside the edge.
    public boolean inside(double screenX, double screenY, double inset) {
        return screenX >= left + inset && screenX <= left + width - inset
            && screenY >= top + inset && screenY <= top + height - inset;
    }

    // A screen spot outside the map pulled back along the line from the middle until it sits
    // this many pixels inside the edge. Hands back how far along the line that is.
    public double toEdge(double screenX, double screenY, double inset) {
        double dx = Math.abs(screenX - middleX());
        double dy = Math.abs(screenY - middleY());
        double across = dx == 0 ? Double.MAX_VALUE : (width / 2.0 - inset) / dx;
        double down = dy == 0 ? Double.MAX_VALUE : (height / 2.0 - inset) / dy;
        return Math.min(1, Math.min(across, down));
    }
}
