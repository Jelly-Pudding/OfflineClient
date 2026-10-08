package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.EntityDamageEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.PlayerMoves;
import com.jellypudding.offlineclient.util.SafeDrop;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Watches the players and creepers round you and hops you clear of the hit before it lands.
// The server sends where another player is every second tick and their speed is worked out
// from that. Fall distance is never sent.
public final class Evade extends Module {

    // A threat and how far a spot would be from it.
    private interface Threat {
        double clearance(Vec3 centre);
    }

    // A blow aimed at a point such as a falling mace or a creeper.
    private record Point(Vec3 at) implements Threat {
        @Override
        public double clearance(Vec3 centre) {
            return centre.distanceTo(at);
        }
    }

    // A spear charge runs along the line the attacker looks down.
    private record Line(Vec3 eyes, Vec3 look) implements Threat {
        @Override
        public double clearance(Vec3 centre) {
            Vec3 offset = centre.subtract(eyes);
            double along = Math.max(0, offset.dot(look));
            return offset.subtract(look.scale(along)).length();
        }
    }

    // A hit taken and your health before it. The health lands a tick or two after the hit.
    private record Hit(Entity attacker, Vec3 from, float healthBefore, int tick) {
    }

    // Blocks a tick downwards before a player counts as dropping onto you.
    private static final double MACE_FALL = 0.3;

    // Ticks ahead a blow is looked for. The hop itself goes on the next tick.
    private static final int LEAD_TICKS = 3;

    // Degrees off a spear's line that still meet its point.
    private static final double SPEAR_CONE = 20;

    // A creeper blast hurts out to twice its radius. A charged one blasts twice as far.
    private static final double CREEPER_BLAST = 6;

    // Players and creepers further off are never a threat this tick.
    private static final double SCAN = 32;

    // Ticks between two hops.
    private static final int HOP_GAP = 10;

    // Ticks a hit is kept whilst its health lands and a hop finds a way.
    private static final int HIT_WAIT = 3;

    // The hop tries this many bearings round you and the same height first.
    private static final int BEARINGS = 8;
    private static final double[] HEIGHTS = {0, 1, -1};

    // Ticks the reason for the last hop stays beside the name.
    private static final int SHOW_TICKS = 20;

    private final BoolSetting maces = new BoolSetting("Maces",
        "Hops away from a player with a mace who drops towards you.", true);
    private final BoolSetting spears = new BoolSetting("Spears",
        "Hops out of the path of a player charging a spear at you.", true);
    private final BoolSetting blades = new BoolSetting("Blades",
        "Hops away from a player with a sword or axe who comes into reach.", false);
    private final BoolSetting creepers = new BoolSetting("Creepers",
        "Hops away from a creeper that starts to swell near you.", true);
    private final NumberSetting keepDistance = new NumberSetting("Keep distance",
        "Hops away from anyone holding a mace or spear who comes closer than this. Nought turns it off.",
        0, 0, 12, 0.5, " blocks");
    private final NumberSetting bigHits = new NumberSetting("After big hits",
        "Hops away from whoever hits you for at least this many hearts. Nought turns it off.",
        0, 0, 10, 0.5, " hearts");
    private final NumberSetting hop = new NumberSetting("Hop",
        "How far each hop takes you.", 4, 2, 10, 0.5, " blocks").min(1);
    private final BoolSetting solidGround = new BoolSetting("Solid ground",
        "Only hops onto ground that holds you up.", true);
    private final KeybindSetting pauseKey = new KeybindSetting("Pause key",
        "Hold it to stop the hops.", KeybindSetting.UNBOUND);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Never hops away from a friend.", true);

    private final PlayerMoves moves = new PlayerMoves();
    private final WorldWatch world = new WorldWatch();
    private Hit hit;
    private float lastHealth;
    private int ticks;
    private int rest;
    private String reason;
    private int shown;

    public Evade() {
        super("Evade", "Hops you out of reach of a falling mace or a charging spear or a raised blade.",
            Category.COMBAT);
        addSettings(maces, spears, blades, creepers, keepDistance, bigHits, hop, solidGround, pauseKey,
            ignoreFriends);
        searchTags("dodge", "sidestep", "anti mace", "anti spear", "hop away");
    }

    @Override
    public String getSuffix() {
        return shown > 0 ? reason : null;
    }

    @Override
    protected void onEnable() {
        moves.clear();
        world.accept();
        hit = null;
        rest = 0;
        shown = 0;
        lastHealth = inGame() ? EntityUtil.totalHealth(mc.player) : 0;
    }

    @Override
    protected void onDisable() {
        moves.clear();
        hit = null;
    }

    @Subscribe
    private void onDamage(EntityDamageEvent event) {
        if (event.getEntity() != mc.player || bigHits.getValue() <= 0) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        hit = new Hit(attacker, event.getSource().getSourcePosition(), lastHealth, ticks);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        ticks++;
        rest = Math.max(0, rest - 1);
        shown = Math.max(0, shown - 1);
        if (!inGame()) {
            return;
        }
        if (world.changed()) {
            moves.clear();
        }
        moves.tick();
        List<Threat> threats = new ArrayList<>();
        String found = gather(threats);
        lastHealth = EntityUtil.totalHealth(mc.player);
        if (found == null || rest > 0 || pauseKey.isHeld() || mc.player.isSpectator() || mc.player.isPassenger()
            || mc.player.isFallFlying() || Hop.travelling()) {
            return;
        }
        Vec3 spot = bestSpot(threats);
        if (spot == null) {
            return;
        }
        Hop.Plan plan = Hop.plan().to(spot);
        if (plan.problem() != null) {
            return;
        }
        plan.go(result -> { });
        hit = null;
        rest = HOP_GAP;
        reason = found;
        shown = SHOW_TICKS;
    }

    // Every threat this tick. Hands back a word for the first one or null when there is none.
    private String gather(List<Threat> threats) {
        String found = null;
        for (Player player : mc.level.players()) {
            if (mc.player.distanceTo(player) > SCAN || !counts(player)) {
                continue;
            }
            String kind = threatOf(player, moves.velocity(player), threats);
            if (found == null) {
                found = kind;
            }
        }
        if (creepers.isOn()) {
            AABB near = mc.player.getBoundingBox().inflate(2 * CREEPER_BLAST);
            for (Creeper creeper : mc.level.getEntitiesOfClass(Creeper.class, near)) {
                double blast = creeper.isPowered() ? 2 * CREEPER_BLAST : CREEPER_BLAST;
                if (creeper.getSwellDir() > 0 && creeper.distanceTo(mc.player) <= blast) {
                    threats.add(new Point(creeper.getBoundingBox().getCenter()));
                    found = found == null ? "creeper" : found;
                }
            }
        }
        String struck = bigHit(threats);
        return found == null ? struck : found;
    }

    // Spectators and fake players and your own copies never threaten anyone. Friends only do
    // whilst Ignore friends is off.
    private boolean counts(Player player) {
        return EntityUtil.isRealPlayer(player) && player.isAlive() && !player.isSpectator()
            && !(ignoreFriends.isOn() && EntityUtil.isFriend(player));
    }

    // Adds what this player threatens. A word for it or null when they threaten nothing.
    private String threatOf(Player player, Vec3 velocity, List<Threat> threats) {
        ItemStack held = player.getMainHandItem();
        ItemStack used = player.getUseItem();
        Vec3 base = player.getPositionCodec().getBase();
        Vec3 eyes = base.add(0, player.getEyeHeight(), 0);
        boolean mace = held.getItem() instanceof MaceItem;
        boolean spear = held.is(ItemTags.SPEARS);
        if (maces.isOn() && mace && velocity.y <= -MACE_FALL && soonInReach(player, held, eyes, velocity)) {
            threats.add(new Point(eyes));
            return "mace";
        }
        boolean charging = player.isUsingItem() && used.is(ItemTags.SPEARS);
        if (spears.isOn() && charging && soonInReach(player, used, eyes, velocity)
            && EntityUtil.lookAngle(player, mc.player) <= SPEAR_CONE) {
            threats.add(new Line(eyes, player.getViewVector(1)));
            return "spear";
        }
        boolean blade = held.is(ItemTags.SWORDS) || held.is(ItemTags.AXES);
        if (blades.isOn() && blade && soonInReach(player, held, eyes, velocity)) {
            threats.add(new Point(eyes));
            return "blade";
        }
        double keep = keepDistance.getValue();
        if (keep > 0 && (mace || spear) && mc.player.position().distanceTo(base) < keep) {
            threats.add(new Point(eyes));
            return "too close";
        }
        return null;
    }

    // True when the weapon reaches you now or will within a few ticks along the way the player
    // moves. A spear cannot strike anything that sits too close.
    private boolean soonInReach(Player player, ItemStack held, Vec3 eyes, Vec3 velocity) {
        AttackRange range = player.getAttackRangeWith(held);
        double least = range.effectiveMinRange(player) - range.hitboxMargin();
        double most = range.effectiveMaxRange(player) + range.hitboxMargin();
        AABB body = mc.player.getBoundingBox();
        for (int ahead = 0; ahead <= LEAD_TICKS; ahead++) {
            double distance = Math.sqrt(body.distanceToSqr(eyes.add(velocity.scale(ahead))));
            if (distance >= least && distance <= most) {
                return true;
            }
        }
        return false;
    }

    // A hit that took at least the hearts set. The health lands a moment after the hit itself.
    // The hit stays until a hop goes or it grows too old.
    private String bigHit(List<Threat> threats) {
        if (hit != null && ticks - hit.tick() > HIT_WAIT) {
            hit = null;
        }
        if (hit == null) {
            return null;
        }
        float lost = hit.healthBefore() - EntityUtil.totalHealth(mc.player);
        if (lost < bigHits.getValue() * EntityUtil.HEART) {
            return null;
        }
        // Your own crystal or arrow names you as the attacker.
        if (hit.attacker() instanceof Player player && !counts(player)) {
            hit = null;
            return null;
        }
        Vec3 from = hit.attacker() != null ? hit.attacker().getBoundingBox().getCenter() : hit.from();
        if (from == null) {
            hit = null;
            return null;
        }
        threats.add(new Point(from));
        return "big hit";
    }

    // The clear spot a hop away that stands furthest from every threat. Null when nowhere is
    // safer than where you stand.
    private Vec3 bestSpot(List<Threat> threats) {
        Vec3 from = mc.player.position();
        double half = mc.player.getBbHeight() / 2;
        double best = clearance(threats, from.add(0, half, 0));
        Vec3 chosen = null;
        for (int i = 0; i < BEARINGS; i++) {
            double angle = i * 2 * Math.PI / BEARINGS;
            Vec3 flat = new Vec3(-Math.sin(angle), 0, Math.cos(angle)).scale(hop.getValue());
            for (double rise : HEIGHTS) {
                Vec3 spot = landing(from, from.add(flat.x, rise, flat.z));
                if (spot == null) {
                    continue;
                }
                double value = clearance(threats, spot.add(0, half, 0));
                if (value > best) {
                    best = value;
                    chosen = spot;
                }
                break;
            }
        }
        return chosen;
    }

    private static double clearance(List<Threat> threats, Vec3 centre) {
        double least = Double.MAX_VALUE;
        for (Threat threat : threats) {
            least = Math.min(least, threat.clearance(centre));
        }
        return least;
    }

    // Where a hop to the spot ends or null when it cannot go there. Solid ground drops it onto
    // a floor that is safe to stand on. The end needs room out of lava and a clear way there.
    private Vec3 landing(Vec3 from, Vec3 spot) {
        Vec3 end = solidGround.isOn() ? SafeDrop.below(mc.player, spot) : spot;
        return end != null && Hop.safeAt(mc.player, end) && Hop.clearWay(mc.player, from, end) ? end : null;
    }
}
