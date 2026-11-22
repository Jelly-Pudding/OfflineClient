package com.jellypudding.offlineclient.rig.duo;

import com.jellypudding.offlineclient.rig.RigConfig;
import com.jellypudding.offlineclient.rig.core.Rig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

// What the game of the second player runs in place of the suites. It takes one order at a time
// from the first game and answers each with one line. Between orders its game ticks on.
public final class PartnerLoop {

    private static final int SWING_STEPS = 1000;
    private static final String TEXT_SPLIT = " / ";

    private final Rig rig;

    public PartnerLoop(Rig rig) {
        this.rig = rig;
    }

    public void run() {
        try (Socket line = new Socket(InetAddress.getLoopbackAddress(), RigConfig.link())) {
            PrintWriter answers = new PrintWriter(line.getOutputStream(), true, StandardCharsets.UTF_8);
            BlockingQueue<String> orders = new LinkedBlockingQueue<>();
            Thread reader = new Thread(() -> read(line, orders), "Rig partner orders");
            reader.setDaemon(true);
            reader.start();
            while (true) {
                String order = orders.poll();
                if (order == null) {
                    comeBackToLife();
                    rig.ticks(1);
                } else if (order.equals(Partner.QUIT)) {
                    break;
                } else {
                    answers.println(answer(order).replace('\n', ' ').replace('\r', ' '));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The line to the first game could not be opened", e);
        } finally {
            rig.closeWorld();
        }
    }

    // A second player that was killed walks on in a new life. No case waits at a death screen.
    private void comeBackToLife() {
        rig.act(mc -> {
            if (mc.player != null && mc.player.isDeadOrDying()) {
                mc.player.respawn();
                mc.gui.setScreen(null);
            }
        });
    }

    // The first game going away ends the line and that counts as being told to stop.
    private static void read(Socket line, BlockingQueue<String> orders) {
        try (BufferedReader heard = new BufferedReader(new InputStreamReader(line.getInputStream(),
            StandardCharsets.UTF_8))) {
            for (String order = heard.readLine(); order != null; order = heard.readLine()) {
                orders.add(order);
            }
        } catch (IOException e) {
            // A broken line is the end of it as well.
        }
        orders.add(Partner.QUIT);
    }

    private String answer(String order) {
        try {
            return Partner.OK + Partner.SPLIT + obey(order.split(Partner.SPLIT, -1));
        } catch (Throwable thrown) {
            return Partner.FAILED + Partner.SPLIT + Rig.describe(thrown);
        }
    }

    private String obey(String[] words) {
        switch (words[0]) {
            case "join" -> rig.enter(PaperWorld.join(rig, null, words[1]));
            case "leave" -> rig.closeWorld();
            case "ticks" -> rig.ticks(Integer.parseInt(words[1]));
            case "enable" -> rig.client().enable(words[1]);
            case "disable" -> rig.client().disable(words[1]);
            case "set" -> rig.client().set(words[1], words[2], words[3]);
            case "client" -> {
                return String.join(" / ", rig.client().command(words[1]));
            }
            case "chat" -> rig.client().say(words[1]);
            case "defaults" -> rig.client().restoreDefaults();
            case "texts" -> {
                return String.join(TEXT_SPLIT, rig.frames().capture(words[1]).texts());
            }
            case "look" -> rig.controls().look(Float.parseFloat(words[1]), Float.parseFloat(words[2]));
            case "aim" -> rig.controls().aimAt(rig.ask(mc -> named(mc, words[1]).getBoundingBox().getCenter()));
            case "click" -> rig.controls().click(Integer.parseInt(words[1]));
            case "hold" -> rig.controls().hold(Integer.parseInt(words[1]));
            case "release" -> rig.controls().release(Integer.parseInt(words[1]));
            case "holdMouse" -> rig.controls().holdMouse(Integer.parseInt(words[1]));
            case "releaseMouse" -> rig.controls().releaseMouse(Integer.parseInt(words[1]));
            case "slot" -> rig.act(mc -> mc.player.getInventory().setSelectedSlot(Integer.parseInt(words[1])));
            case "see" -> {
                return rig.ask(mc -> seen(mc, words[1]));
            }
            case "watch" -> {
                return watch(words[1], Integer.parseInt(words[2]));
            }
            case "self" -> {
                return rig.ask(mc -> seen(mc, mc.player.getName().getString()));
            }
            case "shot" -> {
                return rig.frames().shot(words[1]).toString();
            }
            default -> throw new IllegalArgumentException("The second player knows no order called " + words[0]);
        }
        return "";
    }

    private static Player named(Minecraft mc, String name) {
        for (Player each : mc.level.players()) {
            if (each.getName().getString().equals(name)) {
                return each;
            }
        }
        throw new IllegalStateException("The second player sees nobody called " + name);
    }

    private static String seen(Minecraft mc, String name) {
        Player who = named(mc, name);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("x", number(who.getX()));
        facts.put("y", number(who.getY()));
        facts.put("z", number(who.getZ()));
        facts.put("yaw", number(who.getYRot()));
        facts.put("pitch", number(who.getXRot()));
        facts.put("pose", who.getPose());
        facts.put("crouching", who.isCrouching());
        facts.put("sprinting", who.isSprinting());
        facts.put("swinging", who.isSwinging());
        facts.put("gliding", who.isFallFlying());
        facts.put("hurt", who.hurtTime);
        facts.put("health", number(who.getHealth()));
        facts.put("ground", who.onGround());
        facts.put("glowing", who.isCurrentlyGlowing());
        facts.put("invisible", who.isInvisible());
        facts.put("hand", who.getMainHandItem().getItem());
        facts.put("far", number(who.distanceTo(mc.player)));
        return pairs(facts);
    }

    private String watch(String name, int ticks) {
        int swings = 0;
        int crouches = 0;
        int crouchTicks = 0;
        int hurts = 0;
        double moved = 0;
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        boolean crouched = rig.ask(mc -> named(mc, name).isCrouching());
        int swingTime = Integer.MAX_VALUE;
        int hurtTime = 0;
        Vec3 last = rig.ask(mc -> named(mc, name).position());
        for (int tick = 0; tick < ticks; tick++) {
            rig.ticks(1);
            int[] state = rig.ask(mc -> {
                Player who = named(mc, name);
                return new int[] {who.isCrouching() ? 1 : 0,
                    who.isSwinging() ? Math.round(who.getSwingAnimation(1) * SWING_STEPS) : Integer.MAX_VALUE,
                    who.hurtTime};
            });
            Vec3 now = rig.ask(mc -> named(mc, name).position());
            if (state[0] == 1) {
                crouchTicks++;
                crouches += crouched ? 0 : 1;
            }
            crouched = state[0] == 1;
            // An arm that starts again counts from nought.
            if (state[1] < swingTime) {
                swings++;
            }
            swingTime = state[1];
            if (state[2] > hurtTime) {
                hurts++;
            }
            hurtTime = state[2];
            moved += now.subtract(last).horizontalDistance();
            lowest = Math.min(lowest, now.y);
            highest = Math.max(highest, now.y);
            last = now;
        }
        Map<String, Object> counted = new LinkedHashMap<>();
        counted.put("swings", swings);
        counted.put("crouches", crouches);
        counted.put("crouchTicks", crouchTicks);
        counted.put("hurts", hurts);
        counted.put("moved", number(moved));
        counted.put("lowest", number(lowest));
        counted.put("highest", number(highest));
        return pairs(counted);
    }

    private static String pairs(Map<String, Object> values) {
        StringJoiner joined = new StringJoiner(" ");
        values.forEach((key, value) -> joined.add(key + "=" + String.valueOf(value).replace(' ', '_')));
        return joined.toString();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
