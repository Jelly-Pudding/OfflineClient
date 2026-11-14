package com.jellypudding.offlineclient.rig.suite;

import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.duo.Duo;
import com.jellypudding.offlineclient.rig.duo.PackTrap;
import com.jellypudding.offlineclient.rig.duo.Partner;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;

import java.util.List;
import java.util.Map;

// What a server can find out about the game that joined it and what ServerSpoof lets it find.
// The server plays each trick itself the way a plugin that hunts for mods plays it. Every trick is
// played a second time with the guard off to prove the server would have caught the game.
final class SpoofPlays {

    private static final String SPOOF = "ServerSpoof";
    private static final String HIDE_TEXT = "Hide mod text";
    private static final String BLOCK_PACKS = "Block resource packs";
    private static final String ON = "on";
    private static final String OFF = "off";
    private static final String LOADER = "fabric";
    private static final String PLAIN_BRAND = "vanilla";
    // Words only the mod loader brings along. A game without mods reads the key itself.
    private static final String MOD_KEY = "pack.description.modResources";
    // Words and a key bind of the game itself. Every game reads these the same.
    private static final String GAME_KEY = "block.minecraft.stone";
    private static final String GAME_WORDS = "Stone";
    private static final String GAME_BIND = "key.jump";
    private static final String BIND = "bind:";
    private static final String LINE_SPLIT = "\\|";
    private static final String CHOSEN_HOST = "play.example.org";
    private static final String LOADED = "SUCCESSFULLY_LOADED";
    private static final double FACE_TO_FACE = 4;
    private static final double NEAR = 0.5;
    private static final int OPEN_TICKS = 100;
    private static final int ANSWER_TICKS = 100;
    private static final int PACK_TICKS = 60;

    private SpoofPlays() {
    }

    static void addTo(Map<String, ServerSuite.Play> plays) {
        plays.put("both players stand on the server", SpoofPlays::bothThere);
        plays.put("ServerSpoof tells the server a plain game joined", SpoofPlays::brand);
        plays.put("the server sees the mod loader with ServerSpoof off", SpoofPlays::unspoofed);
        plays.put("ServerSpoof keeps mod words out of a sign the server shows", SpoofPlays::signText);
        plays.put("ServerSpoof keeps mod words out of an anvil the server opens", SpoofPlays::anvilText);
        plays.put("ServerSpoof tells the server the host name you chose", SpoofPlays::hostName);
        plays.put("ServerSpoof answers for a resource pack and never fetches it", SpoofPlays::packBlocked);
    }

    private static void bothThere(Rig rig, Duo duo) {
        rig.expect("true".equals(duo.lead().get("online")), "The server does not hold the first player");
        rig.expect("true".equals(duo.server().player(Partner.NAME).get("online")),
            "The server does not hold the second player");
        double far = Double.parseDouble(duo.partner().see(rig.world().player()).get("far"));
        rig.note("The second player sees the first " + far + " blocks away");
        rig.expect(Math.abs(far - FACE_TO_FACE) < NEAR, "The second player does not see the first where it stands");
    }

    private static void brand(Rig rig, Duo duo) {
        Map<String, String> held = duo.lead();
        rig.note("The server holds the brand " + held.get("brand") + " and the channels " + held.get("channels"));
        rig.expect(PLAIN_BRAND.equals(held.get("brand")), "The server was told the brand " + held.get("brand"));
        rig.expect(!held.getOrDefault("channels", "").contains(LOADER),
            "The server heard channels of the mod loader " + held.get("channels"));
    }

    private static void unspoofed(Rig rig, Duo duo) {
        rig.client().disable(SPOOF);
        duo.rejoin();
        try {
            Map<String, String> held = duo.lead();
            rig.note("The server holds the brand " + held.get("brand") + " and the channels " + held.get("channels"));
            rig.expect(String.valueOf(held.get("brand")).contains(LOADER),
                "The server was told the brand " + held.get("brand") + " with ServerSpoof off");
        } finally {
            rig.client().enable(SPOOF);
            duo.rejoin();
        }
    }

    private static void signText(Rig rig, Duo duo) {
        List<String> guarded = signLines(rig, duo);
        rig.client().set(SPOOF, HIDE_TEXT, OFF);
        List<String> open = signLines(rig, duo);
        rig.note("The sign came back as " + guarded + " and with the guard off as " + open);
        rig.expect(!open.getFirst().equals(MOD_KEY), "The trick caught nothing with the guard off and proves nothing");
        rig.expect(guarded.getFirst().equals(MOD_KEY), "The sign gave mod words away. It read " + guarded.getFirst());
        rig.expect(guarded.get(1).equals(GAME_WORDS), "Words of the game itself read " + guarded.get(1));
        rig.expect(guarded.get(2).equals(open.get(2)) && !guarded.get(2).equals(GAME_BIND),
            "A key bind of the game itself read " + guarded.get(2) + " and not " + open.get(2));
    }

    // The server stands a sign of three keys before the player and opens it. Shutting the screen
    // sends the lines back.
    private static List<String> signLines(Rig rig, Duo duo) {
        String player = rig.world().player();
        int mark = duo.server().logMark();
        duo.server().console("probe sign " + player + " " + MOD_KEY + " " + GAME_KEY + " " + BIND + GAME_BIND);
        rig.until("the sign to open", OPEN_TICKS, mc -> mc.gui.screen() instanceof AbstractSignEditScreen);
        rig.act(mc -> mc.gui.screen().onClose());
        String lines = duo.server().heard("sign " + player, mark, ANSWER_TICKS);
        rig.expect(lines != null, "The server never got the sign back");
        return List.of(lines.split(LINE_SPLIT, -1));
    }

    private static void anvilText(Rig rig, Duo duo) {
        String guarded = anvilName(rig, duo);
        rig.client().set(SPOOF, HIDE_TEXT, OFF);
        String open = anvilName(rig, duo);
        rig.note("The name came back as " + guarded + " and with the guard off as " + open);
        rig.expect(open != null && !open.equals(MOD_KEY),
            "The trick caught nothing with the guard off and proves nothing");
        rig.expect(MOD_KEY.equals(guarded), "The anvil gave mod words away. It read " + guarded);
    }

    // The server opens an anvil and lays an item named by a key in it. The game writes the name
    // into the box and tells the server what the box holds.
    private static String anvilName(Rig rig, Duo duo) {
        String player = rig.world().player();
        int mark = duo.server().logMark();
        duo.server().console("probe anvil " + player + " " + MOD_KEY);
        rig.until("the anvil to open", OPEN_TICKS, mc -> mc.gui.screen() instanceof AnvilScreen);
        String name = duo.server().heard("anvil " + player, mark, ANSWER_TICKS);
        rig.act(mc -> mc.player.closeContainer());
        return name;
    }

    private static void hostName(Rig rig, Duo duo) {
        String typed = duo.lead().get("host");
        rig.client().set(SPOOF, "Host name", CHOSEN_HOST);
        duo.rejoin();
        try {
            String told = duo.lead().get("host");
            rig.note("The server heard the host " + told + " and before that " + typed);
            rig.expect(CHOSEN_HOST.equals(told), "The server heard the host " + told);
        } finally {
            rig.client().restoreDefaults();
            duo.rejoin();
        }
    }

    private static void packBlocked(Rig rig, Duo duo) {
        try (PackTrap trap = new PackTrap()) {
            rig.client().set(SPOOF, BLOCK_PACKS, ON);
            List<String> guarded = packAnswers(rig, duo, trap);
            int fetched = trap.asked();
            rig.client().set(SPOOF, BLOCK_PACKS, OFF);
            List<String> open = packAnswers(rig, duo, trap);
            // The trap handed out nothing and the game wrote that failure down. It was asked for.
            rig.log().skipToEnd();
            rig.note("The server heard " + guarded + " and the pack address was asked " + fetched
                + " times. With the block off it heard " + open + " and the address was asked "
                + (trap.asked() - fetched) + " times");
            rig.expect(trap.asked() > fetched, "The game never fetched a pack with the block off. That proves nothing");
            rig.expect(fetched == 0, "The game asked the pack address " + fetched + " times whilst packs were blocked");
            rig.expect(guarded.contains(LOADED), "The server was not told the pack loaded. It heard " + guarded);
        }
    }

    private static List<String> packAnswers(Rig rig, Duo duo, PackTrap trap) {
        String player = rig.world().player();
        int mark = duo.server().logMark();
        duo.server().console("probe pack " + player + " " + trap.url());
        rig.ticks(PACK_TICKS);
        return duo.server().allHeard("pack " + player, mark);
    }
}
