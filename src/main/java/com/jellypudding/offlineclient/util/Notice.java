package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Webhook;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

// How a module tells the player about a find. After a message one kind of find keeps
// quiet for a while and the next message of that kind counts what turned up in between.
// The module stores every find whether or not its message was held back. Every message
// that goes out also goes to Webhook.
public final class Notice {

    public enum Where { OFF, CHAT, TOAST, BOTH }

    private final String owner;
    private final EnumSetting<Where> where;
    private final BoolSetting coordinates;
    private final NumberSetting quiet;
    private String verb = "found";

    // Kinds of find still inside their quiet time on the player's clock.
    private final Cooldowns<String> quieted = new Cooldowns<>();
    // How many finds of each kind were held back since its last message.
    private final Map<String, Integer> heldBack = new HashMap<>();
    private final ServerWatch server = new ServerWatch();

    public Notice(Module owner, Where defaultWhere) {
        this(owner, defaultWhere, 0);
    }

    // A module whose finds come in bursts starts with a quiet time in seconds.
    public Notice(Module owner, Where defaultWhere, int quietSeconds) {
        this.owner = owner.getName();
        where = row("Where each find is announced.", defaultWhere);
        coordinates = new BoolSetting("Coordinates", "Adds the coordinates to each message.", true)
            .under(where, () -> !where.is(Where.OFF));
        quiet = new NumberSetting("Quiet time",
            "How long one kind of find waits after a message before it can post another. "
                + "The finds in between are counted in the next message.",
            quietSeconds, 0, 60, 1, " seconds")
            .under(where, () -> !where.is(Where.OFF));
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {where, coordinates, quiet};
    }

    // The Messages row on its own for a module that words its own messages.
    public static EnumSetting<Where> row(String description, Where defaultWhere) {
        return new EnumSetting<>("Messages", description, defaultWhere)
            .describe(Where.OFF, "No messages.")
            .describe(Where.CHAT, "A line in chat.")
            .describe(Where.TOAST, "A toast in the top right corner.")
            .describe(Where.BOTH, "A chat line and a toast.");
    }

    // Posts a module's own message where its row says. The words follow the module name in
    // chat and make the body of a toast.
    public static void post(EnumSetting<Where> where, Module owner, Component words) {
        if (show(where, owner, words)) {
            Modules.alert(Webhook.Alert.FIND, owner.getName() + " " + words.getString());
        }
    }

    // The same for news that is no find. Webhook is left out. False when nothing showed.
    public static boolean show(EnumSetting<Where> where, Module owner, Component words) {
        if (where.is(Where.OFF) || !where.isVisible() || OfflineClient.MC.player == null) {
            return false;
        }
        if (!where.is(Where.TOAST)) {
            ChatUtil.component(Component.literal("§b" + owner.getName() + " ").append(words));
        }
        if (!where.is(Where.CHAT)) {
            toast(owner.getName(), ChatFormatting.stripFormatting(words.getString()));
        }
        return true;
    }

    // The word between the module name and the find in chat such as heard for a sound.
    public Notice verb(String verb) {
        this.verb = verb;
        return this;
    }

    // Makes the rows a sub option of a switch. Nothing is announced whilst that switch is off.
    public Notice under(BoolSetting parent) {
        where.under(parent);
        return this;
    }

    // Shows the rows only whilst the supplier answers true. Nothing is announced otherwise.
    public Notice visibleWhen(Supplier<Boolean> visibility) {
        where.visibleWhen(visibility);
        return this;
    }

    // Tells the player about a find unless its kind is still quiet. The kind is a short
    // name the module picks and the words read after the verb such as a zombie spawner.
    public void tell(String kind, String what, BlockPos pos) {
        tell(kind, what, pos, ServerInfo.dimension());
    }

    // The same for a find that may lie in another dimension. There its name takes the place
    // of the distance and the coordinates cannot be walked to.
    public void tell(String kind, String what, BlockPos pos, String dimension) {
        if (where.is(Where.OFF) || !where.isVisible() || OfflineClient.MC.player == null) {
            return;
        }
        // What was held back on the last server is no news here.
        if (server.changed()) {
            quieted.clear();
            heldBack.clear();
        }
        quieted.tick();
        if (quieted.contains(kind)) {
            heldBack.merge(kind, 1, Integer::sum);
            return;
        }
        Integer more = heldBack.remove(kind);
        if (quiet.getInt() > 0) {
            quieted.put(kind, quiet.getInt() * SharedConstants.TICKS_PER_SECOND);
        }
        int count = more == null ? 0 : more;
        boolean here = dimension.equals(ServerInfo.dimension());
        String away = here ? away(OfflineClient.MC.player.position(), pos)
            : "in the " + ServerInfo.dimensionName(dimension);
        if (!where.is(Where.TOAST)) {
            chat(what, away, pos, dimension, count);
        }
        if (!where.is(Where.CHAT)) {
            toast(what, away, pos, count);
        }
        Modules.alert(Webhook.Alert.FIND, owner + " " + verb + " " + what + " " + away + " at " + BlockUtil.text(pos)
            + (count > 0 ? " and " + count + " more" : ""));
    }

    // How far and which way a spot lies from a point such as 340 blocks north east.
    public static String away(Vec3 from, BlockPos pos) {
        Vec3 to = Vec3.atCenterOf(pos);
        return away(from.distanceTo(to), from, to);
    }

    // The same with the distance given. A find that stands for a chunk is measured across
    // the ground.
    public static String away(double distance, Vec3 from, Vec3 to) {
        return Math.round(distance) + " blocks " + Bearing.between(from, to).compass();
    }

    // How far and which way the find lies. With coordinates on they follow.
    private void chat(String what, String away, BlockPos pos, String dimension, int more) {
        MutableComponent line = Component.literal(ChatUtil.from(owner, verb + " §f" + what + "§7 " + away));
        if (coordinates.isOn()) {
            line.append("§7 at ").append(ChatUtil.walkLink(pos, dimension));
        }
        line.append(more > 0 ? "§7. §f" + more + "§7 more turned up since the last message." : "§7.");
        ChatUtil.component(line);
    }

    private void toast(String what, String away, BlockPos pos, int more) {
        toast(owner, what + " " + away + (coordinates.isOn() ? " at " + BlockUtil.text(pos) : "")
            + (more > 0 ? " and " + more + " more" : ""));
    }

    // NameProtect matches names case for case and runs before the capital goes on.
    private static void toast(String title, String body) {
        String shown = EntityUtil.displayed(Component.literal(body)).getString();
        SystemToast.add(OfflineClient.MC.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            Component.literal(title), Component.literal(ChatUtil.capitalised(shown)));
    }
}
