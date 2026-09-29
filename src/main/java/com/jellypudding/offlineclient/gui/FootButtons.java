package com.jellypudding.offlineclient.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// Wide vanilla buttons centred in two rows at the foot of a screen. The disconnected screen and the
// loading screen keep the buttons the client adds there.
public final class FootButtons {

    // How far above the bottom edge each row starts. They sit as far apart as stacked vanilla buttons.
    private static final int UPPER_ROW = 52;
    private static final int LOWER_ROW = 28;

    private FootButtons() {
    }

    public static Button upper(Screen screen, Component text, Button.OnPress press) {
        return row(screen, UPPER_ROW, text, press);
    }

    public static Button lower(Screen screen, Component text, Button.OnPress press) {
        return row(screen, LOWER_ROW, text, press);
    }

    private static Button row(Screen screen, int fromBottom, Component text, Button.OnPress press) {
        return Button.builder(text, press)
            .bounds((screen.width - Button.BIG_WIDTH) / 2, screen.height - fromBottom, Button.BIG_WIDTH,
                Button.DEFAULT_HEIGHT)
            .build();
    }
}
