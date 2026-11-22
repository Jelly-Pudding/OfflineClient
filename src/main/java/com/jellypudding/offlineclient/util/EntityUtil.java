package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.modules.render.Weather;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Bucketable;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public final class EntityUtil {

    public static final float HEART = 2;

    private static final Map<EntityType<?>, Class<?>> BUILT_AS = builtAs();

    private static final double MOVING_SQR = 0.02 * 0.02;

    // The speed boost a zombified piglin carries whilst it is after someone.
    private static final Identifier PIGLIN_CHASE_BOOST = Identifier.withDefaultNamespace("attacking");

    private static final float FADE_DISTANCE = 20;

    public static final String UNKNOWN_THROWER = "Someone";

    // Hitbox reach is shorter than the centre distance. A reach scan runs this much wider.
    private static final double REACH_SCAN_MARGIN = 4;

    private static final double ITEM_GROW = 0.1;

    // The server counts entity ids up from one. Ours count down from the top
    // of the range and the two never meet.
    private static int nextLocalId = Integer.MAX_VALUE;

    private EntityUtil() {
    }

    // A new entity has no id and its hashCode and equals throw until it gets one.
    public static int nextLocalId() {
        return nextLocalId--;
    }

    public static float totalHealth(LivingEntity entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }

    public static float totalMaxHealth(LivingEntity entity) {
        return entity.getMaxHealth() + entity.getAbsorptionAmount();
    }

    public static float healthShare(LivingEntity entity) {
        float max = totalMaxHealth(entity);
        return max <= 0 ? 0 : Math.clamp(totalHealth(entity) / max, 0f, 1f);
    }

    // Zero hearts turns the check off.
    public static boolean healthAtOrBelow(double hearts) {
        Player player = OfflineClient.MC.player;
        if (hearts <= 0 || player == null) {
            return false;
        }
        return totalHealth(player) <= hearts * HEART;
    }

    public static String hearts(float health) {
        return String.format(Locale.ROOT, "%.1f", health / HEART) + " hearts";
    }

    // Burning in a way that hurts. Water and rain and powder snow put the fire out by
    // themselves and fire resistance takes the harm away.
    public static boolean burnHurts(LivingEntity entity) {
        return entity.isOnFire() && !entity.hasEffect(MobEffects.FIRE_RESISTANCE)
            && !inWaterOrRain(entity) && !entity.isInPowderSnow;
    }

    // In water or in the rain that really falls and not the rain Weather shows.
    public static boolean inWaterOrRain(Entity entity) {
        return Weather.real(entity::isInWaterOrRain);
    }

    // The holder on EntityType itself is deprecated. The lookup goes via the registry.
    public static boolean typeIs(Entity entity, TagKey<EntityType<?>> tag) {
        return BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entity.getType()).is(tag);
    }

    // The class each kind of entity is built as. An entity type names Entity as its base class
    // whatever it makes. What a kind really makes is only written down in how the game declares
    // its constant.
    private static Map<EntityType<?>, Class<?>> builtAs() {
        Map<EntityType<?>, Class<?>> classes = new IdentityHashMap<>();
        for (Field constant : EntityTypes.class.getFields()) {
            if (!Modifier.isStatic(constant.getModifiers()) || constant.getType() != EntityType.class
                || !(constant.getGenericType() instanceof ParameterizedType declared)
                || !(declared.getActualTypeArguments()[0] instanceof Class<?> built)) {
                continue;
            }
            try {
                classes.put((EntityType<?>) constant.get(null), built);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("The game hides the entity type " + constant.getName(), e);
            }
        }
        return classes;
    }

    // False for a kind the game does not declare itself.
    public static boolean builds(EntityType<?> type, Class<?> wanted) {
        Class<?> built = BUILT_AS.get(type);
        return built != null && wanted.isAssignableFrom(built);
    }

    public static boolean isPet(Mob mob) {
        return switch (mob) {
            case TamableAnimal tamable -> tamable.isTame();
            case AbstractHorse horse -> horse.isTamed();
            case Ocelot ocelot -> ocelot.isTrusting();
            case Fox fox -> trustsSomeone(fox);
            default -> mob.isSaddled();
        };
    }

    // What isKept covers put in words for the rows that go by it. Finishes a sentence that
    // ends in mobs that.
    public static final String KEPT_WORDS = "somebody tamed or saddled or built or let out of a bucket or"
        + " kept a baby for good. Trusting foxes and ocelots and every snow golem and copper golem and"
        + " happy ghast count as well.";

    public static boolean isKept(Mob mob) {
        if (isPet(mob) || mob instanceof AgeableMob ageable && ageable.isAgeLocked()) {
            return true;
        }
        return switch (mob) {
            // A camel takes a saddle without ever being tamed.
            case AbstractHorse horse -> horse.isSaddled();
            case IronGolem golem -> golem.isPlayerCreated();
            // Players build these or raise them. An allay in a pillager cage is nobody's.
            case SnowGolem _, CopperGolem _, HappyGhast _ -> true;
            // The bucket flag of a tadpole is always set.
            case Tadpole tadpole -> tadpole.isAgeLocked();
            // A cube that carries a block is a ball to kick about.
            case SulfurCube cube -> cube.fromBucket() && !cube.hasBodyItem();
            case Bucketable bucketable -> bucketable.fromBucket();
            default -> false;
        };
    }

    // A fox is sent the two players it trusts and offers no way to ask whether it has any.
    // They are the only entity references in the data it syncs.
    private static boolean trustsSomeone(Fox fox) {
        List<SynchedEntityData.DataValue<?>> changed = fox.getEntityData().getNonDefaultValues();
        return changed != null && changed.stream().anyMatch(data -> data.value() instanceof Optional<?> held
            && held.orElse(null) instanceof EntityReference<?>);
    }

    // A baby that grows up. A baby zombie stays small for life and fights like a grown one.
    public static boolean isYoung(Mob mob) {
        return mob instanceof Tadpole || mob instanceof AgeableMob ageable && ageable.isBaby();
    }

    public static boolean isYours(Mob mob) {
        Player player = OfflineClient.MC.player;
        if (player == null) {
            return false;
        }
        if (mob == player.getVehicle() || Ridden.has(mob)) {
            return true;
        }
        return mob instanceof OwnableEntity pet && pet.getOwnerReference() != null
            && pet.getOwnerReference().matches(player);
    }

    // A golem a player built still joins fights and goes by the rule of its kind.
    public static boolean isNeutral(Mob mob) {
        return EntityKind.of(mob.getType()) == EntityKind.NEUTRAL;
    }

    // The flag for a fight in progress covers every neutral mob and each species shows a rising
    // temper in its own way.
    public static boolean isCalm(Mob mob) {
        if (mob.isAggressive()) {
            return false;
        }
        return switch (mob) {
            case Pufferfish fish -> fish.getPuffState() == 0;
            case Enderman enderman -> !enderman.isCreepy();
            case ZombifiedPiglin piglin
                -> !piglin.getAttributes().hasModifier(Attributes.MOVEMENT_SPEED, PIGLIN_CHASE_BOOST);
            case Goat goat -> goat.getRammingXHeadRot() == 0;
            case NeutralMob temper -> !temper.isAngry();
            default -> true;
        };
    }

    // Something with no health of its own that a swing can land on such as a crystal or a boat.
    // The server kicks for a hit on a dropped item or an experience orb and neither can be
    // picked. Projectiles are left to ArrowDodge.
    public static boolean canBeStruck(Entity entity) {
        return !(entity instanceof LivingEntity) && !(entity instanceof Projectile) && entity.isAlive()
            && entity.isPickable() && entity.isAttackable();
    }

    public static boolean ridesWithYou(Entity entity) {
        Player player = OfflineClient.MC.player;
        return player != null && player.isPassenger() && entity.getRootVehicle() == player.getRootVehicle();
    }

    public static String nameOf(Player player) {
        return player == null ? null : player.getGameProfile().name();
    }

    // The name to draw for a player. NameProtect may swap it for an alias.
    public static String displayNameOf(Player player) {
        return displayName(nameOf(player));
    }

    public static String displayNameOf(Entity entity) {
        return entity instanceof Player player ? displayNameOf(player) : entity.getName().getString();
    }

    // A villager goes by its trade and a tagged mob by its tag.
    public static boolean isCalled(Entity entity, String typed) {
        return typed.equalsIgnoreCase(entity.getName().getString())
            || typed.equalsIgnoreCase(entity.getType().getDescription().getString());
    }

    public static String displayName(String name) {
        NameProtect nameProtect = Modules.get(NameProtect.class);
        return nameProtect == null || name == null ? name : nameProtect.display(name);
    }

    // Text as it shows on screen. NameProtect swaps every renamed name in it wherever the name stands.
    public static Component displayed(Component text) {
        NameProtect nameProtect = Modules.get(NameProtect.class);
        return nameProtect == null ? text : nameProtect.filter(text);
    }

    // S C A or Sp for a game mode and a question mark when it is not known.
    public static String gameModeLetter(GameType mode) {
        if (mode == null) {
            return "?";
        }
        return switch (mode) {
            case SURVIVAL -> "S";
            case CREATIVE -> "C";
            case ADVENTURE -> "A";
            case SPECTATOR -> "Sp";
        };
    }

    public static boolean isFriend(Entity entity) {
        return entity instanceof Player player
            && OfflineClient.INSTANCE.getFriendManager().isFriend(nameOf(player));
    }

    // A player on the enemies list. isEnemy is wider and takes in every player who is no friend.
    public static boolean isListedEnemy(Entity entity) {
        return entity instanceof Player player
            && OfflineClient.INSTANCE.getFriendManager().isEnemy(nameOf(player));
    }

    // A living player who is neither you nor a friend and not watching from spectator. AntiBot
    // takes fake players out. The ghost Blink leaves of you never counts but the practice dummy does.
    public static boolean isEnemy(Entity entity) {
        return entity instanceof Player player && player != OfflineClient.MC.player && player.isAlive()
            && !player.isSpectator() && !isFriend(player) && !Modules.isBot(player)
            && !Modules.isGhostBody(player);
    }

    public static String storageName(Entity entity) {
        return switch (entity) {
            case MinecartHopper _ -> "hopper minecart";
            case AbstractChestBoat _ -> "chest boat";
            default -> "chest minecart";
        };
    }

    // Someone else in view. You and the bodies only this client puts down never count.
    public static boolean isOtherPlayer(Entity entity) {
        return entity instanceof Player && entity != OfflineClient.MC.player && !Modules.isLocalBody(entity);
    }

    public static Player otherPlayerNamed(String name) {
        if (OfflineClient.MC.level == null) {
            return null;
        }
        for (Player player : OfflineClient.MC.level.players()) {
            if (isOtherPlayer(player) && nameOf(player).equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    public static boolean isRealPlayer(Entity entity) {
        return isOtherPlayer(entity) && !Modules.isBot(entity);
    }

    public static String throwerName(Projectile projectile) {
        String name = knownThrower(projectile);
        return name == null ? UNKNOWN_THROWER : name;
    }

    // The client only fills the thrower in whilst they are in sight. A pearl can be named after
    // that too.
    public static String knownThrower(Projectile projectile) {
        Entity owner = projectile.getOwner();
        if (owner != null) {
            return owner.getName().getString();
        }
        return projectile instanceof ThrownEnderpearl pearl ? PearlOwners.INSTANCE.nameOf(pearl) : null;
    }

    // From the eyes to the nearest point of the hitbox. This is how the game measures reach.
    public static double reachDistance(Player from, Entity to) {
        return Math.sqrt(to.getBoundingBox().distanceToSqr(from.getEyePosition()));
    }

    // The entity reach the server measures with. Reach grows the client's own figure.
    public static double serverEntityReach() {
        return OfflineClient.MC.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
    }

    public static boolean isMoving(Vec3 velocity) {
        return velocity.horizontalDistanceSqr() > MOVING_SQR;
    }

    // The distance covered on the last tick. The client keeps no velocity field.
    public static Vec3 velocityOf(Entity entity) {
        return new Vec3(entity.getX() - entity.xOld, entity.getY() - entity.yOld,
            entity.getZ() - entity.zOld);
    }

    // How far you move each second along each axis. It reads how far you moved on the last
    // tick since a rider has no motion of their own. Timer fits more ticks into each second.
    public static Vec3 ownVelocity() {
        Player player = OfflineClient.MC.player;
        return player == null ? Vec3.ZERO
            : velocityOf(player).scale(SharedConstants.TICKS_PER_SECOND * Timer.current());
    }

    public static double ownSpeed() {
        return ownVelocity().horizontalDistance();
    }

    public static double lookAngleTo(Entity target) {
        Player player = OfflineClient.MC.player;
        return player == null ? 180 : lookAngle(player, target);
    }

    // Another player looks where their head turns.
    public static double lookAngle(Entity viewer, Entity target) {
        return degreesBetween(viewer.getViewVector(1),
            target.getBoundingBox().getCenter().subtract(viewer.getEyePosition()));
    }

    // Degrees between where you look and the nearest part of the target. Nought whilst the
    // crosshair lies on it. The middle of a mob that stands right before you is far below the
    // crosshair that rests on its head.
    public static double lookAngleToNearest(Entity target) {
        Player player = OfflineClient.MC.player;
        if (player == null) {
            return 180;
        }
        Vec3 eyes = player.getEyePosition();
        Vec3 view = player.getViewVector(1);
        AABB box = target.getBoundingBox();
        double along = Math.max(0, box.getCenter().subtract(eyes).dot(view));
        double through = along + box.getXsize() + box.getYsize() + box.getZsize();
        if (box.contains(eyes) || box.clip(eyes, eyes.add(view.scale(through))).isPresent()) {
            return 0;
        }
        Vec3 onRay = eyes.add(view.scale(along));
        Vec3 nearest = new Vec3(Math.clamp(onRay.x, box.minX, box.maxX), Math.clamp(onRay.y, box.minY, box.maxY),
            Math.clamp(onRay.z, box.minZ, box.maxZ));
        return degreesBetween(view, nearest.subtract(eyes));
    }

    private static double degreesBetween(Vec3 view, Vec3 toTarget) {
        double length = toTarget.length();
        if (length < 1.0E-4) {
            return 0;
        }
        return Math.toDegrees(Math.acos(Math.clamp(view.dot(toTarget) / length, -1, 1)));
    }

    public static boolean interact(Entity target, InteractionHand hand, boolean rotate) {
        Vec3 centre = target.getBoundingBox().getCenter();
        if (rotate) {
            BlockUtil.faceVector(centre);
        }
        Minecraft mc = OfflineClient.MC;
        if (!mc.gameMode.interact(mc.player, target, new EntityHitResult(target, centre), hand).consumesAction()) {
            return false;
        }
        SwingMode.swingUse(hand);
        return true;
    }

    public static AABB lerpedBox(Entity entity, float partialTicks) {
        Vec3 lerped = entity.getPosition(partialTicks);
        return entity.getBoundingBox().move(lerped.subtract(entity.position()));
    }

    public static AABB markBox(Entity entity, float partialTicks) {
        AABB box = lerpedBox(entity, partialTicks);
        return entity instanceof ItemEntity ? box.inflate(ITEM_GROW) : box;
    }

    public static int distanceColor(Entity entity) {
        Player self = OfflineClient.MC.player;
        float away = self == null ? FADE_DISTANCE : self.distanceTo(entity);
        return ColorUtil.hsv(Math.clamp(away / (2 * FADE_DISTANCE), 0f, 1f) * ColorUtil.GREEN_HUE, 1f, 1f);
    }

    public static Entity nearest(double range, Predicate<Entity> test) {
        return best(range, TargetPriority.NEAREST, test);
    }

    public static Player nearestEnemy(double range) {
        return (Player) nearest(range, EntityUtil::isEnemy);
    }

    public static Entity best(double range, TargetPriority priority, Predicate<Entity> test) {
        Minecraft mc = OfflineClient.MC;
        if (mc.player == null || mc.level == null) {
            return null;
        }
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            // The range goes first. The test may search the area around each entity.
            if (entity == mc.player || mc.player.distanceTo(entity) > range || !test.test(entity)) {
                continue;
            }
            double score = priority.score(entity);
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    public static List<Entity> ranked(double range, TargetPriority priority, Predicate<Entity> test) {
        Minecraft mc = OfflineClient.MC;
        List<Entity> found = new ArrayList<>();
        if (mc.player == null || mc.level == null) {
            return found;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity != mc.player && mc.player.distanceTo(entity) <= range && test.test(entity)) {
                found.add(entity);
            }
        }
        found.sort(Comparator.comparingDouble(priority::score));
        return found;
    }

    public static Entity bestInReach(double reach, TargetPriority priority, Predicate<Entity> test) {
        return best(reach + REACH_SCAN_MARGIN, priority,
            entity -> test.test(entity) && reachDistance(OfflineClient.MC.player, entity) <= reach);
    }

    public static Player bestEnemy(double range, TargetPriority priority) {
        return (Player) best(range, priority, EntityUtil::isEnemy);
    }
}
