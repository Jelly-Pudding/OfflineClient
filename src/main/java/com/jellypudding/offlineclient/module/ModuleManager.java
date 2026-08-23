package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.modules.combat.AnchorAura;
import com.jellypudding.offlineclient.modules.combat.AntiAnchor;
import com.jellypudding.offlineclient.modules.combat.ArrowDodge;
import com.jellypudding.offlineclient.modules.combat.AutoAnvil;
import com.jellypudding.offlineclient.modules.combat.Quiver;
import com.jellypudding.offlineclient.modules.combat.AntiBed;
import com.jellypudding.offlineclient.modules.combat.AttributeSwap;
import com.jellypudding.offlineclient.modules.combat.AutoArmor;
import com.jellypudding.offlineclient.modules.combat.AutoCity;
import com.jellypudding.offlineclient.modules.combat.AutoClicker;
import com.jellypudding.offlineclient.modules.combat.AutoTotem;
import com.jellypudding.offlineclient.modules.combat.AutoTrap;
import com.jellypudding.offlineclient.modules.combat.AutoWeapon;
import com.jellypudding.offlineclient.modules.combat.AutoWeb;
import com.jellypudding.offlineclient.modules.combat.BedAura;
import com.jellypudding.offlineclient.modules.combat.BowAimbot;
import com.jellypudding.offlineclient.modules.combat.BowSpam;
import com.jellypudding.offlineclient.modules.combat.Burrow;
import com.jellypudding.offlineclient.modules.combat.Criticals;
import com.jellypudding.offlineclient.modules.combat.CrystalAura;
import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import com.jellypudding.offlineclient.modules.combat.HoleFiller;
import com.jellypudding.offlineclient.modules.combat.KillAura;
import com.jellypudding.offlineclient.modules.combat.Offhand;
import com.jellypudding.offlineclient.modules.combat.SelfTrap;
import com.jellypudding.offlineclient.modules.combat.SelfWeb;
import com.jellypudding.offlineclient.modules.combat.Surround;
import com.jellypudding.offlineclient.modules.combat.TriggerBot;
import com.jellypudding.offlineclient.modules.misc.AntiAfk;
import com.jellypudding.offlineclient.modules.misc.AntiPacketKick;
import com.jellypudding.offlineclient.modules.misc.AntiSpam;
import com.jellypudding.offlineclient.modules.misc.PacketCanceller;
import com.jellypudding.offlineclient.modules.misc.BetterTab;
import com.jellypudding.offlineclient.modules.misc.AutoLog;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.modules.misc.Derp;
import com.jellypudding.offlineclient.modules.misc.FakePlayer;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.modules.misc.Notifier;
import com.jellypudding.offlineclient.modules.misc.Panic;
import com.jellypudding.offlineclient.modules.misc.ServerSpoof;
import com.jellypudding.offlineclient.modules.misc.SoundBlocker;
import com.jellypudding.offlineclient.modules.misc.StashFinder;
import com.jellypudding.offlineclient.modules.misc.Spam;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.modules.movement.AntiVoid;
import com.jellypudding.offlineclient.modules.movement.AutoJump;
import com.jellypudding.offlineclient.modules.movement.VehicleFly;
import com.jellypudding.offlineclient.modules.movement.ElytraBoost;
import com.jellypudding.offlineclient.modules.movement.AntiPush;
import com.jellypudding.offlineclient.modules.movement.AutoWalk;
import com.jellypudding.offlineclient.modules.movement.Blink;
import com.jellypudding.offlineclient.modules.movement.FastFall;
import com.jellypudding.offlineclient.modules.movement.Flight;
import com.jellypudding.offlineclient.modules.movement.Glide;
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
import com.jellypudding.offlineclient.modules.movement.EdgeGuard;
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
import com.jellypudding.offlineclient.modules.player.AutoGap;
import com.jellypudding.offlineclient.modules.player.AutoMend;
import com.jellypudding.offlineclient.modules.player.AutoPotion;
import com.jellypudding.offlineclient.modules.player.AutoTool;
import com.jellypudding.offlineclient.modules.player.InventoryTweaks;
import com.jellypudding.offlineclient.modules.player.LiquidInteract;
import com.jellypudding.offlineclient.modules.player.NoInteract;
import com.jellypudding.offlineclient.modules.player.NoMiningTrace;
import com.jellypudding.offlineclient.modules.player.ChestSwap;
import com.jellypudding.offlineclient.modules.player.GUIMove;
import com.jellypudding.offlineclient.modules.player.Multitask;
import com.jellypudding.offlineclient.modules.player.NoStatusEffects;
import com.jellypudding.offlineclient.modules.player.PotionSaver;
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
import com.jellypudding.offlineclient.modules.render.CameraTweaks;
import com.jellypudding.offlineclient.modules.render.ChestEsp;
import com.jellypudding.offlineclient.modules.render.ClearSkies;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.modules.render.Esp;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.modules.render.Fullbright;
import com.jellypudding.offlineclient.modules.render.HoleEsp;
import com.jellypudding.offlineclient.modules.render.ItemEsp;
import com.jellypudding.offlineclient.modules.render.LogoutSpots;
import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.modules.render.BreakIndicators;
import com.jellypudding.offlineclient.modules.render.Chams;
import com.jellypudding.offlineclient.modules.render.CityEsp;
import com.jellypudding.offlineclient.modules.render.FreeLook;
import com.jellypudding.offlineclient.modules.render.Nametags;
import com.jellypudding.offlineclient.modules.render.NewChunks;
import com.jellypudding.offlineclient.modules.render.EntityOwner;
import com.jellypudding.offlineclient.modules.render.PopChams;
import com.jellypudding.offlineclient.modules.render.Radar;
import com.jellypudding.offlineclient.modules.render.SpawnEsp;
import com.jellypudding.offlineclient.modules.render.TrueSight;
import com.jellypudding.offlineclient.modules.render.TunnelEsp;
import com.jellypudding.offlineclient.modules.render.VoidEsp;
import com.jellypudding.offlineclient.modules.render.Waypoints;
import com.jellypudding.offlineclient.modules.render.NoHurtCam;
import com.jellypudding.offlineclient.modules.render.Portals;
import com.jellypudding.offlineclient.modules.render.Search;
import com.jellypudding.offlineclient.modules.render.Tracers;
import com.jellypudding.offlineclient.modules.render.Trajectories;
import com.jellypudding.offlineclient.modules.render.XRay;
import com.jellypudding.offlineclient.modules.render.Zoom;
import com.jellypudding.offlineclient.modules.world.AirPlace;
import com.jellypudding.offlineclient.modules.world.AutoFarm;
import com.jellypudding.offlineclient.modules.world.AutoSign;
import com.jellypudding.offlineclient.modules.world.BuildHeight;
import com.jellypudding.offlineclient.modules.world.Excavator;
import com.jellypudding.offlineclient.modules.world.LiquidFiller;
import com.jellypudding.offlineclient.modules.world.SpawnProofer;
import com.jellypudding.offlineclient.modules.world.HighwayBuilder;
import com.jellypudding.offlineclient.modules.world.NoGhostBlocks;
import com.jellypudding.offlineclient.modules.world.Nuker;
import com.jellypudding.offlineclient.modules.world.PacketMine;
import com.jellypudding.offlineclient.modules.world.Tunneller;
import com.jellypudding.offlineclient.modules.world.Scaffold;
import com.jellypudding.offlineclient.modules.world.VeinMiner;
import com.jellypudding.offlineclient.util.ChatUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ModuleManager {

    // Keyed by lower case name. Insertion order is the order modules were added.
    private final Map<String, Module> modules = new LinkedHashMap<>();

    // Lookup by type without walking the name map.
    private final Map<Class<? extends Module>, Module> byType = new HashMap<>();

    public ModuleManager() {
        registerCombat();
        registerMovement();
        registerRender();
        registerPlayer();
        registerWorld();
        registerMisc();

        OfflineClient.INSTANCE.getEventBus().register(this);

        // The config load runs after this and overrides anything it has saved.
        for (Module module : modules.values()) {
            if (module.enabledByDefault()) {
                module.setEnabled(true);
            }
        }
    }

    private void registerCombat() {
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
        add(new AnchorAura());
        add(new BedAura());
        add(new Burrow());
        add(new HoleFiller());
        add(new AutoWeb());
        add(new SelfWeb());
        add(new AntiBed());
        add(new AntiAnchor());
        add(new AttributeSwap());
        add(new BowSpam());
        add(new ArrowDodge());
        add(new Quiver());
        add(new AutoAnvil());
    }

    private void registerMovement() {
        add(new Sprint());
        add(new Speed());
        add(new Flight());
        add(new NoFall());
        add(new NoKnockback());
        add(new Step());
        add(new HighJump());
        add(new NoSlowdown());
        add(new EdgeGuard());
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
        add(new AntiVoid());
        add(new ElytraBoost());
        add(new LongJump());
        add(new NoWeb());
        add(new Sneak());
        add(new VehicleFly());
        add(new Glide());
    }

    private void registerRender() {
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
        add(new NewChunks());
        add(new Chams());
        add(new PopChams());
        add(new BreakIndicators());
        add(new CityEsp());
        add(new BetterTooltips());
        add(new FreeLook());
        add(new TrueSight());
        add(new EntityOwner());
        add(new Radar());
        add(new SpawnEsp());
        add(new TunnelEsp());
        add(new VoidEsp());
        add(new Waypoints());
        add(new Trajectories());
        add(new Tracers());
        add(new Breadcrumbs());
        add(new Freecam());
        add(new Zoom());
        add(new NoHurtCam());
        add(new CameraTweaks());
    }

    private void registerPlayer() {
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
        add(new AutoPotion());
        add(new AutoGap());
        add(new AutoMend());
        add(new InventoryTweaks());
        add(new LiquidInteract());
        add(new NoInteract());
        add(new NoMiningTrace());
        add(new ChestSwap());
        add(new GUIMove());
        add(new Multitask());
        add(new PotionSaver());
        add(new InvWalk());
        add(new GhostHand());
        add(new MiddleClickExtra());
        add(new NoStatusEffects());
    }

    private void registerWorld() {
        add(new Scaffold());
        add(new Nuker());
        add(new AirPlace());
        add(new VeinMiner());
        add(new NoGhostBlocks());
        add(new PacketMine());
        add(new Excavator());
        add(new Tunneller());
        add(new HighwayBuilder());
        add(new AutoSign());
        add(new LiquidFiller());
        add(new SpawnProofer());
        add(new AutoFarm());
        add(new BuildHeight());
    }

    private void registerMisc() {
        add(new ClickGuiModule());
        add(new HudModule());
        add(new AntiAfk());
        add(new AutoReconnect());
        add(new AutoLog());
        add(new Timer());
        add(new FakePlayer());
        add(new Notifier());
        add(new Panic());
        add(new ServerSpoof());
        add(new StashFinder());
        add(new BetterTab());
        add(new Derp());
        add(new AntiPacketKick());
        add(new PacketCanceller());
        add(new NameProtect());
        add(new Spam());
        add(new AntiSpam());
        add(new SoundBlocker());
    }

    private void add(Module module) {
        modules.put(key(module.getName()), module);
        byType.put(module.getClass(), module);
    }

    public Module get(String name) {
        return modules.get(key(name));
    }

    // Spaces and case are ignored.
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replace(" ", "");
    }

    /**
     * The registered module of the given type. A concrete class hits the index
     * directly. A shared base class such as RespawnBlockBreaker falls back to a
     * walk and caches the first match against the asked for type.
     */
    public <T extends Module> T get(Class<T> clazz) {
        Module module = byType.get(clazz);
        if (module == null) {
            module = findByType(clazz);
        }
        if (module == null) {
            throw new IllegalStateException("Module not registered: " + clazz.getSimpleName());
        }
        return clazz.cast(module);
    }

    private Module findByType(Class<? extends Module> clazz) {
        for (Module module : modules.values()) {
            if (clazz.isInstance(module)) {
                byType.put(clazz, module);
                return module;
            }
        }
        return null;
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
        if (OfflineClient.MC.gui.screen() != null) {
            return;
        }
        for (Module module : modules.values()) {
            if (module.getKeybind().getValue() == event.getKey()) {
                boolean was = module.isEnabled();
                module.onKeybind();
                if (module.isEnabled() != was) {
                    ChatUtil.toggled(module);
                }
            }
        }
    }
}
