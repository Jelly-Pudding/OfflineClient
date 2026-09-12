package com.jellypudding.offlineclient.modules.world;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Saves a build as a template for AutoBuild. Press the bind on two corners and
// then on the block the build should grow from. The facing at the last press
// becomes the front of the template.
public final class TemplateTool extends Module {

    private final TextSetting name = new TextSetting("Name",
        "The file the template is saved as. Click to type it.", "My build");
    private final BoolSetting saveBlocks = new BoolSetting("Save block types",
        "Remembers which block sits where. Off saves the shape alone.", true);
    private final BoxStyle boxStyle = new BoxStyle(BoxStyle.Shape.LINES, 200);

    private BlockPos first;
    private BlockPos second;

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
        if (first == null) {
            return "pick a corner";
        }
        return second == null ? "pick the other corner" : "pick the origin";
    }

    @Override
    protected void onEnable() {
        first = null;
        second = null;
    }

    // Each press marks the next spot. A press on nothing switches the tool off.
    @Override
    public void onKeybind() {
        if (!isEnabled() || !inGame()) {
            toggle();
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            toggle();
            return;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        if (first == null) {
            first = pos;
            ChatUtil.message("§bTemplateTool §7first corner at §f" + text(pos) + "§7.");
        } else if (second == null) {
            second = pos;
            ChatUtil.message("§bTemplateTool §7second corner at §f" + text(pos)
                + "§7. Now press the bind on the block the build grows from.");
        } else {
            save(pos);
        }
    }

    private void save(BlockPos origin) {
        Direction front = mc.player.getDirection();
        Direction left = front.getCounterClockWise();
        List<BuildTemplate.Entry> entries = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(first, second)) {
            BlockState state = BlockUtil.state(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            BlockPos offset = pos.subtract(origin);
            int forward = offset.getX() * front.getStepX() + offset.getZ() * front.getStepZ();
            int sideways = offset.getX() * left.getStepX() + offset.getZ() * left.getStepZ();
            entries.add(new BuildTemplate.Entry(sideways, offset.getY(), forward,
                saveBlocks.isOn() ? state.getBlock() : null));
        }
        if (entries.isEmpty()) {
            ChatUtil.error("There are no blocks between the corners.");
            setEnabled(false);
            return;
        }
        try {
            BuildTemplate.save(name.getValue().trim().isEmpty() ? "My build" : name.getValue().trim(),
                entries);
            ChatUtil.message("§bTemplateTool §7saved §f" + entries.size() + "§7 blocks as §f"
                + name.getValue().trim() + "§7.");
        } catch (IOException error) {
            ChatUtil.error("Could not write the template file.");
        }
        setEnabled(false);
    }

    private static String text(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (first == null) {
            return;
        }
        AABB box = second == null ? new AABB(first)
            : AABB.encapsulatingFullBlocks(first, second);
        boxStyle.draw(event.getBatch(), box.inflate(0.005), true);
    }
}
