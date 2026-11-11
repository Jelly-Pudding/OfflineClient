package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.config.BuildFrame;
import com.jellypudding.offlineclient.config.BuildTemplate;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.CornerPicker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

// Saves a build as a template for AutoBuild. Press the bind on two corners and
// then on the block the build should grow from. The facing at the last press
// becomes the front of the template.
public final class TemplateTool extends Module implements CornerPicker.Taker {

    private static final String DEFAULT_NAME = "My build";

    private final TextSetting name = new TextSetting("Name",
        "The file the template is saved as. Click to type it.", DEFAULT_NAME);
    private final BoolSetting saveBlocks = new BoolSetting("Save block types",
        "Remembers which block sits where and which way it faces. Off saves the shape alone.", true);
    private final BoxStyle boxStyle = new BoxStyle(BoxStyle.Shape.LINES, 200);

    private final CornerPicker corners = new CornerPicker();

    public TemplateTool() {
        super("TemplateTool", "Saves a build as a shape AutoBuild can put up again.", Category.WORLD);
        addSettings(name, saveBlocks);
        addSettings(boxStyle.settings());
        searchTags("template", "save build", "copy structure");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        String prompt = corners.prompt();
        return prompt != null ? prompt : "pick the origin";
    }

    @Override
    protected void onEnable() {
        corners.clear();
    }

    @Override
    public void onKeybind() {
        BlockPos corner = CornerPicker.aimedCorner(this);
        if (corner == null) {
            toggle();
            return;
        }
        markCorner(corner);
    }

    // Once both corners are down the next spot is the one the build grows from.
    @Override
    public void markCorner(BlockPos pos) {
        if (corners.done()) {
            save(pos);
            return;
        }
        corners.mark(pos);
        if (!corners.done()) {
            tell("first corner at §f" + BlockUtil.text(pos) + "§7.");
            return;
        }
        // Saving walks every block of the box in one tick.
        if (corners.withinLimit(CornerPicker.MOST_BLOCKS)) {
            tell("second corner at §f" + BlockUtil.text(pos)
                + "§7. Now press the bind on the block the build grows from.");
        }
    }

    private void save(BlockPos origin) {
        BuildFrame frame = BuildFrame.facing(origin, mc.player.getDirection());
        List<BuildTemplate.Entry> entries = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(corners.first(), corners.second())) {
            BlockState state = BlockUtil.state(pos);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                continue;
            }
            entries.add(new BuildTemplate.Entry(frame.localX(pos.getX(), pos.getZ()), frame.localY(pos.getY()),
                frame.localZ(pos.getX(), pos.getZ()), saveBlocks.isOn() ? frame.toLocal(state) : null));
        }
        if (entries.isEmpty()) {
            disable("There are no blocks between the corners.");
            return;
        }
        String file = name.getValue().isBlank() ? DEFAULT_NAME : name.getValue().trim();
        if (BuildTemplate.save(file, entries)) {
            tell("saved §f" + entries.size() + "§7 blocks as §f" + file + "§7.");
        } else {
            ChatUtil.error("Could not write the template file.");
        }
        setEnabled(false);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        corners.draw(event.getBatch(), boxStyle);
    }

    // The key does more than switch the module and cannot be a held one.
    @Override
    public boolean keySwitches() {
        return false;
    }
}
