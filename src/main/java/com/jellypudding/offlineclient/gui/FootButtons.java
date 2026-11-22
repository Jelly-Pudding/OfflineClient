package com.jellypudding.offlineclient.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class FootButtons {

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
