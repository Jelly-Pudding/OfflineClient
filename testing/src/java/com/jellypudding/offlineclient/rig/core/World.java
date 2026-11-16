package com.jellypudding.offlineclient.rig.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The world a case stands in seen from its server. A case builds its stage with server commands
// and reads here what really happened to the player. The server is the one inside the game or
// one that runs beside it and a case written against this class plays on either.
public abstract class World {

    // Ticks the client gets to hear what the server changed.
    protected static final int SYNC_TICKS = 3;
    private static final int ARRIVE_TICKS = 60;
    private static final double ARRIVED = 0.3;

    protected final Rig rig;
    private final String player;

    protected World(Rig rig, String player) {
        this.rig = rig;
        this.player = player;
    }

    // The name the server knows the test player by.
    public String player() {
        return player;
    }

    // Below nought when the command did not succeed. What the server said goes into the list.
    protected abstract int perform(String command, List<String> said);

    protected abstract void close();

    // What the server holds about the test player this tick. Both kinds of server answer with
    // the same words such as x and health and sneaking.
    public abstract Map<String, String> facts();

    // Where the server holds the test player.
    public Vec3 where() {
        Map<String, String> held = facts();
        if (!held.containsKey("x")) {
            throw new RigFailure("The server does not hold the player " + player);
        }
        return new Vec3(Double.parseDouble(held.get("x")), Double.parseDouble(held.get("y")),
            Double.parseDouble(held.get("z")));
    }

    public float health() {
        return Float.parseFloat(facts().get("health"));
    }

    // True when the server holds the fact as true such as sneaking or flying.
    public boolean holds(String fact) {
        return "true".equals(facts().get(fact));
    }

    // The id of the block the server holds there with its namespace in front.
    public abstract String blockAt(BlockPos pos);

    public boolean isAir(BlockPos pos) {
        return blockAt(pos).endsWith(":air");
    }

    // Runs a server command with full rights and hands back its result number. A command the
    // server refuses fails the case with what the server said.
    public int command(String command) {
        List<String> said = new ArrayList<>();
        int result = perform(command, said);
        if (result < 0) {
            throw new RigFailure("The server refused the command " + command + " and said " + said);
        }
        return result;
    }

    // The same for a command that may find nothing to do. False when the server refused it.
    public boolean attempt(String command) {
        return perform(command, new ArrayList<>()) >= 0;
    }

    // The number a command hands back such as how many entities it found. Nought when it found none.
    public int count(String command) {
        return Math.max(0, perform(command, new ArrayList<>()));
    }

    // Commands whose outcome nobody asks about such as the tidying between two cases. A server
    // that runs beside the game takes them all in one go.
    public void quietly(List<String> commands) {
        for (String command : commands) {
            attempt(command);
        }
    }

    // Several commands and then the ticks the client needs to see what they did.
    public void commands(String... commands) {
        for (String command : commands) {
            command(command);
        }
        rig.ticks(SYNC_TICKS);
    }

    public void setBlock(BlockPos pos, String block) {
        command("setblock " + at(pos) + " " + block);
    }

    public void fill(BlockPos from, BlockPos to, String block) {
        command("fill " + at(from) + " " + at(to) + " " + block);
    }

    // The same for blocks that may be there already. The server calls a fill that changes nothing a failure.
    public void refill(BlockPos from, BlockPos to, String block) {
        attempt("fill " + at(from) + " " + at(to) + " " + block);
    }

    // The three numbers of a spot the way a command takes them.
    public static String at(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    // Puts the player there and waits until the client stands there too.
    public void teleport(double x, double y, double z, float yaw, float pitch) {
        command(String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f %.2f %.2f", player, x, y, z, yaw, pitch));
        Vec3 spot = new Vec3(x, y, z);
        rig.until("the player to arrive at " + spot, ARRIVE_TICKS,
            mc -> mc.player != null && mc.player.position().distanceTo(spot) < ARRIVED);
        rig.ticks(SYNC_TICKS);
    }

    public void gameMode(String mode) {
        commands("gamemode " + mode + " " + player);
    }

    public void clearInventory() {
        commands("clear " + player);
    }

    // Puts an item in one slot of the hotbar counted from nought.
    public void hotbar(int slot, String item) {
        command("item replace entity " + player + " hotbar." + slot + " with " + item);
    }
}
