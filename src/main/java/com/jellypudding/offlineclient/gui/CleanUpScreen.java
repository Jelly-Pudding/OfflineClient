package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.util.Armed;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.server.network.EventLoopGroupHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

// Every server is looked up and pinged from a thread of its own and one that fails is tried again
// after a pause.
public final class CleanUpScreen extends ListWindow {

    private enum Finding { WAITING, CHECKING, ANSWERS, OTHER_VERSION, NO_ANSWER, UNKNOWN_HOST }

    private enum Rule {
        ALL("All", "Every server on your list."),
        UNKNOWN_HOST("Unknown host", "Servers whose address led nowhere on both tries."),
        NO_ANSWER("No answer", "Servers that were found and answered neither of two pings."),
        OTHER_VERSION("Other version", "Servers that answered and run a version this game cannot join.");

        private final String label;
        private final String help;

        Rule(String label, String help) {
            this.label = label;
            this.help = help;
        }

        private boolean takes(Entry entry) {
            return switch (this) {
                case ALL -> true;
                case UNKNOWN_HOST -> entry.finding == Finding.UNKNOWN_HOST;
                case NO_ANSWER -> entry.finding == Finding.NO_ANSWER;
                case OTHER_VERSION -> entry.finding == Finding.OTHER_VERSION;
            };
        }
    }

    private enum Keep implements RowList.Button {
        KEEP("keep", "Leaves this server on your list when the rest shown here go."),
        KEPT("kept", "This server stays. Click to let it go with the rest shown here.");

        private final String label;
        private final String help;

        Keep(String label, String help) {
            this.label = label;
            this.help = help;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String help() {
            return help;
        }

        @Override
        public boolean warns() {
            return false;
        }
    }

    private record Answer(String version, int protocol, long ping) {
    }

    // Silence until the deadline counts as no answer.
    private static final class Look {

        private final long deadline;
        private volatile boolean failed;
        private volatile boolean unknownHost;

        private Look(long deadline) {
            this.deadline = deadline;
        }
    }

    // The answer comes from another thread.
    private static final class Entry {

        private final ServerData server;
        private Finding finding = Finding.WAITING;
        private boolean spared;
        private int tries;
        private long notBefore;
        private boolean unknownEveryTime = true;
        private Look look;
        private volatile Answer answer;

        private Entry(ServerData server) {
            this.server = server;
        }

        private boolean settled() {
            return finding != Finding.WAITING && finding != Finding.CHECKING;
        }
    }

    // A failed ping alone says little. A server has to fail this often to count as gone.
    private static final int TRIES = 2;
    private static final long TRY_MS = TimeUnit.SECONDS.toMillis(8);
    private static final long RETRY_MS = TimeUnit.SECONDS.toMillis(5);
    private static final int MOST_AT_ONCE = 8;
    private static final int LONGEST_VERSION = 24;

    private static final int REMOVE_WIDTH = 84;
    private static final String SEPARATOR = " · ";
    private static final String HINT = "Pick a rule on the left or search by name or address. Everything listed"
        + " goes when you press remove twice. Click a server to keep it.";

    private final RowList<Entry> list = new RowList<>();
    // The list of the screen under this one. A late ping there saves it and must not bring back
    // what went.
    private final ServerList servers;
    private final ServerStatusPinger pinger = new ServerStatusPinger();
    private final List<Entry> entries = new ArrayList<>();
    // A fresh copy of the screen under this one. Its rows show what is left after a removal.
    private final Supplier<Screen> reopened;
    private Rule rule = Rule.ALL;
    private boolean stale = true;
    private boolean removedAny;
    // Set once the screen is gone. A try that has not begun yet then never does.
    private volatile boolean closed;
    private final Armed<List<Entry>> armed = new Armed<>();

    public CleanUpScreen(JoinMultiplayerScreen parent, Supplier<Screen> reopened) {
        super("Clean up", parent);
        this.reopened = reopened;
        servers = parent.getServers();
        for (int i = 0; i < servers.size(); i++) {
            entries.add(new Entry(servers.get(i)));
        }
    }

    @Override
    protected void changed() {
        stale = true;
    }

    @Override
    public void tick() {
        super.tick();
        pinger.tick();
        long now = System.currentTimeMillis();
        int running = 0;
        for (Entry entry : entries) {
            if (settle(entry, now)) {
                running++;
            }
        }
        for (Entry entry : entries) {
            if (running >= MOST_AT_ONCE) {
                break;
            }
            if (entry.finding == Finding.WAITING && now >= entry.notBefore) {
                start(entry, now);
                running++;
            }
        }
    }

    // An answer counts whichever try it came from and turns a server that seemed gone back.
    private boolean settle(Entry entry, long now) {
        Answer answer = entry.answer;
        if (answer != null) {
            Finding found = answer.protocol() == SharedConstants.getProtocolVersion()
                ? Finding.ANSWERS : Finding.OTHER_VERSION;
            if (entry.finding != found) {
                entry.finding = found;
                entry.look = null;
                changed();
            }
            return false;
        }
        Look look = entry.look;
        if (entry.finding != Finding.CHECKING || look == null) {
            return false;
        }
        if (!look.failed && now < look.deadline) {
            return true;
        }
        entry.look = null;
        entry.unknownEveryTime &= look.unknownHost;
        if (entry.tries < TRIES) {
            entry.finding = Finding.WAITING;
            entry.notBefore = now + RETRY_MS;
        } else {
            entry.finding = entry.unknownEveryTime ? Finding.UNKNOWN_HOST : Finding.NO_ANSWER;
        }
        changed();
        return false;
    }

    // The lookup and the ping work on a copy. The connection blocks its thread until the server
    // takes it or turns it down.
    private void start(Entry entry, long now) {
        Look look = new Look(now + TRY_MS);
        entry.look = look;
        entry.finding = Finding.CHECKING;
        entry.tries++;
        ServerData copy = new ServerData(entry.server.name, entry.server.ip, ServerData.Type.OTHER);
        EventLoopGroupHolder group = EventLoopGroupHolder.remote(OfflineClient.MC.options.useNativeTransport());
        Thread.ofVirtual().name("OfflineClient server check").start(() -> {
            if (closed) {
                return;
            }
            try {
                if (ServerNameResolver.DEFAULT.resolveAddress(ServerAddress.parseString(copy.ip)).isEmpty()) {
                    look.unknownHost = true;
                    look.failed = true;
                    return;
                }
                pinger.pingServer(copy, () -> { }, () -> entry.answer = answerOf(copy), group);
                // A connection that opened after the screen went would never be closed.
                if (closed) {
                    pinger.removeAll();
                }
            } catch (Exception e) {
                look.failed = true;
            }
        });
    }

    // Runs on the connection thread.
    private static Answer answerOf(ServerData pinged) {
        String version = pinged.version.getString();
        return new Answer(version.length() > LONGEST_VERSION ? version.substring(0, LONGEST_VERSION) : version,
            pinged.protocol, pinged.ping);
    }

    private List<Entry> shown() {
        String query = search.get().trim().toLowerCase(Locale.ROOT);
        List<Entry> shown = new ArrayList<>();
        for (Entry entry : entries) {
            boolean named = query.isEmpty() || entry.server.name.toLowerCase(Locale.ROOT).contains(query)
                || entry.server.ip.toLowerCase(Locale.ROOT).contains(query);
            if (named && rule.takes(entry)) {
                shown.add(entry);
            }
        }
        return shown;
    }

    private List<Entry> doomed() {
        List<Entry> doomed = shown();
        doomed.removeIf(entry -> entry.spared);
        return doomed;
    }

    private void rebuild() {
        stale = false;
        List<RowList.Row<Entry>> rows = new ArrayList<>();
        for (Entry entry : shown()) {
            rows.add(new RowList.Row<>(entry, entry.server.name, entry.spared ? GuiTheme.textFaint() : GuiTheme.text(),
                entry.server.ip + SEPARATOR + found(entry), List.of(entry.spared ? Keep.KEPT : Keep.KEEP),
                entry.spared ? Keep.KEPT.help() : Keep.KEEP.help()));
        }
        list.show(rows);
        // The button stands down once the servers it would take differ from the ones it was armed for.
        if (!armed.isArmed(doomed())) {
            armed.disarm();
        }
    }

    private static String found(Entry entry) {
        return switch (entry.finding) {
            case WAITING -> entry.tries == 0 ? "waiting to be checked" : "failed once and waits for another try";
            case CHECKING -> "checking";
            case ANSWERS -> "answers in " + entry.answer.ping() + " ms";
            case OTHER_VERSION -> "runs " + (entry.answer.version().isBlank() ? "another version"
                : entry.answer.version());
            case NO_ANSWER -> "no answer to " + TRIES + " pings";
            case UNKNOWN_HOST -> "unknown host on " + TRIES + " tries";
        };
    }

    // Then the fault is more likely on this side.
    private boolean nobodyAnswers() {
        boolean any = false;
        for (Entry entry : entries) {
            if (!entry.settled() || entry.finding == Finding.ANSWERS || entry.finding == Finding.OTHER_VERSION) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private int removeX() {
        return windowX() + windowWidth() - MARGIN - REMOVE_WIDTH;
    }

    @Override
    protected void renderView(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (stale) {
            rebuild();
        }
        Font font = ClientFonts.gui();
        footer.begin();
        List<Entry> doomed = doomed();
        if (armed.isArmed()) {
            footer.show("Click again to take " + servers(doomed.size()) + " off your server list for good.",
                GuiTheme.RED_TEXT);
        } else if (rule != Rule.ALL && nobodyAnswers()) {
            footer.show("Not one server answered. Your own connection or proxy may be down and these servers fine.",
                GuiTheme.RED_TEXT);
        }
        footer.showStatus();
        int wx = windowX();
        int wy = windowY();
        int ww = windowWidth();
        WindowParts.frame(context, wx, wy, ww, windowHeight());
        WindowParts.title(context, font, wx, wy, ww, "Clean up", "your server list", servers(entries.size()));
        renderToolbar(context, font, wx, doomed.size(), mouseX, mouseY);

        List<String> names = new ArrayList<>();
        for (Rule each : Rule.values()) {
            names.add(each.label);
        }
        int tabX = wx + MARGIN;
        WindowParts.tabs(context, font, tabX, contentTop(), WindowParts.tabWidth(), contentHeight(), names,
            this::tally, rule.ordinal(), mouseX, mouseY);
        int overTab = WindowParts.tabAt(mouseX, mouseY, tabX, contentTop(), WindowParts.tabWidth(), contentHeight(),
            Rule.values().length);
        if (overTab >= 0) {
            footer.show(Rule.values()[overTab].help, GuiTheme.text());
        }
        list.render(context, font, listX(), contentTop(), listWidth(), contentHeight(), mouseX, mouseY, footer,
            entries.isEmpty() ? "your server list is empty" : "no server here");
        footer.render(context, font, wx, wy, ww, windowHeight(), HINT);
    }

    private static String servers(int count) {
        return Tally.counted(count, "server");
    }

    private String tally(int tab) {
        Rule counted = Rule.values()[tab];
        return String.valueOf(entries.stream().filter(counted::takes).count());
    }

    private void renderToolbar(GuiGraphicsExtractor context, Font font, int wx, int doomed, int mouseX, int mouseY) {
        int y = toolbarY();
        int searchX = wx + MARGIN;
        search.render(context, font, searchX, y, removeX() - 4 - searchX, mouseX, mouseY,
            search.isEmpty() ? null : Tally.counted(list.size(), "match"));
        if (armed.isArmed()) {
            RowList.warning(context, font, removeX(), y, REMOVE_WIDTH, SearchField.HEIGHT, "confirm");
            return;
        }
        boolean over = SettingWidget.isOver(mouseX, mouseY, removeX(), y, REMOVE_WIDTH, SearchField.HEIGHT);
        GuiTheme.button(context, font, removeX(), y, REMOVE_WIDTH, SearchField.HEIGHT, "remove " + doomed,
            over && doomed > 0, doomed > 0);
        if (over) {
            footer.show(doomed == 0 ? "Nothing listed here would go."
                : "Takes the " + servers(doomed) + " listed here off your server list. It asks once more first.",
                GuiTheme.text());
        }
    }

    @Override
    protected boolean click(double mx, double my) {
        if (search.click(mx, my) != SearchField.Click.MISSED) {
            return true;
        }
        if (SettingWidget.isOver(mx, my, removeX(), toolbarY(), REMOVE_WIDTH, SearchField.HEIGHT)) {
            pressRemove();
            return true;
        }
        int tab = WindowParts.tabAt(mx, my, windowX() + MARGIN, contentTop(), WindowParts.tabWidth(),
            contentHeight(), Rule.values().length);
        if (tab >= 0) {
            rule = Rule.values()[tab];
            list.toTop();
            changed();
            return true;
        }
        return list.click(mx, my, (entry, button) -> {
            entry.spared = !entry.spared;
            changed();
        });
    }

    private void pressRemove() {
        List<Entry> doomed = doomed();
        if (doomed.isEmpty() || !armed.press(doomed)) {
            return;
        }
        doomed.forEach(entry -> servers.remove(entry.server));
        servers.save();
        removedAny = true;
        entries.removeAll(doomed);
        footer.say("Took " + servers(doomed.size()) + " off your list");
        changed();
    }

    @Override
    protected void releaseLists() {
        list.release();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return list.scroll(toView(mouseX), toView(mouseY), scrollY)
            || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // The screen under this one still has rows for the servers that went. A fresh one shows what
    // is left.
    @Override
    public void onClose() {
        OfflineClient.MC.gui.setScreen(removedAny ? reopened.get() : parent);
    }

    @Override
    public void removed() {
        closed = true;
        pinger.removeAll();
        super.removed();
    }
}
