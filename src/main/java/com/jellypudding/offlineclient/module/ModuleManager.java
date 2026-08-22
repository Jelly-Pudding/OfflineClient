package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.modules.combat.AutoArmor;
import com.jellypudding.offlineclient.modules.combat.AutoCity;
import com.jellypudding.offlineclient.modules.combat.AutoClicker;
import com.jellypudding.offlineclient.modules.combat.AutoTotem;
import com.jellypudding.offlineclient.modules.combat.AutoTrap;
import com.jellypudding.offlineclient.modules.combat.AutoWeapon;
import com.jellypudding.offlineclient.modules.combat.BowAimbot;
import com.jellypudding.offlineclient.modules.combat.Criticals;
import com.jellypudding.offlineclient.modules.combat.CrystalAura;
import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import com.jellypudding.offlineclient.modules.combat.KillAura;
import com.jellypudding.offlineclient.modules.combat.Offhand;
import com.jellypudding.offlineclient.modules.combat.SelfTrap;
import com.jellypudding.offlineclient.modules.combat.Surround;
import com.jellypudding.offlineclient.modules.combat.TriggerBot;
import com.jellypudding.offlineclient.modules.misc.AntiAfk;
import com.jellypudding.offlineclient.modules.misc.AntiSpam;
import com.jellypudding.offlineclient.modules.misc.AutoLog;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.modules.misc.FakePlayer;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.modules.misc.Notifier;
import com.jellypudding.offlineclient.modules.misc.Spam;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.modules.movement.AutoJump;
import com.jellypudding.offlineclient.modules.movement.AntiPush;
import com.jellypudding.offlineclient.modules.movement.AutoWalk;
import com.jellypudding.offlineclient.modules.movement.Blink;
import com.jellypudding.offlineclient.modules.movement.FastFall;
import com.jellypudding.offlineclient.modules.movement.Flight;
import com.jellypudding.offlineclient.modules.movement.HighJump;
import com.jellypudding.offlineclient.modules.movement.Jesus;
import com.jellypudding.offlineclient.modules.movement.LongJump;
import com.jellypudding.offlineclient.modules.movement.NoFall;
import com.jellypudding.offlineclient.modules.movement.NoKnockback;
import com.jellypudding.offlineclient.modules.movement.ElytraFly;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.NoWeb;
import com.jellypudding.offlineclient.modules.movement.Parkour;
import com.jellypudding.offlineclient.modules.movement.QuickClimb;
import com.jellypudding.offlineclient.modules.movement.SafeWalk;
import com.jellypudding.offlineclient.modules.movement.Sneak;
import com.jellypudding.offlineclient.modules.movement.Speed;
import com.jellypudding.offlineclient.modules.movement.Spider;
import com.jellypudding.offlineclient.modules.movement.Sprint;
import com.jellypudding.offlineclient.modules.movement.Step;
import com.jellypudding.offlineclient.modules.player.AntiHunger;
import com.jellypudding.offlineclient.modules.player.AutoDrop;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.AutoFish;
import com.jellypudding.offlineclient.modules.player.AutoReplenish;
import com.jellypudding.offlineclient.modules.player.AutoRespawn;
import com.jellypudding.offlineclient.modules.player.AutoTool;
import com.jellypudding.offlineclient.modules.player.ChestStealer;
import com.jellypudding.offlineclient.modules.player.GhostHand;
import com.jellypudding.offlineclient.modules.player.InvWalk;
import com.jellypudding.offlineclient.modules.player.MiddleClickExtra;
import com.jellypudding.offlineclient.modules.player.NoRotate;
import com.jellypudding.offlineclient.modules.player.Reach;
import com.jellypudding.offlineclient.modules.player.FastBreak;
import com.jellypudding.offlineclient.modules.player.FastPlace;
import com.jellypudding.offlineclient.modules.player.FastUse;
import com.jellypudding.offlineclient.modules.render.AntiBlind;
import com.jellypudding.offlineclient.modules.render.Breadcrumbs;
import com.jellypudding.offlineclient.modules.render.ChestEsp;
import com.jellypudding.offlineclient.modules.render.ClearSkies;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.modules.render.Esp;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.modules.render.Fullbright;
import com.jellypudding.offlineclient.modules.render.HoleEsp;
import com.jellypudding.offlineclient.modules.render.ItemEsp;
import com.jellypudding.offlineclient.modules.render.LogoutSpots;
import com.jellypudding.offlineclient.modules.render.Nametags;
import com.jellypudding.offlineclient.modules.render.NoHurtCam;
import com.jellypudding.offlineclient.modules.render.Portals;
import com.jellypudding.offlineclient.modules.render.Search;
import com.jellypudding.offlineclient.modules.render.Tracers;
import com.jellypudding.offlineclient.modules.render.Trajectories;
import com.jellypudding.offlineclient.modules.render.XRay;
import com.jellypudding.offlineclient.modules.render.Zoom;
import com.jellypudding.offlineclient.modules.world.AirPlace;
import com.jellypudding.offlineclient.modules.world.Nuker;
import com.jellypudding.offlineclient.modules.world.Scaffold;
import com.jellypudding.offlineclient.modules.world.VeinMiner;
import com.jellypudding.offlineclient.util.ChatUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModuleManager {

    private final Map<String, Module> modules = new LinkedHashMap<>();

    public ModuleManager() {
        // Combat
        add(new KillAura());
        add(new TriggerBot());
        add(new Criticals());
        add(new AutoTotem());
        add(new AutoArmor());
        add(new AutoClicker());
        add(new AutoWeapon());
        add(new BowAimbot());
        add(new Surround());
        add(new CrystalAura());
        add(new AutoCity());
        add(new SelfTrap());
        add(new AutoTrap());
        add(new Offhand());
        add(new Hitboxes());

        // Movement
        add(new Sprint());
        add(new Speed());
        add(new Flight());
        add(new NoFall());
        add(new NoKnockback());
        add(new Step());
        add(new HighJump());
        add(new NoSlowdown());
        add(new SafeWalk());
        add(new Spider());
        add(new QuickClimb());
        add(new AutoWalk());
        add(new Blink());
        add(new Parkour());
        add(new ElytraFly());
        add(new FastFall());
        add(new Jesus());
        add(new AutoJump());
        add(new AntiPush());
        add(new LongJump());
        add(new NoWeb());
        add(new Sneak());

        // Render
        add(new Fullbright());
        add(new AntiBlind());
        add(new ClearView());
        add(new ClearSkies());
        add(new Esp());
        add(new ChestEsp());
        add(new Search());
        add(new HoleEsp());
        add(new ItemEsp());
        add(new LogoutSpots());
        add(new Portals());
        add(new XRay());
        add(new Nametags());
        add(new Trajectories());
        add(new Tracers());
        add(new Breadcrumbs());
        add(new Freecam());
        add(new Zoom());
        add(new NoHurtCam());

        // Player
        add(new FastPlace());
        add(new FastBreak());
        add(new AutoRespawn());
        add(new AutoEat());
        add(new ChestStealer());
        add(new AntiHunger());
        add(new AutoTool());
        add(new Reach());
        add(new AutoFish());
        add(new AutoDrop());
        add(new NoRotate());
        add(new FastUse());
        add(new AutoReplenish());
        add(new InvWalk());
        add(new GhostHand());
        add(new MiddleClickExtra());

        // World
        add(new Scaffold());
        add(new Nuker());
        add(new AirPlace());
        add(new VeinMiner());

        // Misc
        add(new ClickGuiModule());
        add(new HudModule());
        add(new AntiAfk());
        add(new AutoReconnect());
        add(new AutoLog());
        add(new Timer());
        add(new FakePlayer());
        add(new Notifier());
        add(new NameProtect());
        add(new Spam());
        add(new AntiSpam());

        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    private void add(Module module) {
        modules.put(module.getName().toLowerCase(), module);
    }

    public Module get(String name) {
        return modules.get(name.toLowerCase().replace(" ", ""));
    }

    public <T extends Module> T get(Class<T> clazz) {
        for (Module module : modules.values()) {
            if (clazz.isInstance(module)) {
                return clazz.cast(module);
            }
        }
        throw new IllegalStateException("Module not registered: " + clazz.getSimpleName());
    }

    public List<Module> getAll() {
        return new ArrayList<>(modules.values());
    }

    public List<Module> getByCategory(Category category) {
        List<Module> result = new ArrayList<>();
        for (Module module : modules.values()) {
            if (module.getCategory() == category) {
                result.add(module);
            }
        }
        return result;
    }

    public List<Module> getEnabled() {
        List<Module> result = new ArrayList<>();
        for (Module module : modules.values()) {
            if (module.isEnabled()) {
                result.add(module);
            }
        }
        return result;
    }

    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        // Ignore binds while any screen is open.
        if (OfflineClient.MC.gui.screen() != null) {
            return;
        }
        for (Module module : modules.values()) {
            if (module.getKeybind().getValue() == event.getKey()) {
                boolean was = module.isEnabled();
                module.onKeybind();
                if (module.isEnabled() != was) {
                    ChatUtil.message("§b" + module.getName() + " §7is now "
                        + (module.isEnabled() ? "§aenabled" : "§cdisabled") + "§7.");
                }
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
            }
        }
    }
}
