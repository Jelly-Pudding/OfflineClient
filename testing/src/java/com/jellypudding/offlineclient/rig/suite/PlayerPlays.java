package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PaperServer;
import com.jellypudding.offlineclient.rig.duo.Partner;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// What only a second player can show. It is hit and followed and talked to and it says what it
// sees of the first player. The server counts what really reached it.
final class PlayerPlays {

    private static final int FORWARD = InputConstants.KEY_W;
    private static final int LEFT_BUTTON = InputConstants.MOUSE_BUTTON_LEFT;
    private static final int MIDDLE_BUTTON = InputConstants.MOUSE_BUTTON_MIDDLE;
    private static final int JUMP = InputConstants.KEY_SPACE;
    // Ticks after the jump key at which a jumping player is well on the way down.
    private static final int FALLING_AFTER = 8;
    private static final float NORTH = 180;

    private static final int WATCH_TICKS = 60;
    private static final int SETTLE_TICKS = 20;
    private static final int SEEN_TICKS = 5;
    private static final int WALK_TICKS = 30;
    private static final int ANSWER_TICKS = 100;
    private static final int KILL_TICKS = 300;
    private static final int SPAM_TICKS = 200;
    private static final int FOLLOW_TICKS = 100;
    private static final int FIGHT_TICKS = 120;
    private static final int FEWEST = 3;
    private static final int REPEATS = 3;

    // The eyes of a player who really crouches drop by this much and more.
    private static final double STEADY_EYES = 0.05;
    // The chest of the second player where it stands in reach of the first.
    private static final Vec3 IN_REACH = new Vec3(2.5, -59.0, 0.5);
    private static final double PUSHED = 0.3;
    private static final double HELD = 0.05;
    private static final double STILL = 0.1;
    private static final double WALKED = 3;
    private static final double KEPT = 4.5;
    private static final double EYE_TO_EYE = 0.5;
    private static final double SMASHED = 20;
    private static final float EAST = -90;
    private static final double TURNED_TO = 6;
    private static final int TAB = InputConstants.KEY_TAB;
    private static final int BACK_TICKS = 400;
    private static final Pattern NUMBER = Pattern.compile("\\d{1,4}");

    private static final String SWORD = "diamond_sword";
    private static final String HITS = "hits";
    private static final String CRITS = "crits";
    private static final String DAMAGE = "hitDamage";
    private static final String FRIEND = "friend add ";
    private static final String NO_FRIEND = "friend remove ";
    private static final String REPEATED_LINE = "the same words again";
    private static final String SHARED_KEY = "rigkey";
    private static final String SECRET = "meet at the old oak";
    private static final String OBSIDIAN = "obsidian";
    private static final String HARDY = "effect give %s resistance 600 4 true";
    // The block the second player stands in whilst in reach and the one over its head.
    private static final BlockPos STOOD_IN = new BlockPos(2, -60, 0);
    private static final BlockPos OVERHEAD = new BlockPos(2, -58, 0);
    // Ground a block north of that spot. A crystal set on it stands right beside the player.
    private static final BlockPos CRYSTAL_BASE = new BlockPos(2, -61, -1);
    private static final List<String> CRIT_MODES = List.of("packet", "subtle", "mini_jump", "full_jump");

    private PlayerPlays() {
    }

    static void addTo(Map<String, ServerSuite.Play> plays) {
        plays.put("another player sees Derp twerk and your own eyes stay level", PlayerPlays::twerk);
        plays.put("another player sees Derp flail", PlayerPlays::flail);
        plays.put("SkinDerp blinks the skin the server shows to others", PlayerPlays::skinDerp);
        plays.put("KillAura hits another player and leaves a friend alone", PlayerPlays::killAura);
        plays.put("TriggerBot hits the player under the crosshair and nobody else", PlayerPlays::triggerBot);
        plays.put("Criticals makes the hits on another player critical in every mode", PlayerPlays::criticals);
        plays.put("Criticals lands a mace smash on another player", PlayerPlays::maceSmash);
        plays.put("NoKnockback holds you still when another player hits you", PlayerPlays::noKnockback);
        plays.put("FightBot runs to another player and fights it", PlayerPlays::fightBot);
        plays.put("Knockback knocks another player further", PlayerPlays::knockback);
        plays.put("CrystalAura blows a crystal up beside another player", PlayerPlays::crystalAura);
        plays.put("AutoTrap builds a roof over another player", PlayerPlays::autoTrap);
        plays.put("AutoWeb webs the block another player stands in", PlayerPlays::autoWeb);
        plays.put("AnchorAura blows an anchor up beside another player", PlayerPlays::anchorAura);
        plays.put("TntAura lights TNT beside another player", PlayerPlays::tntAura);
        plays.put("LavaAura pours lava on another player", PlayerPlays::lavaAura);
        plays.put("AutoAnvil drops an anvil on another player", PlayerPlays::autoAnvil);
        plays.put("Hitboxes lets a swing just beside another player land", PlayerPlays::hitboxes);
        plays.put("AimAssist turns your view to the player you fight", PlayerPlays::aimAssist);
        plays.put("TpAura hits another player from all sides", PlayerPlays::tpAura);
        plays.put("Honker blows its horn when another player comes near", PlayerPlays::honker);
        plays.put("BetterTab writes the ping into the player list", PlayerPlays::betterTab);
        plays.put("LogoutSpots marks where another player left", PlayerPlays::logoutSpots);
        plays.put("Linger keeps the world once the connection drops", PlayerPlays::linger);
        plays.put("AutoReconnect joins again after a kick", PlayerPlays::autoReconnect);
        plays.put("AutoLog leaves the server once your health is low", PlayerPlays::autoLog);
        plays.put("KillMessage names the player you killed in chat", PlayerPlays::killMessage);
        plays.put("another player sees you stand still whilst Blink holds your moves", PlayerPlays::blink);
        plays.put("Follow walks after another player", PlayerPlays::follow);
        plays.put("RemoteView looks through the eyes of another player", PlayerPlays::remoteView);
        plays.put("MiddleClickExtra makes a friend of the player you middle click", PlayerPlays::middleClick);
        plays.put("Notifier tells you when a player comes into sight", PlayerPlays::notifier);
        plays.put("MessageAura whispers to a player who comes into view", PlayerPlays::messageAura);
        plays.put("MassTPA asks every player for a teleport", PlayerPlays::massTpa);
        plays.put("Spam sends its line to the server", PlayerPlays::spam);
        plays.put("AntiSpam stacks a line another player repeats", PlayerPlays::antiSpam);
        plays.put("SecretChat is read by a player with the key and not by the server", PlayerPlays::secretChat);
        plays.put("ChatAlerts shows a toast when another player writes your name", PlayerPlays::chatAlerts);
    }

    private static void twerk(Rig rig, Duo duo) {
        String me = rig.world().player();
        rig.client().set("Derp", "Mode", "twerk");
        rig.client().enable("Derp");
        duo.partner().watch(me, WATCH_TICKS);
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (int tick = 0; tick < WATCH_TICKS; tick++) {
            rig.ticks(1);
            double eyes = rig.ask(mc -> mc.gameRenderer.mainCamera().position().y - mc.player.getY());
            lowest = Math.min(lowest, eyes);
            highest = Math.max(highest, eyes);
        }
        Map<String, String> seen = duo.partner().watched();
        double sneaks = count(duo, "sneaks");
        rig.note("The second player saw " + seen.get("crouches") + " crouches and the server counted " + sneaks
            + " and the first player's eyes moved " + told(highest - lowest) + " blocks");
        rig.expect(Integer.parseInt(seen.get("crouches")) >= FEWEST, "The second player saw no twerk");
        rig.expect(sneaks >= FEWEST, "The server heard no sneaks");
        rig.expect(highest - lowest < STEADY_EYES, "The first player's own view went up and down");
    }

    private static void flail(Rig rig, Duo duo) {
        rig.client().set("Derp", "Mode", "flail");
        rig.client().enable("Derp");
        duo.partner().watch(rig.world().player(), WATCH_TICKS);
        rig.ticks(WATCH_TICKS);
        Map<String, String> seen = duo.partner().watched();
        double swings = count(duo, "swings");
        rig.note("The second player saw " + seen.get("swings") + " swings and the server counted " + swings);
        rig.expect(Integer.parseInt(seen.get("swings")) >= FEWEST, "The second player saw no flailing arm");
        rig.expect(swings >= FEWEST, "The server heard no swings");
    }

    private static void skinDerp(Rig rig, Duo duo) {
        rig.client().enable("SkinDerp");
        rig.ticks(WATCH_TICKS);
        double changes = count(duo, "skinChanges");
        rig.client().disable("SkinDerp");
        rig.note("The server was told of " + changes + " changes to the skin in " + WATCH_TICKS + " ticks");
        rig.expect(changes >= FEWEST, "The skin the server shows to others did not blink");
    }

    private static void killAura(Rig rig, Duo duo) {
        arm(rig);
        duo.partnerInReach(true);
        rig.client().enable("KillAura");
        rig.ticks(WATCH_TICKS);
        double hits = count(duo, HITS);
        rig.client().command(FRIEND + Partner.NAME);
        try {
            duo.server().resetTallies();
            rig.ticks(WATCH_TICKS);
            double onFriend = count(duo, HITS);
            rig.note("KillAura landed " + hits + " hits in " + WATCH_TICKS + " ticks and " + onFriend
                + " once the player was a friend");
            rig.expect(hits >= FEWEST, "KillAura did not hit a player who stood in reach");
            rig.expect(onFriend == 0, "KillAura hit a friend " + onFriend + " times");
        } finally {
            rig.client().command(NO_FRIEND + Partner.NAME);
        }
    }

    private static void triggerBot(Rig rig, Duo duo) {
        arm(rig);
        duo.partnerInReach(true);
        rig.controls().aimAt(IN_REACH);
        rig.client().enable("TriggerBot");
        rig.ticks(WATCH_TICKS);
        double hits = count(duo, HITS);
        rig.controls().look(NORTH, 0);
        rig.ticks(SETTLE_TICKS);
        duo.server().resetTallies();
        rig.ticks(WATCH_TICKS);
        double lookingAway = count(duo, HITS);
        rig.note("TriggerBot landed " + hits + " hits with the player under the crosshair and " + lookingAway
            + " whilst looking away");
        rig.expect(hits >= FEWEST, "TriggerBot did not hit the player under the crosshair");
        rig.expect(lookingAway == 0, "TriggerBot hit a player the crosshair was nowhere near");
    }

    private static void criticals(Rig rig, Duo duo) {
        arm(rig);
        duo.partnerInReach(true);
        rig.client().enable("KillAura");
        rig.ticks(WATCH_TICKS);
        Map<String, Double> plain = duo.leadTally();
        rig.expect(plain.getOrDefault(HITS, 0.0) >= FEWEST, "KillAura did not hit a player who stood in reach");
        rig.expect(plain.getOrDefault(CRITS, 0.0) == 0,
            "Hits were critical before Criticals was on. That proves nothing");
        rig.client().disable("KillAura");
        rig.expect(jumpingHit(rig, duo), "The server saw no critical hit in a jump by hand. That proves nothing");
        rig.client().enable("KillAura");
        rig.client().enable("Criticals");
        List<String> weak = new ArrayList<>();
        for (String mode : CRIT_MODES) {
            rig.client().set("Criticals", "Mode", mode);
            rig.ticks(SETTLE_TICKS);
            duo.server().resetTallies();
            int setBack = rig.setbacks();
            int mark = duo.server().logMark();
            rig.ticks(WATCH_TICKS);
            Map<String, Double> seen = duo.leadTally();
            List<String> told = duo.server().allHeard("hit " + rig.world().player(), mark);
            rig.note(mode + " gave " + told.subList(0, Math.min(2, told.size())));
            double hits = seen.getOrDefault(HITS, 0.0);
            double crits = seen.getOrDefault(CRITS, 0.0);
            rig.note(String.format(Locale.ROOT, "%s made %d of %d hits critical at %.1f damage a hit against %.1f "
                + "and the server set the player back %d times", mode, (int) crits, (int) hits,
                seen.getOrDefault(DAMAGE, 0.0) / Math.max(1, hits),
                plain.getOrDefault(DAMAGE, 0.0) / plain.getOrDefault(HITS, 1.0), rig.setbacks() - setBack));
            if (hits < FEWEST || crits < hits - 1) {
                weak.add(mode);
            }
        }
        rig.expect(weak.isEmpty(), "Hits were not critical in the modes " + weak);
    }

    // A mace hits for six from the ground. A smash adds what the fall is worth.
    private static void maceSmash(Rig rig, Duo duo) {
        rig.world().hotbar(0, "mace");
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(0));
        duo.partnerInReach(true);
        rig.client().set("Criticals", "Mace smash", "on");
        rig.client().enable("Criticals");
        rig.client().enable("KillAura");
        int setBack = rig.setbacks();
        rig.ticks(WATCH_TICKS * 2);
        rig.client().disable("KillAura");
        Map<String, Double> seen = duo.leadTally();
        double hits = seen.getOrDefault(HITS, 0.0);
        double each = seen.getOrDefault(DAMAGE, 0.0) / Math.max(1, hits);
        rig.note(String.format(Locale.ROOT, "%d hits landed at %.1f damage a hit and the server set the player back "
            + "%d times", (int) hits, each, rig.setbacks() - setBack));
        rig.expect(hits >= FEWEST, "The mace did not hit a player who stood in reach");
        rig.expect(each >= SMASHED, "The hits were no smashes");
    }

    // A jump by hand and a swing on the way down. True when the server called that hit critical.
    private static boolean jumpingHit(Rig rig, Duo duo) {
        rig.controls().aimAt(IN_REACH);
        rig.ticks(SETTLE_TICKS);
        duo.server().resetTallies();
        int mark = duo.server().logMark();
        rig.controls().hold(JUMP);
        rig.ticks(2);
        rig.controls().release(JUMP);
        rig.ticks(FALLING_AFTER);
        rig.controls().click(LEFT_BUTTON);
        rig.ticks(SETTLE_TICKS);
        rig.note("A jump by hand gave " + duo.server().allHeard("hit " + rig.world().player(), mark));
        rig.controls().look(NORTH, 0);
        return count(duo, CRITS) == 1;
    }

    private static void noKnockback(Rig rig, Duo duo) {
        duo.partnerAt(IN_REACH.x, -60, IN_REACH.z, 90);
        double pushed = punched(rig, duo);
        duo.leadHome();
        rig.client().enable("NoKnockback");
        double held = punched(rig, duo);
        rig.note("A hit pushed the first player " + told(pushed) + " blocks and with NoKnockback " + told(held));
        rig.expect(pushed >= PUSHED, "A hit did not push the player without NoKnockback. That proves nothing");
        rig.expect(held <= HELD, "A hit still pushed the player " + told(held) + " blocks");
    }

    // The second player hits the first with its fist. Blocks the hit moved the first player.
    private static double punched(Rig rig, Duo duo) {
        Vec3 from = rig.world().where();
        duo.partner().ask("aim", rig.world().player());
        duo.partner().ask("ticks", "2");
        duo.partner().ask("click", Integer.toString(LEFT_BUTTON));
        rig.ticks(SETTLE_TICKS);
        return rig.world().where().subtract(from).horizontalDistance();
    }

    private static void fightBot(Rig rig, Duo duo) {
        arm(rig);
        duo.partnerAt(0.5, -60, -9.5, 0);
        duo.server().console("effect give " + Partner.NAME + " resistance 600 4 true");
        Vec3 from = rig.world().where();
        rig.client().enable("FightBot");
        rig.ticks(FIGHT_TICKS);
        double hits = count(duo, HITS);
        double ran = rig.world().where().subtract(from).horizontalDistance();
        rig.client().disable("FightBot");
        rig.note("FightBot ran " + told(ran) + " blocks and landed " + hits + " hits in " + FIGHT_TICKS + " ticks");
        rig.expect(ran >= WALKED, "FightBot never ran to its target");
        rig.expect(hits >= FEWEST, "FightBot did not fight the player it ran to");
    }

    private static void killMessage(Rig rig, Duo duo) {
        arm(rig);
        duo.partnerInReach(false);
        rig.client().enable("KillMessage");
        int mark = duo.server().logMark();
        rig.client().enable("KillAura");
        String said = duo.server().heard("chat " + rig.world().player(), mark, KILL_TICKS);
        rig.client().disable("KillAura");
        rig.note("After the kill the first player said " + said);
        rig.expect(said != null, "Nothing was said in chat after the kill");
        rig.expect(said.contains(Partner.NAME), "The line does not name who died. It reads " + said);
    }

    private static void blink(Rig rig, Duo duo) {
        String me = rig.world().player();
        Vec3 before = spot(duo.partner().see(me));
        rig.client().enable("Blink");
        rig.controls().hold(FORWARD);
        rig.ticks(WALK_TICKS);
        rig.controls().release(FORWARD);
        Vec3 held = spot(duo.partner().see(me));
        rig.client().disable("Blink");
        // The held walk goes out a tick at a time and takes as long as it was held for.
        rig.ticks(WALK_TICKS * 2 + SETTLE_TICKS);
        Vec3 after = spot(duo.partner().see(me));
        rig.note("The second player saw the first move " + told(held.distanceTo(before)) + " blocks whilst Blink held "
            + "and " + told(after.distanceTo(before)) + " once it let go");
        rig.expect(held.distanceTo(before) < STILL, "The second player saw the first move whilst Blink held");
        rig.expect(after.distanceTo(before) >= WALKED, "The second player never saw the first arrive");
    }

    private static void follow(Rig rig, Duo duo) {
        duo.partnerAt(0.5, -60, -9.5, 0);
        rig.client().enable("Follow");
        rig.ticks(FOLLOW_TICKS);
        double first = apart(duo);
        duo.partnerAt(8.5, -60, -9.5, 0);
        rig.ticks(FOLLOW_TICKS);
        double second = apart(duo);
        rig.client().disable("Follow");
        rig.note("Follow came within " + told(first) + " blocks and after the player moved on within " + told(second));
        rig.expect(first <= KEPT, "Follow stayed " + told(first) + " blocks behind");
        rig.expect(second <= KEPT, "Follow stayed " + told(second) + " blocks behind once the player moved on");
    }

    private static void remoteView(Rig rig, Duo duo) {
        rig.client().enable("RemoteView");
        rig.ticks(SETTLE_TICKS);
        double fromTheirs = rig.ask(mc -> mc.gameRenderer.mainCamera().position()
            .distanceTo(named(mc, Partner.NAME).getEyePosition()));
        rig.client().disable("RemoteView");
        rig.ticks(SETTLE_TICKS);
        double fromOwn = rig.ask(mc -> mc.gameRenderer.mainCamera().position().distanceTo(mc.player.getEyePosition()));
        rig.note("The camera sat " + told(fromTheirs) + " blocks from the other player's eyes and afterwards "
            + told(fromOwn) + " from your own");
        rig.expect(fromTheirs < EYE_TO_EYE, "The camera did not go to the other player's eyes");
        rig.expect(fromOwn < EYE_TO_EYE, "The camera did not come back");
    }

    private static void middleClick(Rig rig, Duo duo) {
        duo.partnerInReach(true);
        rig.client().enable("MiddleClickExtra");
        rig.controls().aimAt(IN_REACH);
        rig.ticks(2);
        rig.controls().click(MIDDLE_BUTTON);
        rig.ticks(SETTLE_TICKS);
        boolean friend = rig.ask(mc -> OfflineClient.INSTANCE.getFriendManager().isFriend(Partner.NAME));
        rig.client().command(NO_FRIEND + Partner.NAME);
        rig.expect(friend, "The player under the crosshair did not become a friend");
    }

    private static void notifier(Rig rig, Duo duo) {
        duo.partnerAway();
        rig.client().enable("Notifier");
        rig.ticks(SETTLE_TICKS);
        int mark = rig.client().chatMark();
        duo.partnerHome();
        rig.ticks(SETTLE_TICKS);
        List<String> said = rig.client().saidSince(mark);
        rig.note("Notifier said " + said);
        rig.expect(said.stream().anyMatch(line -> line.contains(Partner.NAME)),
            "Notifier said nothing about a player who came into sight");
    }

    private static void messageAura(Rig rig, Duo duo) {
        duo.partnerAway();
        rig.client().enable("MessageAura");
        rig.ticks(SETTLE_TICKS);
        int mark = duo.server().logMark();
        duo.partnerHome();
        String typed = duo.server().heard("command " + rig.world().player(), mark, ANSWER_TICKS);
        rig.note("The server heard " + typed);
        rig.expect(typed != null && typed.startsWith("/msg " + Partner.NAME + " "),
            "No whisper went to the player who came into view");
    }

    private static void massTpa(Rig rig, Duo duo) {
        int mark = duo.server().logMark();
        rig.client().enable("MassTPA");
        String typed = duo.server().heard("command " + rig.world().player(), mark, ANSWER_TICKS);
        rig.client().disable("MassTPA");
        rig.note("The server heard " + typed);
        rig.expect(("/tpa " + Partner.NAME).equals(typed), "No request went to the other player");
    }

    private static void spam(Rig rig, Duo duo) {
        String line = rig.client().valueOf("Spam", "Line 1");
        int mark = duo.server().logMark();
        rig.client().enable("Spam");
        String said = duo.server().heard("chat " + rig.world().player(), mark, SPAM_TICKS);
        rig.client().disable("Spam");
        rig.expect(line.equals(said), "Spam sent " + said + " and its line is " + line);
    }

    private static void antiSpam(Rig rig, Duo duo) {
        rig.client().enable("AntiSpam");
        for (int said = 0; said < REPEATS; said++) {
            duo.partner().ask("chat", REPEATED_LINE);
            rig.ticks(SEEN_TICKS);
        }
        rig.ticks(SEEN_TICKS);
        List<String> shown = rig.frames().capture("antispam").texts().stream()
            .filter(text -> text.contains(REPEATED_LINE)).toList();
        rig.note("The chat shows " + shown);
        rig.expect(shown.size() == 1, "The chat shows the repeated line " + shown.size() + " times");
        rig.expect(shown.getFirst().endsWith(" x" + REPEATS), "The line carries no count of " + REPEATS);
    }

    private static void secretChat(Rig rig, Duo duo) {
        rig.client().set("SecretChat", "Key", SHARED_KEY);
        rig.client().enable("SecretChat");
        duo.partner().ask("set", "SecretChat", "Key", SHARED_KEY);
        duo.partner().ask("enable", "SecretChat");
        int mark = duo.server().logMark();
        rig.client().say(rig.client().valueOf("SecretChat", "Prefix") + SECRET);
        String sealed = duo.server().heard("chat " + rig.world().player(), mark, ANSWER_TICKS);
        rig.ticks(SEEN_TICKS * 2);
        String shown = duo.partner().ask("texts", "secretchat");
        rig.note("The server passed on " + sealed);
        rig.expect(sealed != null, "Nothing reached the server");
        rig.expect(!sealed.contains(SECRET), "The server could read the secret line");
        rig.expect(shown.contains(SECRET), "The second player has the key and could not read the line");
    }

    // A sword in the first slot and that slot in the hand.
    private static void arm(Rig rig) {
        rig.world().hotbar(0, SWORD);
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(0));
        rig.ticks(2);
    }

    private static double count(Duo duo, String what) {
        return duo.leadTally().getOrDefault(what, 0.0);
    }

    private static double apart(Duo duo) {
        return spot(duo.lead()).distanceTo(spot(duo.server().player(Partner.NAME)));
    }

    private static Vec3 spot(Map<String, String> facts) {
        return new Vec3(Double.parseDouble(facts.get("x")), Double.parseDouble(facts.get("y")),
            Double.parseDouble(facts.get("z")));
    }

    private static Player named(Minecraft mc, String name) {
        for (Player each : mc.level.players()) {
            if (each.getName().getString().equals(name)) {
                return each;
            }
        }
        throw new RigFailure("The first player sees nobody called " + name);
    }

    private static String told(double blocks) {
        return String.format(Locale.ROOT, "%.2f", blocks);
    }

    private static void knockback(Rig rig, Duo duo) {
        arm(rig);
        double plain = knocked(rig, duo);
        rig.client().enable("Knockback");
        double far = knocked(rig, duo);
        rig.note("One hit knocked the second player " + told(plain) + " blocks back and with Knockback "
            + told(far));
        rig.expect(plain >= PUSHED, "The hit did not move the second player without Knockback. That proves nothing");
        rig.expect(far >= plain + PUSHED, "Knockback knocked the second player no further");
    }

    // One full swing at the second player with open ground at its back. Blocks it went.
    private static double knocked(Rig rig, Duo duo) {
        duo.partnerAt(IN_REACH.x, -60, IN_REACH.z, 90);
        duo.server().console(String.format(Locale.ROOT, HARDY, Partner.NAME));
        Vec3 from = spot(duo.server().player(Partner.NAME));
        rig.controls().aimAt(IN_REACH);
        rig.ticks(SETTLE_TICKS);
        rig.controls().click(LEFT_BUTTON);
        rig.ticks(SETTLE_TICKS);
        return spot(duo.server().player(Partner.NAME)).subtract(from).horizontalDistance();
    }

    // The first player cannot be hurt and its own blast does not hold the module back.
    private static void crystalAura(Rig rig, Duo duo) {
        duo.partnerAt(IN_REACH.x, -60, IN_REACH.z, 90);
        rig.world().setBlock(CRYSTAL_BASE, OBSIDIAN);
        duo.server().console(String.format(Locale.ROOT, HARDY, rig.world().player()));
        rig.world().hotbar(0, "end_crystal 16");
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(0));
        rig.ticks(SEEN_TICKS);
        rig.client().enable("CrystalAura");
        Props.await(rig, "a blast to hurt the second player", ANSWER_TICKS,
            () -> duo.server().tally(Partner.NAME).getOrDefault("hurt.ENTITY_EXPLOSION", 0.0) >= 1);
        rig.client().disable("CrystalAura");
    }

    private static void autoTrap(Rig rig, Duo duo) {
        duo.partnerAt(IN_REACH.x, -60, IN_REACH.z, 90);
        rig.world().hotbar(0, OBSIDIAN + " 64");
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(0));
        rig.client().enable("AutoTrap");
        Props.await(rig, "a block over the second player's head", KILL_TICKS, () -> Props.is(rig, OVERHEAD, OBSIDIAN));
    }

    private static void autoWeb(Rig rig, Duo duo) {
        duo.partnerAt(IN_REACH.x, -60, IN_REACH.z, 90);
        rig.world().hotbar(0, "cobweb 16");
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(0));
        rig.client().enable("AutoWeb");
        Props.await(rig, "a web in the second player's block", ANSWER_TICKS, () -> Props.is(rig, STOOD_IN, "cobweb"));
    }

    // The first player cannot be hurt. Its own blast holds no module back and does not end the case.
    private static void hardy(Rig rig, Duo duo) {
        duo.server().console(String.format(Locale.ROOT, HARDY, rig.world().player()));
    }

    private static void inHand(Rig rig, String item) {
        rig.world().hotbar(0, item);
        rig.act(mc -> mc.player.getInventory().setSelectedSlot(0));
        rig.ticks(2);
    }

    private static double hurtBy(Duo duo, String cause) {
        return duo.server().tally(Partner.NAME).getOrDefault("hurt." + cause, 0.0);
    }

    private static void anchorAura(Rig rig, Duo duo) {
        duo.partnerAt(IN_REACH.x, -60, IN_REACH.z, 90);
        hardy(rig, duo);
        inHand(rig, "respawn_anchor 8");
        rig.world().hotbar(1, "glowstone 16");
        rig.client().enable("AnchorAura");
        Props.await(rig, "a blast to hurt the second player", KILL_TICKS, () -> hurtBy(duo, "BLOCK_EXPLOSION") >= 1);
        rig.client().disable("AnchorAura");
    }

    private static void tntAura(Rig rig, Duo duo) {
        duo.partnerAt(IN_REACH.x, -60, IN_REACH.z, 90);
        hardy(rig, duo);
        inHand(rig, "tnt 8");
        rig.world().hotbar(1, "flint_and_steel");
        rig.client().enable("TntAura");
        Props.await(rig, "lit TNT beside the second player", KILL_TICKS, () -> Props.living(rig, "tnt") >= 1);
        rig.client().disable("TntAura");
    }

    // The second player stands four blocks off. Lava runs three and the module keeps it off you.
    private static void lavaAura(Rig rig, Duo duo) {
        hardy(rig, duo);
        inHand(rig, "lava_bucket");
        rig.client().enable("LavaAura");
        Props.await(rig, "lava to burn the second player", KILL_TICKS,
            () -> hurtBy(duo, "LAVA") + hurtBy(duo, "FIRE") + hurtBy(duo, "FIRE_TICK") >= 1);
        rig.client().disable("LavaAura");
    }

    private static void autoAnvil(Rig rig, Duo duo) {
        duo.partnerInReach(false);
        inHand(rig, "anvil 8");
        rig.world().hotbar(1, OBSIDIAN + " 16");
        rig.client().enable("AutoAnvil");
        Props.await(rig, "an anvil to land on the second player", KILL_TICKS, () -> hurtBy(duo, "FALLING_BLOCK") >= 1);
        rig.client().disable("AutoAnvil");
    }

    // The crosshair sits half a block to the side of the second player and past its body.
    private static void hitboxes(Rig rig, Duo duo) {
        arm(rig);
        duo.partnerInReach(true);
        Vec3 beside = IN_REACH.add(0, 0, 0.5);
        double missed = swingAt(rig, duo, beside);
        rig.client().enable("Hitboxes");
        double landed = swingAt(rig, duo, beside);
        rig.expect(missed == 0, "The swing beside the player landed without Hitboxes. That proves nothing");
        rig.expect(landed == 1, "The swing beside the player did not land");
    }

    private static double swingAt(Rig rig, Duo duo, Vec3 point) {
        duo.server().resetTallies();
        rig.controls().aimAt(point);
        rig.ticks(SETTLE_TICKS);
        rig.controls().click(LEFT_BUTTON);
        rig.ticks(SETTLE_TICKS);
        return count(duo, HITS);
    }

    private static void aimAssist(Rig rig, Duo duo) {
        arm(rig);
        duo.partnerInReach(true);
        swingAt(rig, duo, IN_REACH);
        rig.client().enable("AimAssist");
        rig.controls().look(EAST + 30, 0);
        rig.ticks(SETTLE_TICKS * 2);
        float yaw = rig.ask(mc -> mc.player.getYRot());
        rig.note("The view was turned thirty degrees off the player and came to rest "
            + told(Mth.wrapDegrees(yaw - EAST)) + " degrees off it");
        rig.expect(Math.abs(Mth.wrapDegrees(yaw - EAST)) < TURNED_TO, "The view did not turn to the player");
    }

    private static void tpAura(Rig rig, Duo duo) {
        arm(rig);
        duo.partnerInReach(true);
        rig.client().enable("TpAura");
        rig.ticks(WATCH_TICKS);
        Map<String, Double> seen = duo.leadTally();
        rig.client().disable("TpAura");
        rig.note("It landed " + seen.getOrDefault(HITS, 0.0) + " hits and the server saw the player move "
            + told(seen.getOrDefault("moved", 0.0)) + " blocks");
        rig.expect(seen.getOrDefault(HITS, 0.0) >= FEWEST, "TpAura did not hit a player in reach");
        rig.expect(seen.getOrDefault("moved", 0.0) >= 2, "The server never saw the player move round its target");
    }

    private static void honker(Rig rig, Duo duo) {
        duo.partnerAway();
        inHand(rig, "goat_horn");
        rig.client().enable("Honker");
        rig.ticks(SETTLE_TICKS);
        duo.server().resetTallies();
        duo.partnerHome();
        Props.await(rig, "the horn to be blown", ANSWER_TICKS, () -> count(duo, "interacts") >= 1);
    }

    // The game draws bars for the ping. Numbers in the list are the module's.
    private static void betterTab(Rig rig, Duo duo) {
        rig.client().disable("BetterTab");
        boolean bare = numbersInList(rig, "player list bare");
        rig.client().enable("BetterTab");
        boolean numbered = numbersInList(rig, "player list numbered");
        rig.expect(!bare, "A number stood on the screen without BetterTab. That proves nothing");
        rig.expect(numbered, "No ping is written into the player list");
    }

    private static boolean numbersInList(Rig rig, String shot) {
        rig.controls().hold(TAB);
        rig.ticks(SEEN_TICKS);
        boolean numbers = rig.frames().capture(shot).texts().stream().anyMatch(text -> NUMBER.matcher(text).matches());
        rig.controls().release(TAB);
        return numbers;
    }

    private static void logoutSpots(Rig rig, Duo duo) {
        rig.client().enable("LogoutSpots");
        rig.controls().look(EAST, 0);
        rig.ticks(SETTLE_TICKS);
        duo.partner().ask("leave");
        rig.ticks(SETTLE_TICKS);
        boolean marked;
        try {
            marked = rig.frames().capture("logout spot").texts().stream().anyMatch(text -> text.contains(Partner.NAME));
        } finally {
            duo.partner().ask("join", PaperServer.address());
        }
        rig.expect(marked, "Nothing on the screen names the player who left");
    }

    private static void linger(Rig rig, Duo duo) {
        String me = rig.world().player();
        rig.client().enable("Linger");
        duo.server().console("kick " + me + " rig");
        rig.ticks(SETTLE_TICKS * 2);
        boolean gone = "false".equals(duo.server().player(me).get("online"));
        boolean held = rig.ask(mc -> mc.level != null && mc.player != null
            && !(mc.gui.screen() instanceof DisconnectedScreen));
        rig.client().disable("Linger");
        rig.ticks(SETTLE_TICKS);
        rig.expect(gone, "The kick did not land. That proves nothing");
        rig.expect(held, "The world was not kept once the connection dropped");
    }

    private static void autoReconnect(Rig rig, Duo duo) {
        String me = rig.world().player();
        rig.client().enable("AutoReconnect");
        duo.server().console("kick " + me + " rig");
        rig.ticks(SETTLE_TICKS);
        rig.expect("false".equals(duo.server().player(me).get("online")), "The kick did not land. That proves nothing");
        int took = Props.await(rig, "the player to be back on the server", BACK_TICKS,
            () -> "true".equals(duo.server().player(me).get("online")));
        rig.until("the player to stand in the world again", BACK_TICKS,
            mc -> mc.player != null && mc.level != null && mc.gui.screen() == null);
        rig.client().disable("AutoReconnect");
        rig.note("It was back about " + took + " ticks after the kick");
    }

    private static void autoLog(Rig rig, Duo duo) {
        String me = rig.world().player();
        rig.client().enable("AutoLog");
        duo.server().console("damage " + me + " 15");
        try {
            Props.await(rig, "the player to leave the server", ANSWER_TICKS,
                () -> "false".equals(duo.server().player(me).get("online")));
        } finally {
            rig.client().disable("AutoLog");
        }
    }

    private static void chatAlerts(Rig rig, Duo duo) {
        rig.client().enable("ChatAlerts");
        boolean quiet = !toasted(rig, duo, "nothing here");
        boolean rang = toasted(rig, duo, "hey " + rig.world().player());
        rig.expect(quiet, "A line that names nobody raised a toast. That proves nothing");
        rig.expect(rang, "No toast came up when another player wrote your name");
    }

    // Has the second player say the words and wipes the chat. A toast is then all that still
    // holds them.
    private static boolean toasted(Rig rig, Duo duo, String words) {
        duo.partner().ask("chat", words);
        rig.ticks(SETTLE_TICKS);
        rig.act(mc -> mc.gui.hud.getChat().clearMessages(true));
        rig.ticks(2);
        return rig.frames().capture("toast " + words).shows(words);
    }
}
