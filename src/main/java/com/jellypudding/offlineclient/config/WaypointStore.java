package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Dimension;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.TextCut;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

// Named coordinates saved to offlineclient/waypoints.json.
// Each one remembers the server and the dimension it was marked in.
public final class WaypointStore {

    // The shape drawn on a waypoint on screen.
    public enum Icon { NONE, SQUARE, CIRCLE, DIAMOND, TRIANGLE, CROSS, STAR }

    // What a waypoint does once you come near it.
    public enum Action { NOTHING, HIDE, DELETE }

    // What a waypoint does within a distance of you. Doing nothing has no distance.
    public record Near(Action action, double distance) {

        public static final Near NOTHING = new Near(Action.NOTHING, 0);
        // The distance a rule starts with until the player gives one.
        public static final double USUAL_DISTANCE = 8;
        // Standing in the marked block puts your feet half a block from its middle. A shorter rule
        // could miss you even there.
        public static final double SHORTEST_DISTANCE = 1;

        public static Near of(Action action, double distance) {
            return action == Action.NOTHING ? NOTHING : new Near(action, Math.max(SHORTEST_DISTANCE, distance));
        }

        public boolean acts() {
            return action != Action.NOTHING;
        }
    }

    // A hue below zero takes the colour from the name. A rainbow turns by itself.
    public record Colour(int hue, float saturation, float brightness, boolean rainbow) {

        public static final float USUAL_SATURATION = 0.7f;
        public static final float USUAL_BRIGHTNESS = 1f;
        public static final int FULL_CIRCLE = 360;
        private static final int NAME_HUE = -1;

        public static final Colour AUTO = new Colour(NAME_HUE, USUAL_SATURATION, USUAL_BRIGHTNESS, false);

        // The usual shade of a hue. Any hue below zero leaves the colour to the name.
        public static Colour ofHue(int hue) {
            return hue < 0 ? AUTO : new Colour(Math.floorMod(hue, FULL_CIRCLE), USUAL_SATURATION,
                USUAL_BRIGHTNESS, false);
        }

        // The colour a colour row shows with the hue to a whole degree.
        public static Colour of(ColorSetting row) {
            return new Colour(Math.floorMod(Math.round(row.getHue()), FULL_CIRCLE), row.getSaturation(),
                row.getBrightness(), row.isRainbow());
        }

        public boolean fromName() {
            return hue < 0 && !rainbow;
        }

        // The same shade at another hue. A rainbow stops turning.
        public Colour withHue(int hue) {
            return new Colour(Math.floorMod(hue, FULL_CIRCLE), saturation, brightness, false);
        }

        // The same hue in another shade. Saturation and brightness run from nought to one.
        public Colour withShade(float saturation, float brightness) {
            return new Colour(hue, Math.clamp(saturation, 0f, 1f), Math.clamp(brightness, 0f, 1f), rainbow);
        }

        // The same shade turning through every hue. The hue it shows now is kept for when it stops.
        public Colour turning(String name) {
            return new Colour(hueFor(name), saturation, brightness, true);
        }

        // The hue the colour has for a waypoint of that name. The same name always gets the same one.
        public int hueFor(String name) {
            return hue >= 0 ? hue : Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), FULL_CIRCLE);
        }

        public int argb(String name) {
            return rainbow ? ColorUtil.rainbow(0) : ColorUtil.hsv(hueFor(name), saturation, brightness);
        }
    }

    // What one waypoint has of its own. A null follows the Waypoints module.
    public record Options(Icon icon, Double scale, Double range, Boolean mirrored, Near near) {

        public static final Options NONE = new Options(null, null, null, null, null);
        // A label smaller than this cannot be read.
        public static final double SMALLEST_SCALE = 0.1;

        // These options with every gap filled from the others.
        public Options over(Options base) {
            return new Options(icon != null ? icon : base.icon, scale != null ? scale : base.scale,
                range != null ? range : base.range, mirrored != null ? mirrored : base.mirrored,
                near != null ? near : base.near);
        }

        // These options once rows that showed one set of values show another. A value whose row was left
        // alone stays as it is. A moved row gives its value and one moved onto what the base gives follows
        // the base again.
        public Options edited(Options shown, Options rows, Options base) {
            return new Options(edited(icon, shown.icon, rows.icon, base.icon),
                edited(scale, shown.scale, rows.scale, base.scale),
                edited(range, shown.range, rows.range, base.range),
                edited(mirrored, shown.mirrored, rows.mirrored, base.mirrored),
                edited(near, shown.near, rows.near, base.near));
        }

        private static <T> T edited(T own, T shown, T row, T base) {
            if (Objects.equals(row, shown)) {
                return own;
            }
            return Objects.equals(row, base) ? null : row;
        }

        public Options withIcon(Icon icon) {
            return new Options(icon, scale, range, mirrored, near);
        }

        public Options withScale(Double scale) {
            return new Options(icon, scale, range, mirrored, near);
        }

        public Options withRange(Double range) {
            return new Options(icon, scale, range, mirrored, near);
        }

        public Options withMirrored(Boolean mirrored) {
            return new Options(icon, scale, range, mirrored, near);
        }

        public Options withNear(Near near) {
            return new Options(icon, scale, range, mirrored, near);
        }
    }

    // A found waypoint is a place a module learnt for you and not one you marked yourself.
    public record Waypoint(String name, int x, int y, int z, String dimension, String server,
                           Colour colour, boolean hidden, boolean found, Options options) {

        public Waypoint withName(String name) {
            return new Waypoint(name, x, y, z, dimension, server, colour, hidden, found, options);
        }

        public Waypoint at(BlockPos pos) {
            return new Waypoint(name, pos.getX(), pos.getY(), pos.getZ(), dimension, server, colour, hidden,
                found, options);
        }

        public Waypoint in(String dimension) {
            return new Waypoint(name, x, y, z, dimension, server, colour, hidden, found, options);
        }

        public Waypoint withColour(Colour colour) {
            return new Waypoint(name, x, y, z, dimension, server, colour, hidden, found, options);
        }

        public Waypoint withHidden(boolean hidden) {
            return new Waypoint(name, x, y, z, dimension, server, colour, hidden, found, options);
        }

        public Waypoint withOptions(Options options) {
            return new Waypoint(name, x, y, z, dimension, server, colour, hidden, found, options);
        }

        public Waypoint withFound(boolean found) {
            return new Waypoint(name, x, y, z, dimension, server, colour, hidden, found, options);
        }

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        // The middle of the block it marks.
        public Vec3 centre() {
            return Vec3.atCenterOf(pos());
        }

        public int argb() {
            return colour.argb(name);
        }
    }

    // A name longer than this is cut. A label has to fit the screen. A stem leaves room for
    // the number after it.
    public static final int LONGEST_NAME = 64;
    private static final int LONGEST_STEM = LONGEST_NAME - 8;
    // Longer than any real server address or world folder or dimension id. A saved entry with a
    // longer one came from a broken file.
    private static final int LONGEST_PLACE = 512;

    private static WaypointStore instance;

    private final Path file;
    private final List<Waypoint> waypoints = new ArrayList<>();

    private WaypointStore(Path file) {
        this.file = file;
        load();
    }

    public static synchronized WaypointStore get() {
        if (instance == null) {
            instance = new WaypointStore(DataFiles.path("waypoints.json"));
        }
        return instance;
    }

    // A name as it is saved. It is one word of at most the longest length. Null when nothing
    // is left of the text.
    public static String cleanName(String text) {
        String name = text.replaceAll("\\s+", "");
        if (name.isEmpty()) {
            return null;
        }
        return TextCut.cut(name, LONGEST_NAME);
    }

    // Only the waypoints marked in the world the player is standing in.
    public List<Waypoint> here() {
        return in(ServerInfo.dimension());
    }

    // The waypoints of one dimension of this server.
    public List<Waypoint> in(String dimension) {
        String server = ServerInfo.key();
        List<Waypoint> matching = new ArrayList<>();
        for (Waypoint waypoint : waypoints) {
            if (waypoint.dimension().equals(dimension) && ServerInfo.matches(waypoint.server(), server)) {
                matching.add(waypoint);
            }
        }
        return matching;
    }

    // Every waypoint of this server whichever dimension it is in.
    public List<Waypoint> onServer() {
        String server = ServerInfo.key();
        List<Waypoint> matching = new ArrayList<>();
        for (Waypoint waypoint : waypoints) {
            if (ServerInfo.matches(waypoint.server(), server)) {
                matching.add(waypoint);
            }
        }
        return matching;
    }

    // Every dimension of this server that holds a waypoint.
    public List<String> dimensions() {
        Set<String> found = new LinkedHashSet<>();
        for (Waypoint waypoint : onServer()) {
            found.add(waypoint.dimension());
        }
        return new ArrayList<>(found);
    }

    // Waypoints from the other side of a nether portal with their coordinates scaled
    // to this side. Empty anywhere but the overworld and the nether.
    public List<Waypoint> mirrored() {
        String dimension = ServerInfo.dimension();
        String other = Dimension.otherSide(dimension);
        List<Waypoint> matching = new ArrayList<>();
        if (other == null) {
            return matching;
        }
        for (Waypoint waypoint : in(other)) {
            matching.add(waypoint.at(Dimension.acrossPortal(Vec3.atLowerCornerOf(waypoint.pos()), other))
                .in(dimension));
        }
        return matching;
    }

    // An existing waypoint of the same name in the new one's world is replaced. The new one
    // goes to the end of the list.
    private void add(Waypoint waypoint) {
        waypoints.removeIf(existing -> named(existing, waypoint.name(), waypoint.dimension(), waypoint.server()));
        waypoints.add(waypoint);
        save();
    }

    // Puts a changed waypoint in the place of the one it came from. False when that one has
    // gone or another waypoint already has the new name in a world where both would show.
    public boolean replace(Waypoint from, Waypoint changed) {
        int at = indexOf(from);
        if (at < 0 || !alike(waypoints.get(at), changed) && taken(changed, at)) {
            return false;
        }
        waypoints.set(at, changed);
        save();
        return true;
    }

    // Whether this waypoint is still saved. One a module moved meanwhile still counts.
    public boolean holds(Waypoint waypoint) {
        return indexOf(waypoint) >= 0;
    }

    // Where a waypoint is held. One a module moved meanwhile is found by its name and where it is
    // filed. Minus one once it has gone.
    private int indexOf(Waypoint waypoint) {
        int at = waypoints.indexOf(waypoint);
        if (at >= 0) {
            return at;
        }
        for (int i = 0; i < waypoints.size(); i++) {
            if (alike(waypoints.get(i), waypoint)) {
                return i;
            }
        }
        return -1;
    }

    // Whether a waypoint other than the one at that place has this name in a world where both
    // would show.
    private boolean taken(Waypoint waypoint, int except) {
        for (int i = 0; i < waypoints.size(); i++) {
            if (i != except && clash(waypoints.get(i), waypoint)) {
                return true;
            }
        }
        return false;
    }

    // The same name in the same dimension filed under the same server. A module that moves a
    // waypoint keeps all three.
    private static boolean alike(Waypoint one, Waypoint other) {
        return one.server().equals(other.server()) && named(one, other.name(), other.dimension(), other.server());
    }

    // Two waypoints of one name and dimension where one shows in the world the other is filed
    // under. A plain singleplayer one shows in every single player world.
    private static boolean clash(Waypoint one, Waypoint other) {
        return named(one, other.name(), other.dimension(), other.server())
            || named(other, one.name(), one.dimension(), one.server());
    }

    // Saves a waypoint in that dimension of the world you play in. One of the same name that shows
    // there moves to the spot under the name as given and is filed under this world. It keeps its
    // colour and options and whether it is hidden. The colour is for a new name.
    public void mark(String name, BlockPos pos, String dimension, Colour colour) {
        mark(name, pos, dimension, colour, false);
    }

    // The same for a place a module learnt for you.
    public void markFind(String name, BlockPos pos, String dimension, Colour colour) {
        mark(name, pos, dimension, colour, true);
    }

    // A name with nothing left of it once cleaned saves nothing. A find you mark yourself becomes
    // your own and stays one whatever a module marks there later.
    private void mark(String name, BlockPos pos, String dimension, Colour colour, boolean found) {
        String clean = cleanName(name);
        if (clean == null) {
            return;
        }
        String server = ServerInfo.key();
        Waypoint saved = lookup(clean, dimension, server);
        add(saved == null
            ? new Waypoint(clean, pos.getX(), pos.getY(), pos.getZ(), dimension, server, colour, false, found,
                Options.NONE)
            : new Waypoint(clean, pos.getX(), pos.getY(), pos.getZ(), dimension, server, saved.colour(),
                saved.hidden(), found && saved.found(), saved.options()));
    }

    // The same in the dimension you are in.
    public void mark(String name, BlockPos pos, Colour colour) {
        mark(name, pos, ServerInfo.dimension(), colour);
    }

    // Saves a new waypoint in that dimension of this server named after the stem with the first
    // number no waypoint there uses such as stash3. Returns the name it took.
    public String markFresh(String stem, BlockPos pos, String dimension, Colour colour) {
        String name = freshName(stem, dimension);
        mark(name, pos, dimension, colour, false);
        return name;
    }

    // The same for a place a module learnt for you.
    public String markFreshFind(String stem, BlockPos pos, String dimension, Colour colour) {
        String name = freshName(stem, dimension);
        mark(name, pos, dimension, colour, true);
        return name;
    }

    private String freshName(String stem, String dimension) {
        String server = ServerInfo.key();
        String clean = cleanName(stem);
        String start = clean == null ? "" : TextCut.cut(clean, LONGEST_STEM);
        int number = 1;
        while (lookup(start + number, dimension, server) != null) {
            number++;
        }
        return start + number;
    }

    // Null when no waypoint of that name is in this world.
    public Waypoint find(String name) {
        return find(name, ServerInfo.dimension());
    }

    // Null when that dimension of this server holds no waypoint of that name.
    public Waypoint find(String name, String dimension) {
        return lookup(name, dimension, ServerInfo.key());
    }

    // Null when that dimension of that server holds no waypoint of that name.
    private Waypoint lookup(String name, String dimension, String server) {
        for (Waypoint waypoint : waypoints) {
            if (named(waypoint, name, dimension, server)) {
                return waypoint;
            }
        }
        return null;
    }

    // Removes the waypoint of that name in this world. False when there is none.
    public boolean remove(String name) {
        Waypoint waypoint = find(name);
        return waypoint != null && remove(waypoint);
    }

    // Removes this one waypoint and never another of the same name. False once it has gone.
    public boolean remove(Waypoint waypoint) {
        int at = indexOf(waypoint);
        if (at < 0) {
            return false;
        }
        waypoints.remove(at);
        save();
        return true;
    }

    // The name matches and the waypoint belongs to that dimension of that server. A base in
    // the Nether never stands in for one in the overworld.
    private static boolean named(Waypoint waypoint, String name, String dimension, String server) {
        return waypoint.name().equalsIgnoreCase(name)
            && waypoint.dimension().equals(dimension)
            && ServerInfo.matches(waypoint.server(), server);
    }

    // Removes every waypoint of this server in every dimension and hands back how many went.
    public int clearServer() {
        String server = ServerInfo.key();
        int before = waypoints.size();
        waypoints.removeIf(waypoint -> ServerInfo.matches(waypoint.server(), server));
        save();
        return before - waypoints.size();
    }

    // Swaps every waypoint for the saved ones. Saved text that is no list leaves the store as it
    // is and an entry that cannot be read is left out.
    public void replaceWith(JsonElement saved) {
        List<Waypoint> decoded = decode(saved);
        waypoints.clear();
        waypoints.addAll(decoded);
        save();
    }

    private void load() {
        DataFiles.readJson(file, WaypointStore::decode).ifPresent(waypoints::addAll);
    }

    // One entry that cannot be read only loses itself.
    private static List<Waypoint> decode(JsonElement root) {
        return DataFiles.entries(root, WaypointStore::read, "saved waypoints");
    }

    // Null for an entry without a name or a spot or a real world.
    private static Waypoint read(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject o = element.getAsJsonObject();
        String typed = JsonFields.text(o, "name");
        String name = typed == null ? null : cleanName(typed);
        String dimension = JsonFields.text(o, "dimension");
        String server = JsonFields.text(o, "server");
        Integer x = JsonFields.whole(o, "x");
        Integer y = JsonFields.whole(o, "y");
        Integer z = JsonFields.whole(o, "z");
        if (name == null || dimension == null || server == null || x == null || y == null || z == null
            || dimension.length() > LONGEST_PLACE || server.length() > LONGEST_PLACE) {
            return null;
        }
        return new Waypoint(name, x, y, z, dimension, server, readColour(o),
            Boolean.TRUE.equals(JsonFields.flag(o, "hidden")), Boolean.TRUE.equals(JsonFields.flag(o, "found")),
            readOptions(o));
    }

    // A colour left to the name has the usual shade.
    private static Colour readColour(JsonObject o) {
        Double hue = JsonFields.number(o, "hue");
        boolean rainbow = Boolean.TRUE.equals(JsonFields.flag(o, "rainbow"));
        if ((hue == null || hue < 0) && !rainbow) {
            return Colour.AUTO;
        }
        Double saturation = JsonFields.number(o, "saturation");
        Double brightness = JsonFields.number(o, "brightness");
        int own = hue == null || hue < 0 ? Colour.NAME_HUE : Math.floorMod(Mth.floor(hue), Colour.FULL_CIRCLE);
        return new Colour(own,
            saturation == null ? Colour.USUAL_SATURATION : Math.clamp(saturation.floatValue(), 0f, 1f),
            brightness == null ? Colour.USUAL_BRIGHTNESS : Math.clamp(brightness.floatValue(), 0f, 1f), rainbow);
    }

    private static Options readOptions(JsonObject o) {
        Double scale = JsonFields.number(o, "scale");
        Double range = JsonFields.number(o, "range");
        Action action = JsonFields.choice(o, "near", Action.class);
        Double distance = JsonFields.number(o, "nearDistance");
        return new Options(JsonFields.choice(o, "icon", Icon.class),
            scale == null ? null : Math.max(Options.SMALLEST_SCALE, scale),
            range == null ? null : Math.max(0, range), JsonFields.flag(o, "mirrored"),
            action == null ? null : Near.of(action, distance == null ? Near.USUAL_DISTANCE : distance));
    }

    private void save() {
        DataFiles.writeJson(file, toJson());
    }

    // Every waypoint of every server the way the file holds them. A profile keeps a copy. Only
    // what differs from a plain waypoint is written.
    public JsonArray toJson() {
        JsonArray root = new JsonArray();
        for (Waypoint waypoint : waypoints) {
            JsonObject o = new JsonObject();
            o.addProperty("name", waypoint.name());
            o.addProperty("x", waypoint.x());
            o.addProperty("y", waypoint.y());
            o.addProperty("z", waypoint.z());
            o.addProperty("dimension", waypoint.dimension());
            o.addProperty("server", waypoint.server());
            write(o, waypoint.colour());
            if (waypoint.hidden()) {
                o.addProperty("hidden", true);
            }
            if (waypoint.found()) {
                o.addProperty("found", true);
            }
            write(o, waypoint.options());
            root.add(o);
        }
        return root;
    }

    private static void write(JsonObject o, Colour colour) {
        if (colour.hue() >= 0) {
            o.addProperty("hue", colour.hue());
        }
        if (colour.saturation() != Colour.USUAL_SATURATION) {
            o.addProperty("saturation", colour.saturation());
        }
        if (colour.brightness() != Colour.USUAL_BRIGHTNESS) {
            o.addProperty("brightness", colour.brightness());
        }
        if (colour.rainbow()) {
            o.addProperty("rainbow", true);
        }
    }

    private static void write(JsonObject o, Options options) {
        if (options.icon() != null) {
            o.addProperty("icon", options.icon().name());
        }
        if (options.scale() != null) {
            o.addProperty("scale", options.scale());
        }
        if (options.range() != null) {
            o.addProperty("range", options.range());
        }
        if (options.mirrored() != null) {
            o.addProperty("mirrored", options.mirrored());
        }
        Near near = options.near();
        if (near != null) {
            o.addProperty("near", near.action().name());
            if (near.acts()) {
                o.addProperty("nearDistance", near.distance());
            }
        }
    }
}
