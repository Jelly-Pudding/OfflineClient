package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;

// FastBreak and FastPlace outrun the server often enough that the guess is wrong.
public final class NoGhostBlocks extends Module {

    private final BoolSetting breaking = new BoolSetting("Breaking",
        "Leave a broken block on screen until the server removes it.", true);
    private final BoolSetting placing = new BoolSetting("Placing",
        "Leave the space clear until the server puts the block there.", true);

    public NoGhostBlocks() {
        super("NoGhostBlocks", "Waits for the server instead of guessing what a click did.",
            Category.WORLD);
        addSettings(breaking, placing);
        searchTags("ghost blocks", "resync", "desync", "block sync");
    }

    // In singleplayer the guess is always right.
    public boolean holdsBreaks() {
        return isEnabled() && breaking.isOn() && !mc.isLocalServer();
    }

    public boolean holdsPlacements() {
        return isEnabled() && placing.isOn() && !mc.isLocalServer();
    }
}
