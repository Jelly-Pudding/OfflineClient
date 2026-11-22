package com.jellypudding.offlineclient.rig.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The world a case stands in seen from its server. The server is the one inside the game or one
// that runs beside it and a case written against this class plays on either.
public abstract class World {

    // Ticks the client gets to hear what the server changed.
    protected static final int SYNC_TICKS = 3;

    protected final Rig rig;
    private final String player;
    private boolean unheard;

    protected World(Rig rig, String player) {
        this.rig = rig;
        this.player = player;
    }

    public String player() {
        return player;
    }

    // Below nought when the command did not succeed. What the server said goes into the list.
    protected abstract int perform(String command, List<String> said);

    // Returns once the client has taken everything the server has sent.
    protected abstract void catchUp();

    // The server sends what it changed a tick later and the client takes it later still. A case
    // that went straight on would look at a world the client has not seen yet.
    protected void told() {
        unheard = true;
    }

    void hear() {
        if (!unheard) {
            return;
        }
        unheard = false;
        if (rig.ask(mc -> mc.player != null)) {
            catchUp();
        }
    }

    // For a case that changed the server by another road than a command of this class.
    public final void sync() {
        quietly(List.of());
        hear();
    }

    protected abstract void close();

    // What the server holds about the test player this tick. Both kinds of server answer with
    // the same words such as x and health and sneaking.
    public abstract Map<String, String> facts();

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

    public boolean holds(String fact) {
        return "true".equals(facts().get(fact));
    }

    // The id of the block the server holds there with its namespace in front.
    public abstract String blockAt(BlockPos pos);

    // Leaves the test player this many food points and nothing saved up behind them. The hunger
    // effect would take seconds to get there.
    public abstract void foodLevel(int points);

    public boolean isAir(BlockPos pos) {
        return blockAt(pos).endsWith(":air");
    }

    // Runs a server command with full rights and hands back its result number. A command the
    // server refuses fails the case with what the server said.
    public int command(String command) {
        List<String> said = new ArrayList<>();
        int result = tell(command, said);
        if (result < 0) {
            throw new RigFailure("The server refused the command " + command + " and said " + said);
        }
        return result;
    }

    // The same for a command that may find nothing to do. False when the server refused it.
    public boolean attempt(String command) {
        return tell(command, new ArrayList<>()) >= 0;
    }

    // The number a command hands back such as how many entities it found. Nought when it found none.
    public int count(String command) {
        return Math.max(0, tell(command, new ArrayList<>()));
    }

    private int tell(String command, List<String> said) {
        told();
        return perform(command, said);
    }

    // Commands whose outcome nobody asks about such as the tidying between two cases.
    public final void quietly(List<String> commands) {
        told();
        performAll(commands);
    }

    public final void quietly(String... commands) {
        quietly(List.of(commands));
    }

    protected void performAll(List<String> commands) {
        for (String command : commands) {
            perform(command, new ArrayList<>());
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

    public static String at(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    // Puts the player there and waits until the client has taken the move. A rocket or anything
    // else that still pushes the player carries it on from there.
    public void teleport(double x, double y, double z, float yaw, float pitch) {
        command(String.format(Locale.ROOT, "tp %s %.3f %.3f %.3f %.2f %.2f", player, x, y, z, yaw, pitch));
        hear();
        rig.ticks(SYNC_TICKS);
    }

    public void gameMode(String mode) {
        commands("gamemode " + mode + " " + player);
    }

    public void clearInventory() {
        commands("clear " + player);
    }

    public void hotbar(int slot, String item) {
        command("item replace entity " + player + " hotbar." + slot + " with " + item);
    }
}
