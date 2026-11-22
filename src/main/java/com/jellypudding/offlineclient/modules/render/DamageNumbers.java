package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Health is read once a tick and a drop shows whilst the entity flashes from a hit. The server
// sends health at most once a tick. Absorption counts as health.
public final class DamageNumbers extends Module {

    private static final class Pop {

        private final Vec3 at;
        private final String text;
        private int age;

        private Pop(Vec3 at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    private static final double RISE = 1;
    private static final double OVER_HEAD = 0.3;
    private static final int FADE_TICKS = 8;
    // The health of a hit can come a tick or two after the hit itself.
    private static final int YOUR_HIT_TICKS = 3;
    // A smaller drop is rounding in the health the server sends.
    private static final float SMALLEST = 0.05f;
    private static final int PLACES = 1;

    private final EntityFilter filter = EntityFilter.living("Shows hits on", "given numbers", true,
        EntityFilter.Pick.ALL, List.of());
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the numbers.", 1, 0.5, 3, 0.1).min(0.1);
    private final NumberSetting duration = new NumberSetting("Duration",
        "Ticks each number stays before it fades.", 24, 6, 80, 1, " ticks").min(1);
    private final ColorSetting color = new ColorSetting("Colour",
        "Colour of the numbers.", 0, false);
    private final BoolSetting yourHits = new BoolSetting("Your hits",
        "Says in chat how much health each of your hits took off.", false);

    // Health by entity id as read last tick.
    private Map<Integer, Float> health = new HashMap<>();
    private Map<Integer, Float> read = new HashMap<>();
    // The tick of your last hit on each entity whilst its health is awaited.
    private final Map<Integer, Integer> yourHitAt = new HashMap<>();
    private final List<Pop> pops = new ArrayList<>();
    private final WorldWatch world = new WorldWatch();
    private int ticks;

    public DamageNumbers() {
        super("DamageNumbers", "Shows the health each hit takes off as a number rising from the entity.",
            Category.RENDER);
        addSettings(filter.settings());
        addSettings(scale, duration, color, yourHits);
        searchTags("damage indicator", "damage esp", "hit numbers", "health");
    }

    @Override
    protected void onDisable() {
        clear();
    }

    private void clear() {
        health.clear();
        yourHitAt.clear();
        pops.clear();
    }

    @Subscribe
    private void onDamage(EntityDamageEvent event) {
        if (yourHits.isOn() && event.getSource().getEntity() == mc.player) {
            yourHitAt.put(event.getEntity().getId(), ticks);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (world.changed()) {
            clear();
        }
        ticks++;
        int life = duration.getInt() + FADE_TICKS;
        pops.removeIf(pop -> ++pop.age > life);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living) {
                readHealth(living);
            }
        }
        Map<Integer, Float> last = health;
        health = read;
        read = last;
        read.clear();
        yourHitAt.values().removeIf(at -> ticks - at > YOUR_HIT_TICKS);
    }

    private void readHealth(LivingEntity living) {
        Float before = health.get(living.getId());
        // A watched entity that just died still shows the hit that killed it.
        if (!filter.matches(living) && (before == null || living.isAlive())) {
            return;
        }
        float now = EntityUtil.totalHealth(living);
        read.put(living.getId(), now);
        // Health also falls when absorption runs out or the most it can hold shrinks.
        if (before != null && before - now >= SMALLEST && living.hurtTime > 0) {
            lost(living, before - now);
        }
    }

    private void lost(LivingEntity living, float amount) {
        String text = ChatUtil.decimal(amount, PLACES);
        pops.add(new Pop(living.position().add(0, living.getBbHeight() + OVER_HEAD, 0), text));
        if (yourHitAt.remove(living.getId()) != null && yourHits.isOn()) {
            ChatUtil.message("§7Your hit took §f" + text + " §7health off §f" + living.getName().getString() + "§7.");
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (pops.isEmpty() || !inGame() || !WorldToScreen.update()) {
            return;
        }
        float life = duration.getInt() + FADE_TICKS;
        for (Pop pop : pops) {
            float age = pop.age + event.getPartialTicks();
            Vec3 screen = WorldToScreen.project(pop.at.add(0, RISE * age / life, 0));
            if (screen == null) {
                continue;
            }
            float strength = Math.clamp((life - age) / FADE_TICKS, 0f, 1f);
            RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(), List.of(pop.text),
                List.of(ColorUtil.fade(color.getColor(), strength)),
                ColorUtil.fade(RenderUtil.LABEL_BACKGROUND, strength));
        }
    }
}
