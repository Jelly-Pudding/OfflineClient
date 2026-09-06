package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ConfigManager;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

// Reached from the title screen. Puts the client back into a known good
// state when a saved setting makes the game unplayable.
public final class RecoveryScreen extends Screen {

    // Destructive marks an action that needs a second click to go through.
    private record Action(String label, String description, Runnable task, String doneMessage,
                          boolean destructive) {
    }

    private static final int BUTTON_WIDTH = 190;
    private static final int BUTTON_HEIGHT = 22;
    private static final int GAP = 8;
    private static final int BACK_WIDTH = 80;
    private static final int BACK_HEIGHT = 20;

    private static final float TITLE_SCALE = 2f;
    private static final String SUBTITLE = "Puts the client back into a known good state.";

    // Room above the buttons for the title and below them for the status lines.
    private static final int TITLE_ROOM = 70;
    private static final int FOOT_ROOM = 34;

    private static final long FLASH_MS = 900;
    private static final long STATUS_MS = 4000;
    private static final long ARM_MS = 3000;

    private final Screen parent;
    private final Action[] actions;

    private String status = "";
    private long statusUntil;

    private int flashed = -1;
    private long flashUntil;

    private int armed = -1;
    private long armedUntil;

    public RecoveryScreen(Screen parent) {
        super(Component.literal("Recovery"));
        this.parent = parent;
        ConfigManager config = OfflineClient.INSTANCE.getConfigManager();
        actions = new Action[] {
            new Action("Reset module settings",
                "Every module off with default settings and binds.",
                config::resetModules, "Module settings reset.", false),
            new Action("Reset GUI layout",
                "Panels back to their starting spots and sizes.",
                config::resetGuiLayout, "GUI layout reset.", false),
            new Action("Clear friends",
                "Empties the friends list.",
                config::clearFriends, "Friends cleared.", false),
            new Action("Reset everything",
                "Back to a completely fresh client.",
                config::resetEverything, "Everything reset.", true),
        };
    }

    private int listHeight() {
        return actions.length * (BUTTON_HEIGHT + GAP) - GAP;
    }

    // The whole column is centred. The title always keeps its room.
    private int topY() {
        int column = TITLE_ROOM + listHeight() + FOOT_ROOM + BACK_HEIGHT;
        return Math.max(TITLE_ROOM, (height - column) / 2 + TITLE_ROOM);
    }

    private int buttonX() {
        return width / 2 - BUTTON_WIDTH / 2;
    }

    private int backY() {
        return topY() + listHeight() + FOOT_ROOM;
    }

    private int backX() {
        return width / 2 - BACK_WIDTH / 2;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        context.fillGradient(0, 0, width, height, 0xB0101018, 0xD0060610);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        long now = System.currentTimeMillis();
        expire(now);

        Font font = OfflineClient.MC.font;
        renderTitle(context, font);

        String hovering = null;
        int y = topY();
        for (int i = 0; i < actions.length; i++) {
            Action action = actions[i];
            boolean hovered = overButton(mouseX, mouseY, y);
            boolean flashing = flashed == i;
            boolean waiting = armed == i;

            // The flash fades out. A repeated click still reads as a new one.
            float flash = flashing ? Math.clamp((flashUntil - now) / (float) FLASH_MS, 0f, 1f) : 0f;
            int fill = flashing
                ? ColorUtil.lerp(GuiTheme.bgPanel(), GuiTheme.GREEN, flash * 0.55f)
                : hovered ? GuiTheme.bgRowHover() : GuiTheme.bgPanel();
            int edge = flashing ? GuiTheme.GREEN
                : waiting ? 0xFFE05050
                : hovered ? GuiTheme.accent() : GuiTheme.edge();

            RenderUtil.roundedBorderedRect(context, buttonX(), y,
                buttonX() + BUTTON_WIDTH, y + BUTTON_HEIGHT, GuiTheme.CORNER, fill, edge);
            context.guiRenderState.up();

            String label = waiting ? "click again to confirm" : action.label();
            int labelColor = flashing ? GuiTheme.text()
                : waiting ? 0xFFFF9090
                : hovered ? GuiTheme.accentText() : GuiTheme.text();
            context.centeredText(font, label, width / 2, GuiTheme.textY(y, BUTTON_HEIGHT), labelColor);

            if (flashing) {
                RenderUtil.tick(context, buttonX() + 8, y + BUTTON_HEIGHT / 2 - 2, GuiTheme.GREEN);
            }
            if (hovered) {
                hovering = action.description();
            }
            y += BUTTON_HEIGHT + GAP;
        }

        int footY = topY() + listHeight() + 8;
        if (hovering != null) {
            context.centeredText(font, hovering, width / 2, footY, GuiTheme.textDim());
        }
        // The status keeps its own line. Hovering a button never hides it.
        if (!status.isEmpty()) {
            context.centeredText(font, status, width / 2, footY + 12, GuiTheme.GREEN);
        }

        boolean overBack = overBack(mouseX, mouseY);
        RenderUtil.roundedBorderedRect(context, backX(), backY(), backX() + BACK_WIDTH,
            backY() + BACK_HEIGHT, GuiTheme.CORNER,
            overBack ? GuiTheme.bgRowHover() : GuiTheme.bgPanel(),
            overBack ? GuiTheme.accent() : GuiTheme.edge());
        context.guiRenderState.up();
        context.centeredText(font, "back", width / 2, GuiTheme.textY(backY(), BACK_HEIGHT),
            overBack ? GuiTheme.accentText() : GuiTheme.text());
    }

    // The title in the accent at twice size with a rule and small print below.
    private void renderTitle(GuiGraphicsExtractor context, Font font) {
        String title = OfflineClient.NAME + " Recovery";
        int fullWidth = font.width(title);
        float x = width / 2f - fullWidth * TITLE_SCALE / 2f;
        int y = topY() - TITLE_ROOM + 12;

        RenderUtil.gradientTextScaled(context, font, title, x, y,
            GuiTheme.accentText(), GuiTheme.accent(), TITLE_SCALE);

        int ruleY = y + (int) (GuiTheme.TEXT_HEIGHT * TITLE_SCALE) + 7;
        int half = (int) (fullWidth * TITLE_SCALE / 2);
        rule(context, width / 2 - half, width / 2 + half, ruleY);
        context.centeredText(font, SUBTITLE, width / 2, ruleY + 7, GuiTheme.textDim());
    }

    // A hairline that fades out towards both ends.
    private static void rule(GuiGraphicsExtractor context, int left, int right, int y) {
        int centre = (left + right) / 2;
        int half = Math.max(1, centre - left);
        int accent = GuiTheme.accent();
        for (int x = left; x < right; x++) {
            float strength = 1f - Math.abs(x - centre) / (float) half;
            context.fill(x, y, x + 1, y + 1, ColorUtil.fade(accent, strength));
        }
        context.guiRenderState.up();
    }

    private void expire(long now) {
        if (flashed != -1 && now > flashUntil) {
            flashed = -1;
        }
        if (armed != -1 && now > armedUntil) {
            armed = -1;
        }
        if (!status.isEmpty() && now > statusUntil) {
            status = "";
        }
    }

    private boolean overButton(double mx, double my, int y) {
        return mx >= buttonX() && mx < buttonX() + BUTTON_WIDTH
            && my >= y && my < y + BUTTON_HEIGHT;
    }

    private boolean overBack(double mx, double my) {
        return mx >= backX() && mx < backX() + BACK_WIDTH
            && my >= backY() && my < backY() + BACK_HEIGHT;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (overBack(mx, my)) {
            onClose();
            return true;
        }
        int y = topY();
        for (int i = 0; i < actions.length; i++) {
            if (overButton(mx, my, y)) {
                activate(i);
                return true;
            }
            y += BUTTON_HEIGHT + GAP;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void activate(int index) {
        long now = System.currentTimeMillis();
        Action action = actions[index];
        click();

        // A wipe asks twice. Everything else goes through on the first click.
        if (action.destructive() && armed != index) {
            armed = index;
            armedUntil = now + ARM_MS;
            status = "";
            return;
        }

        armed = -1;
        action.task().run();
        flashed = index;
        flashUntil = now + FLASH_MS;
        status = action.doneMessage();
        statusUntil = now + STATUS_MS;
    }

    private static void click() {
        OfflineClient.MC.getSoundManager()
            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        OfflineClient.MC.gui.setScreen(parent);
    }
}
