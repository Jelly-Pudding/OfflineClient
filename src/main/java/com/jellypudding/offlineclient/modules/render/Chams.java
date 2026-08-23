package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

// EntityRendererMixin flags the target and LivingEntityRendererMixin swaps the render type.
public final class Chams extends Module {

    // Looked up once per entity every frame.
    private static volatile Chams instance;

    private final BoolSetting players = new BoolSetting("Players",
        "Show other players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Show mobs.", false);
    private final BoolSetting self = new BoolSetting("Self",
        "Show your own body in third person.", false);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Draw the model over blocks instead of only tinting it.", true);
    private final ColorSetting color = new ColorSetting("Color",
        "Model colour.", 300, false);
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How solid the model looks.", 0.7, 0.1, 1, 0.05).min(0.05).max(1);
    private final BoolSetting friendColor = new BoolSetting("Friend color",
        "Paint friends blue instead.", true);

    public Chams() {
        super("Chams", "Draws player and mob models through walls.", Category.RENDER);
        addSettings(players, mobs, self, throughWalls, color, opacity, friendColor);
        searchTags("see through", "models", "wallhack");
        instance = this;
    }

    // Null before the client has started.
    public static Chams get() {
        return instance;
    }

    public boolean applies(Entity entity) {
        if (!isEnabled() || !(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (entity == mc.player) {
            return self.isOn() && showsSelf();
        }
        if (entity instanceof Player player) {
            return players.isOn() && !player.isSpectator();
        }
        return mobs.isOn();
    }

    // The player's own model is only on screen in third person or Freecam.
    private boolean showsSelf() {
        return Modules.enabled(Freecam.class) || !mc.options.getCameraType().isFirstPerson();
    }

    public boolean throughWalls() {
        return throughWalls.isOn();
    }

    public int tintFor(Entity entity) {
        int base = color.getColor();
        if (friendColor.isOn() && entity instanceof Player player
            && OfflineClient.INSTANCE.getFriendManager().isFriend(player.getGameProfile().name())) {
            base = EntityUtil.FRIEND_COLOR;
        }
        return ColorUtil.withAlpha(base, (int) (opacity.getValue() * 255));
    }
}
