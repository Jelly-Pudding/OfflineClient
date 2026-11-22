package com.jellypudding.offlineclient.hud;

// The numbers are read from the game's own layout code.
public enum GamePart {
    // Whether the game draws it most of the time. Then across from the middle of the screen
    // and up from its bottom edge and the size.
    HOTBAR("Hotbar", "The game's hotbar with your other hand and the attack meter.",
        true, -91, 22, 182, 22),
    EXPERIENCE("Experience bar",
        "Your level and experience. The locator bar and a mount's jump bar take the same place.",
        true, -91, 36, 182, 12),
    HEARTS("Hearts", "Your health.", true, -91, 39, 81, 9),
    HUNGER("Hunger", "Your hunger. A mount you ride shows its health here instead.", true, 10, 39, 81, 9),
    ARMOUR("Armour bar", "Your armour points.", true, -91, 49, 81, 9),
    AIR("Air", "The bubbles you have left underwater.", false, 10, 49, 81, 9),
    ITEM_NAME("Item name", "The name that shows above the hotbar when you pick another item.",
        false, -60, 61, 120, 13);

    private static final String SWITCH = " Switch it on to move or resize or hide it. Off leaves it as the game"
        + " draws it.";

    // The x and y a part starts with. They match home on a common screen size and
    // only count once the part has been moved.
    private static final int USUAL_WIDTH = 480;
    private static final int USUAL_HEIGHT = 270;

    private final String label;
    private final String description;
    private final boolean steady;
    private final int fromMiddle;
    private final int fromBottom;
    private final int width;
    private final int height;

    GamePart(String label, String description, boolean steady, int fromMiddle, int fromBottom, int width,
             int height) {
        this.label = label;
        this.description = description;
        this.steady = steady;
        this.fromMiddle = fromMiddle;
        this.fromBottom = fromBottom;
        this.width = width;
        this.height = height;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description + SWITCH;
    }

    // One that only comes and goes takes no room whilst the game draws it itself.
    public boolean steady() {
        return steady;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Box home(int screenWidth, int screenHeight) {
        return new Box(screenWidth / 2 + fromMiddle, screenHeight - fromBottom, width, height);
    }

    public double usualX() {
        Box home = home(USUAL_WIDTH, USUAL_HEIGHT);
        return tenths(HudManager.shareOf(home.left(), width, USUAL_WIDTH));
    }

    public double usualY() {
        Box home = home(USUAL_WIDTH, USUAL_HEIGHT);
        return tenths(HudManager.shareOf(home.top(), height, USUAL_HEIGHT));
    }

    private static double tenths(double share) {
        return Math.round(share * 1000) / 10.0;
    }
}
