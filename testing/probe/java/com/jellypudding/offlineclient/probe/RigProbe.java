package com.jellypudding.offlineclient.probe;

import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Tells the test rig what the server saw. Every answer is one line in the server's log that
// starts with the word PROBE. It also plays the tricks a server plays to find out whether a game
// runs mods. Nothing else in the game changes.
public final class RigProbe extends JavaPlugin implements Listener {

    private static final String WORD = "PROBE ";
    // A word of a sign or an item name that begins like this is a key bind and any other a translation key.
    private static final String BIND = "bind:";
    private static final String LINE_SPLIT = "|";
    private static final int SIGN_LINES = 4;
    private static final int SIGN_AWAY = 2;
    // Ticks a game needs to see a block or a screen before the next thing about it is sent.
    private static final int CLIENT_TICKS = 5;

    // What each player did since the last reset.
    private final Map<String, Map<String, Double>> tallies = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::watchAir, 1, 1);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0]) {
            case "mark" -> say("mark " + word(args, 1));
            case "reset" -> {
                tallies.clear();
                say("reset done");
            }
            case "player" -> say("player " + word(args, 1) + " " + facts(Bukkit.getPlayerExact(word(args, 1))));
            case "tally" -> say("tally " + word(args, 1) + " " + pairs(tallies.getOrDefault(word(args, 1), Map.of())));
            case "block" -> say("block " + Bukkit.getWorlds().getFirst().getBlockAt(Integer.parseInt(args[1]),
                Integer.parseInt(args[2]), Integer.parseInt(args[3])).getType().getKey());
            case "plugin" -> say("plugin " + word(args, 1) + " "
                + Bukkit.getPluginManager().isPluginEnabled(word(args, 1)));
            case "sign" -> showSign(args);
            case "anvil" -> showAnvil(args);
            case "pack" -> Bukkit.getPlayerExact(args[1]).setResourcePack(UUID.randomUUID(), args[2], (byte[]) null,
                (Component) null, false);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static String word(String[] args, int index) {
        return index < args.length ? args[index] : "";
    }

    private void say(String line) {
        getLogger().info(WORD + line);
    }

    // Stands a sign written in keys before the player and opens it for editing. The game sends
    // the lines back as words and a key that came back as words is one the game knew.
    private void showSign(String[] args) {
        Player player = Bukkit.getPlayerExact(args[1]);
        Block block = player.getLocation().getBlock().getRelative(0, 0, -SIGN_AWAY);
        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        for (int line = 0; line < SIGN_LINES && line + 2 < args.length; line++) {
            sign.getSide(Side.FRONT).line(line, keyed(args[line + 2]));
        }
        sign.update(true, false);
        Bukkit.getScheduler().runTaskLater(this, () -> player.openSign((Sign) block.getState(), Side.FRONT),
            CLIENT_TICKS);
    }

    // Opens an anvil with an item named by a key in it. The game writes the name into the box
    // as words and sends them back.
    private void showAnvil(String[] args) {
        Player player = Bukkit.getPlayerExact(args[1]);
        InventoryView anvil = player.openAnvil(null, true);
        ItemStack named = new ItemStack(Material.PAPER);
        named.editMeta(meta -> meta.customName(keyed(args[2])));
        Bukkit.getScheduler().runTaskLater(this, () -> anvil.getTopInventory().setItem(0, named), CLIENT_TICKS);
    }

    private static Component keyed(String word) {
        return word.startsWith(BIND) ? Component.keybind(word.substring(BIND.length())) : Component.translatable(word);
    }

    private static String plain(Component text) {
        return PlainTextComponentSerializer.plainText().serialize(text);
    }

    // The player as the server holds it this tick.
    private static String facts(Player player) {
        if (player == null) {
            return "online=false";
        }
        Location at = player.getLocation();
        Vector speed = player.getVelocity();
        Map<String, Object> facts = new TreeMap<>();
        facts.put("online", true);
        facts.put("brand", player.getClientBrandName());
        facts.put("host", player.getVirtualHost() == null ? "" : player.getVirtualHost().getHostString());
        facts.put("channels", String.join(",", player.getListeningPluginChannels()));
        facts.put("locale", player.locale());
        facts.put("sight", player.getClientViewDistance());
        facts.put("mode", player.getGameMode());
        facts.put("x", number(at.getX()));
        facts.put("y", number(at.getY()));
        facts.put("z", number(at.getZ()));
        facts.put("yaw", number(at.getYaw()));
        facts.put("pitch", number(at.getPitch()));
        facts.put("vx", number(speed.getX()));
        facts.put("vy", number(speed.getY()));
        facts.put("vz", number(speed.getZ()));
        facts.put("ground", player.isOnGround());
        facts.put("sneaking", player.isSneaking());
        facts.put("sprinting", player.isSprinting());
        facts.put("flying", player.isFlying());
        facts.put("mayfly", player.getAllowFlight());
        facts.put("gliding", player.isGliding());
        facts.put("swimming", player.isSwimming());
        facts.put("pose", player.getPose());
        facts.put("fall", number(player.getFallDistance()));
        facts.put("health", number(player.getHealth()));
        facts.put("food", player.getFoodLevel());
        facts.put("slot", player.getInventory().getHeldItemSlot());
        facts.put("hand", player.getInventory().getItemInMainHand().getType().getKey());
        facts.put("ping", player.getPing());
        return pairs(facts);
    }

    private static String pairs(Map<String, ?> values) {
        StringJoiner joined = new StringJoiner(" ");
        for (Map.Entry<String, ?> each : new TreeMap<>(values).entrySet()) {
            Object value = each.getValue() instanceof Double number ? number(number) : each.getValue();
            joined.add(each.getKey() + "=" + String.valueOf(value).replace(' ', '_'));
        }
        return joined.toString();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private void add(Player player, String what, double amount) {
        tallies.computeIfAbsent(player.getName(), name -> new ConcurrentHashMap<>()).merge(what, amount, Double::sum);
    }

    private void most(Player player, String what, double amount) {
        tallies.computeIfAbsent(player.getName(), name -> new ConcurrentHashMap<>()).merge(what, amount, Math::max);
    }

    // Counts the ticks each player spends off the ground and how high they get.
    private void watchAir() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnGround()) {
                add(player, "airTicks", 1);
            }
            most(player, "highest", player.getLocation().getY());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        add(event.getPlayer(), "swings", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            add(event.getPlayer(), "sneaks", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFlight(PlayerToggleFlightEvent event) {
        add(event.getPlayer(), "flightToggles", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player && event.isGliding()) {
            add(player, "glides", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player hurt && !event.isCancelled()) {
            add(hurt, "hurts", 1);
            add(hurt, "hurtSum", event.getFinalDamage());
            add(hurt, "hurt." + event.getCause(), 1);
        }
        if (event instanceof EntityDamageByEntityEvent blow && blow.getDamager() instanceof Player hitter) {
            add(hitter, event.isCancelled() ? "hitsRefused" : "hits", 1);
            add(hitter, "hitDamage", event.getDamage());
            say("hit " + hitter.getName() + " critical=" + blow.isCritical() + " fall="
                + number(hitter.getFallDistance()) + " ground=" + hitter.isOnGround() + " sprinting="
                + hitter.isSprinting() + " charge=" + number(hitter.getAttackCooldown()));
            if (blow.isCritical()) {
                add(hitter, "crits", 1);
            }
            most(hitter, "farthestHit", hitter.getEyeLocation().distance(event.getEntity().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        add(event.getEntity(), "deaths", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        add(event.getPlayer(), "kicks", 1);
        say("kick " + event.getPlayer().getName() + " cause=" + event.getCause());
    }

    // An anti cheat that sets a player back shows as a teleport no command asked for.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        add(event.getPlayer(), "teleports." + event.getCause(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        double across = Math.hypot(event.getTo().getX() - event.getFrom().getX(),
            event.getTo().getZ() - event.getFrom().getZ());
        add(event.getPlayer(), event.isCancelled() ? "movesRefused" : "moved", event.isCancelled() ? 1 : across);
        most(event.getPlayer(), "longestMove", across);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        add(event.getPlayer(), event.isCancelled() ? "placesRefused" : "placed", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        add(event.getPlayer(), event.isCancelled() ? "breaksRefused" : "broken", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        add(event.getPlayer(), "interacts", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        add(event.getPlayer(), "chats", 1);
        if (event.signedMessage().signature() != null) {
            add(event.getPlayer(), "chatsSigned", 1);
        }
        say("chat " + event.getPlayer().getName() + " " + plain(event.message()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTyped(PlayerCommandPreprocessEvent event) {
        add(event.getPlayer(), "commands", 1);
        say("command " + event.getPlayer().getName() + " " + event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSign(SignChangeEvent event) {
        StringJoiner lines = new StringJoiner(LINE_SPLIT);
        event.lines().forEach(line -> lines.add(plain(line)));
        say("sign " + event.getPlayer().getName() + " " + lines);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnvil(PrepareAnvilEvent event) {
        String typed = event.getView().getRenameText();
        if (typed != null && !typed.isEmpty()) {
            say("anvil " + event.getView().getPlayer().getName() + " " + typed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPack(PlayerResourcePackStatusEvent event) {
        say("pack " + event.getPlayer().getName() + " " + event.getStatus());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOptions(PlayerClientOptionsChangeEvent event) {
        if (event.hasSkinPartsChanged()) {
            add(event.getPlayer(), "skinChanges", 1);
        }
    }
}
