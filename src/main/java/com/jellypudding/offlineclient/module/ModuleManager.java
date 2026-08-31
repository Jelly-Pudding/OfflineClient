package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.modules.combat.AnchorAura;
import com.jellypudding.offlineclient.modules.combat.AntiAnchor;
import com.jellypudding.offlineclient.modules.combat.AntiAnvil;
import com.jellypudding.offlineclient.modules.combat.AntiBed;
import com.jellypudding.offlineclient.modules.combat.ArrowDodge;
import com.jellypudding.offlineclient.modules.combat.AttributeSwap;
import com.jellypudding.offlineclient.modules.combat.AutoAnvil;
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
import com.jellypudding.offlineclient.modules.combat.Quiver;
import com.jellypudding.offlineclient.modules.combat.SelfAnvil;
import com.jellypudding.offlineclient.modules.combat.SelfTrap;
import com.jellypudding.offlineclient.modules.combat.SelfWeb;
import com.jellypudding.offlineclient.modules.combat.Surround;
import com.jellypudding.offlineclient.modules.combat.TriggerBot;
import com.jellypudding.offlineclient.modules.misc.AntiAfk;
import com.jellypudding.offlineclient.modules.misc.AntiPacketKick;
import com.jellypudding.offlineclient.modules.misc.AntiSpam;
import com.jellypudding.offlineclient.modules.misc.AutoLog;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.modules.misc.BetterChat;
import com.jellypudding.offlineclient.modules.misc.BetterTab;
import com.jellypudding.offlineclient.modules.misc.BookBot;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.modules.misc.Derp;
import com.jellypudding.offlineclient.modules.misc.FakePlayer;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.modules.misc.Notebot;
import com.jellypudding.offlineclient.modules.misc.Notifier;
import com.jellypudding.offlineclient.modules.misc.PacketCanceller;
import com.jellypudding.offlineclient.modules.misc.Panic;
import com.jellypudding.offlineclient.modules.misc.ServerSpoof;
import com.jellypudding.offlineclient.modules.misc.SoundBlocker;
import com.jellypudding.offlineclient.modules.misc.Spam;
import com.jellypudding.offlineclient.modules.misc.StashFinder;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.modules.movement.AirJump;
import com.jellypudding.offlineclient.modules.movement.AntiPush;
import com.jellypudding.offlineclient.modules.movement.AntiVoid;
import com.jellypudding.offlineclient.modules.movement.AutoJump;
import com.jellypudding.offlineclient.modules.movement.AutoWalk;
import com.jellypudding.offlineclient.modules.movement.Blink;
import com.jellypudding.offlineclient.modules.movement.ClickTp;
import com.jellypudding.offlineclient.modules.movement.EdgeGuard;
import com.jellypudding.offlineclient.modules.movement.ElytraBoost;
import com.jellypudding.offlineclient.modules.movement.ElytraFly;
import com.jellypudding.offlineclient.modules.movement.FastFall;
import com.jellypudding.offlineclient.modules.movement.Flight;
import com.jellypudding.offlineclient.modules.movement.Glide;
import com.jellypudding.offlineclient.modules.movement.HighJump;
import com.jellypudding.offlineclient.modules.movement.HoleSnap;
import com.jellypudding.offlineclient.modules.movement.Jesus;
import com.jellypudding.offlineclient.modules.movement.LongJump;
import com.jellypudding.offlineclient.modules.movement.NoClip;
import com.jellypudding.offlineclient.modules.movement.NoFall;
import com.jellypudding.offlineclient.modules.movement.NoKnockback;
import com.jellypudding.offlineclient.modules.movement.NoSlowdown;
import com.jellypudding.offlineclient.modules.movement.NoWeb;
import com.jellypudding.offlineclient.modules.movement.Parkour;
import com.jellypudding.offlineclient.modules.movement.QuickClimb;
import com.jellypudding.offlineclient.modules.movement.Sneak;
import com.jellypudding.offlineclient.modules.movement.Speed;
import com.jellypudding.offlineclient.modules.movement.Spider;
import com.jellypudding.offlineclient.modules.movement.Sprint;
import com.jellypudding.offlineclient.modules.movement.Step;
import com.jellypudding.offlineclient.modules.movement.TridentBoost;
import com.jellypudding.offlineclient.modules.movement.VehicleFly;
import com.jellypudding.offlineclient.modules.player.AntiCactus;
import com.jellypudding.offlineclient.modules.player.AntiHunger;
import com.jellypudding.offlineclient.modules.player.AutoDrop;
import com.jellypudding.offlineclient.modules.player.AutoEat;
import com.jellypudding.offlineclient.modules.player.AutoFish;
import com.jellypudding.offlineclient.modules.player.AutoGap;
import com.jellypudding.offlineclient.modules.player.AutoMend;
import com.jellypudding.offlineclient.modules.player.AutoPotion;
import com.jellypudding.offlineclient.modules.player.AutoReplenish;
import com.jellypudding.offlineclient.modules.player.AutoRespawn;
import com.jellypudding.offlineclient.modules.player.AutoTool;
import com.jellypudding.offlineclient.modules.player.ChestStealer;
import com.jellypudding.offlineclient.modules.player.ChestSwap;
import com.jellypudding.offlineclient.modules.player.FastBreak;
import com.jellypudding.offlineclient.modules.player.FastPlace;
import com.jellypudding.offlineclient.modules.player.FastUse;
import com.jellypudding.offlineclient.modules.player.GUIMove;
import com.jellypudding.offlineclient.modules.player.GhostHand;
import com.jellypudding.offlineclient.modules.player.InvWalk;
import com.jellypudding.offlineclient.modules.player.InventoryTweaks;
import com.jellypudding.offlineclient.modules.player.LiquidInteract;
import com.jellypudding.offlineclient.modules.player.MiddleClickExtra;
import com.jellypudding.offlineclient.modules.player.Multitask;
import com.jellypudding.offlineclient.modules.player.NoInteract;
import com.jellypudding.offlineclient.modules.player.NoMiningTrace;
import com.jellypudding.offlineclient.modules.player.NoRotate;
import com.jellypudding.offlineclient.modules.player.NoStatusEffects;
import com.jellypudding.offlineclient.modules.player.PotionSaver;
import com.jellypudding.offlineclient.modules.player.Reach;
import com.jellypudding.offlineclient.modules.player.Rotation;
import com.jellypudding.offlineclient.modules.render.AntiBlind;
import com.jellypudding.offlineclient.modules.render.BetterTooltips;
import com.jellypudding.offlineclient.modules.render.Breadcrumbs;
import com.jellypudding.offlineclient.modules.render.BreakIndicators;
import com.jellypudding.offlineclient.modules.render.CameraTweaks;
import com.jellypudding.offlineclient.modules.render.Chams;
import com.jellypudding.offlineclient.modules.render.ChestEsp;
import com.jellypudding.offlineclient.modules.render.CityEsp;
import com.jellypudding.offlineclient.modules.render.ClearView;
import com.jellypudding.offlineclient.modules.render.EntityOwner;
import com.jellypudding.offlineclient.modules.render.Esp;
import com.jellypudding.offlineclient.modules.render.FreeLook;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.modules.render.Fullbright;
import com.jellypudding.offlineclient.modules.render.HandView;
import com.jellypudding.offlineclient.modules.render.HoleEsp;
import com.jellypudding.offlineclient.modules.render.ItemEsp;
import com.jellypudding.offlineclient.modules.render.ItemHighlight;
import com.jellypudding.offlineclient.modules.render.LogoutSpots;
import com.jellypudding.offlineclient.modules.render.Nametags;
import com.jellypudding.offlineclient.modules.render.NewChunks;
import com.jellypudding.offlineclient.modules.render.NoBackground;
import com.jellypudding.offlineclient.modules.render.NoHurtCam;
import com.jellypudding.offlineclient.modules.render.NoRender;
import com.jellypudding.offlineclient.modules.render.NoShieldOverlay;
import com.jellypudding.offlineclient.modules.render.OpenWaterEsp;
import com.jellypudding.offlineclient.modules.render.PopChams;
import com.jellypudding.offlineclient.modules.render.Portals;
import com.jellypudding.offlineclient.modules.render.Radar;
import com.jellypudding.offlineclient.modules.render.Search;
import com.jellypudding.offlineclient.modules.render.SpawnEsp;
import com.jellypudding.offlineclient.modules.render.TimeChanger;
import com.jellypudding.offlineclient.modules.render.Tracers;
import com.jellypudding.offlineclient.modules.render.Trajectories;
import com.jellypudding.offlineclient.modules.render.TrueSight;
import com.jellypudding.offlineclient.modules.render.TunnelEsp;
import com.jellypudding.offlineclient.modules.render.VoidEsp;
import com.jellypudding.offlineclient.modules.render.Waypoints;
import com.jellypudding.offlineclient.modules.render.Weather;
import com.jellypudding.offlineclient.modules.render.XRay;
import com.jellypudding.offlineclient.modules.render.Zoom;
import com.jellypudding.offlineclient.modules.world.AirPlace;
import com.jellypudding.offlineclient.modules.world.AutoBreed;
import com.jellypudding.offlineclient.modules.world.AutoBrewer;
import com.jellypudding.offlineclient.modules.world.AutoFarm;
import com.jellypudding.offlineclient.modules.world.AutoLibrarian;
import com.jellypudding.offlineclient.modules.world.AutoNametag;
import com.jellypudding.offlineclient.modules.world.AutoShearer;
import com.jellypudding.offlineclient.modules.world.AutoSign;
import com.jellypudding.offlineclient.modules.world.AutoSmelter;
import com.jellypudding.offlineclient.modules.world.BonemealAura;
import com.jellypudding.offlineclient.modules.world.BuildHeight;
import com.jellypudding.offlineclient.modules.world.EChestFarmer;
import com.jellypudding.offlineclient.modules.world.Excavator;
import com.jellypudding.offlineclient.modules.world.HighwayBuilder;
import com.jellypudding.offlineclient.modules.world.LiquidFiller;
import com.jellypudding.offlineclient.modules.world.NoGhostBlocks;
import com.jellypudding.offlineclient.modules.world.Nuker;
import com.jellypudding.offlineclient.modules.world.PacketMine;
import com.jellypudding.offlineclient.modules.world.Scaffold;
import com.jellypudding.offlineclient.modules.world.SpawnProofer;
import com.jellypudding.offlineclient.modules.world.Tillaura;
import com.jellypudding.offlineclient.modules.world.Tunneller;
import com.jellypudding.offlineclient.modules.world.VeinMiner;
import com.jellypudding.offlineclient.util.ChatUtil;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModuleManager {

    // Keyed by lower case name. Insertion order is the order modules were added.
    private final Map<String, Module> modules = new LinkedHashMap<>();

    // Lookup by type without walking the name map.
    private final Map<Class<? extends Module>, Module> byType = new ConcurrentHashMap<>();

    public ModuleManager() {
        registerCombat();
        registerMovement();
        registerRender();
        registerPlayer();
        registerWorld();
        registerMisc();

        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    // Runs once the client holds this manager. The config load then overrides it.
    public void enableDefaults() {
        for (Module module : modules.values()) {
            if (module.enabledByDefault()) {
                module.setEnabled(true);
            }
        }
    }

    // Each category lists its modules in the order the panels show them.
    // The everyday ones sit at the top and related ones sit together.
    private void registerCombat() {
        add(new KillAura());
        add(new CrystalAura());
        add(new AutoTotem());
        add(new Surround());
        add(new AutoArmor());
        add(new Offhand());

        add(new TriggerBot());
        add(new Criticals());
        add(new AutoClicker());
        add(new AutoWeapon());
        add(new AttributeSwap());
        add(new Hitboxes());

        add(new BowAimbot());
        add(new BowSpam());
        add(new Quiver());
        add(new ArrowDodge());

        add(new AnchorAura());
        add(new BedAura());
        add(new AutoCity());
        add(new HoleFiller());
        add(new AutoWeb());
        add(new SelfWeb());

        add(new AutoTrap());
        add(new SelfTrap());
        add(new Burrow());
        add(new AutoAnvil());
        add(new SelfAnvil());

        add(new AntiAnvil());
        add(new AntiBed());
        add(new AntiAnchor());
    }

    private void registerMovement() {
        add(new Sprint());
        add(new Speed());
        add(new Flight());
        add(new ElytraFly());
        add(new ElytraBoost());
        add(new NoFall());
        add(new Step());

        add(new Jesus());
        add(new Spider());
        add(new NoSlowdown());
        add(new NoKnockback());
        add(new AntiPush());
        add(new AntiVoid());
        add(new HoleSnap());
        add(new EdgeGuard());

        add(new AutoWalk());
        add(new AutoJump());
        add(new HighJump());
        add(new LongJump());
        add(new AirJump());
        add(new Parkour());
        add(new QuickClimb());
        add(new FastFall());
        add(new Glide());
        add(new NoWeb());
        add(new Sneak());

        add(new Blink());
        add(new ClickTp());
        add(new VehicleFly());
        add(new TridentBoost());
        add(new NoClip());
    }

    private void registerRender() {
        add(new Esp());
        add(new ChestEsp());
        add(new ItemEsp());
        add(new HoleEsp());
        add(new Search());
        add(new XRay());
        add(new Fullbright());
        add(new Nametags());
        add(new Tracers());

        add(new NewChunks());
        add(new Chams());
        add(new PopChams());
        add(new TrueSight());
        add(new CityEsp());
        add(new SpawnEsp());
        add(new TunnelEsp());
        add(new VoidEsp());
        add(new LogoutSpots());
        add(new Portals());

        add(new Waypoints());
        add(new Breadcrumbs());
        add(new Radar());
        add(new Trajectories());
        add(new BreakIndicators());
        add(new EntityOwner());
        add(new BetterTooltips());
        add(new ItemHighlight());
        add(new OpenWaterEsp());

        add(new Freecam());
        add(new FreeLook());
        add(new Zoom());
        add(new CameraTweaks());
        add(new NoHurtCam());
        add(new AntiBlind());
        add(new ClearView());
        add(new Weather());
        add(new TimeChanger());

        add(new NoRender());
        add(new HandView());
        add(new NoShieldOverlay());
        add(new NoBackground());
    }

    private void registerPlayer() {
        add(new AutoEat());
        add(new AutoGap());
        add(new AutoPotion());
        add(new AutoTool());
        add(new AutoReplenish());
        add(new AutoMend());
        add(new AutoRespawn());

        add(new FastPlace());
        add(new FastBreak());
        add(new FastUse());
        add(new Reach());
        add(new Rotation());
        add(new NoRotate());
        add(new AntiHunger());

        add(new ChestStealer());
        add(new ChestSwap());
        add(new InventoryTweaks());
        add(new AutoDrop());
        add(new GUIMove());
        add(new InvWalk());
        add(new Multitask());
        add(new GhostHand());
        add(new MiddleClickExtra());

        add(new LiquidInteract());
        add(new NoInteract());
        add(new NoMiningTrace());
        add(new PotionSaver());
        add(new NoStatusEffects());
        add(new AntiCactus());
        add(new AutoFish());
    }

    private void registerWorld() {
        add(new Scaffold());
        add(new Nuker());
        add(new VeinMiner());
        add(new PacketMine());
        add(new Excavator());
        add(new Tunneller());
        add(new HighwayBuilder());
        add(new AirPlace());
        add(new NoGhostBlocks());

        add(new AutoFarm());
        add(new Tillaura());
        add(new BonemealAura());
        add(new AutoBreed());
        add(new AutoShearer());
        add(new AutoNametag());

        add(new LiquidFiller());
        add(new SpawnProofer());
        add(new BuildHeight());
        add(new AutoSign());

        add(new EChestFarmer());
        add(new AutoBrewer());
        add(new AutoSmelter());
        add(new AutoLibrarian());
    }

    private void registerMisc() {
        add(new ClickGuiModule());
        add(new HudModule());
        add(new Panic());
        add(new AutoLog());
        add(new AutoReconnect());
        add(new AntiAfk());
        add(new Timer());
        add(new FakePlayer());
        add(new Notifier());
        add(new StashFinder());

        add(new BetterTab());
        add(new BetterChat());
        add(new AntiSpam());
        add(new Spam());
        add(new NameProtect());

        add(new ServerSpoof());
        add(new AntiPacketKick());
        add(new PacketCanceller());
        add(new SoundBlocker());
        add(new Derp());
        add(new BookBot());
        add(new Notebot());
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

    // Registration order. Every category lists its everyday modules first.
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
