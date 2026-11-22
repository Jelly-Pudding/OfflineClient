package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ConfigManager;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.util.Armed;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Flash;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

// Reached from the title screen. Puts the client back into a known good
// state when a saved setting makes the game unplayable.
public final class RecoveryScreen extends Screen {

    // Confirm marks an action that throws away something you built up and needs a second click.
    private record Action(String label, String description, Runnable task, String doneMessage,
                          boolean confirm) {
    }

    private static final int BUTTON_WIDTH = 190;
    private static final int BUTTON_HEIGHT = 22;
    private static final int GAP = 8;
    private static final int BACK_WIDTH = 80;
    private static final int BACK_HEIGHT = 20;

    private static final float TITLE_SCALE = 2f;
    private static final String SUBTITLE = "Puts the client back into a known good state.";

    private static final int TITLE_ROOM = 70;
    private static final int FOOT_ROOM = 24;

    private static final int TICK_GAP = 4;

    private final Screen parent;
    private final Action[] actions;

    private int done = -1;
    private long doneUntil;

    private final Armed<Integer> armed = new Armed<>();

    public RecoveryScreen(Screen parent) {
        super(Component.literal("Recovery"));
        this.parent = parent;
        ConfigManager config = OfflineClient.INSTANCE.getConfigManager();
        actions = new Action[] {
            new Action("Reset module settings",
                "Every module off with default settings and binds.",
                config::resetModules, "Module settings reset", true),
            new Action("Reset GUI layout",
                "Panels back to their starting spots and sizes.",
                config::resetGuiLayout, "GUI layout reset", false),
            new Action("Clear friends",
                "Empties the friends list and the enemies list.",
                config::clearFriends, "Friends and enemies cleared", true),
            new Action("Reset everything",
                "Back to a completely fresh client.",
                config::resetEverything, "Everything reset", true),
        };
    }

    private int listHeight() {
        return actions.length * (BUTTON_HEIGHT + GAP) - GAP;
    }

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

        Font font = ClientFonts.guiAtGameScale();
        renderTitle(context, font);

        String hovering = null;
        boolean hoveringArmed = false;
        int y = topY();
        for (int i = 0; i < actions.length; i++) {
            Action action = actions[i];
            boolean hovered = overButton(mouseX, mouseY, y);
            boolean waiting = armed.isArmed(i);
            float success = done == i ? Flash.strength(doneUntil) : 0f;

            int fill = ColorUtil.lerp(hovered ? GuiTheme.bgRowHover() : GuiTheme.bgPanel(),
                GuiTheme.GREEN, success * 0.2f);
            int edge = waiting ? GuiTheme.RED
                : ColorUtil.lerp(hovered ? GuiTheme.accent() : GuiTheme.edge(), GuiTheme.GREEN, success);
            RenderUtil.roundedBorderedRect(context, buttonX(), y,
                buttonX() + BUTTON_WIDTH, y + BUTTON_HEIGHT, GuiTheme.CORNER, fill, edge);
            context.guiRenderState.up();

            int textY = GuiTheme.textY(y, BUTTON_HEIGHT);
            if (success > 0) {
                renderDone(context, font, action.doneMessage(), textY, success);
            } else {
                String label = waiting ? "Click again to confirm" : action.label();
                int labelColor = waiting ? GuiTheme.RED_TEXT
                    : hovered ? GuiTheme.accentText() : GuiTheme.text();
                context.centeredText(font, label, width / 2, textY, labelColor);
            }
            if (hovered) {
                hovering = action.description();
                hoveringArmed = waiting;
            }
            y += BUTTON_HEIGHT + GAP;
        }

        if (hovering != null) {
            context.centeredText(font, hovering, width / 2, topY() + listHeight() + 8,
                hoveringArmed ? GuiTheme.RED_TEXT : GuiTheme.textDim());
        }

        GuiTheme.button(context, font, backX(), backY(), BACK_WIDTH, BACK_HEIGHT, "back",
            overBack(mouseX, mouseY), true);
    }

    private void renderDone(GuiGraphicsExtractor context, Font font, String message, int textY,
                            float strength) {
        int left = width / 2 - (RenderUtil.TICK_SIZE + TICK_GAP + font.width(message)) / 2;
        RenderUtil.tick(context, left, textY + 1, ColorUtil.fade(GuiTheme.GREEN, strength));
        context.text(font, message, left + RenderUtil.TICK_SIZE + TICK_GAP, textY,
            ColorUtil.lerp(GuiTheme.text(), GuiTheme.GREEN, strength));
    }

    private void renderTitle(GuiGraphicsExtractor context, Font font) {
        String title = OfflineClient.NAME + " Recovery";
        Font titleFont = ClientFonts.guiAt(TITLE_SCALE);
        int fullWidth = titleFont.width(title);
        float x = width / 2f - fullWidth * TITLE_SCALE / 2f;
        int y = topY() - TITLE_ROOM + 12;

        RenderUtil.gradientTextScaled(context, titleFont, title, x, y,
            GuiTheme.accentText(), GuiTheme.accent(), TITLE_SCALE);

        int ruleY = y + (int) (GuiTheme.TEXT_HEIGHT * TITLE_SCALE) + 7;
        int half = (int) (fullWidth * TITLE_SCALE / 2);
        rule(context, width / 2 - half, width / 2 + half, ruleY);
        context.centeredText(font, SUBTITLE, width / 2, ruleY + 7, GuiTheme.textDim());
    }

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
        if (done != -1 && now > doneUntil) {
            done = -1;
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
        Action action = actions[index];
        click();

        if (action.confirm() && !armed.press(index)) {
            done = -1;
            return;
        }

        armed.disarm();
        action.task().run();
        done = index;
        doneUntil = System.currentTimeMillis() + Flash.SHOWN_MS;
    }

    private static void click() {
        OfflineClient.MC.getSoundManager()
            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
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
