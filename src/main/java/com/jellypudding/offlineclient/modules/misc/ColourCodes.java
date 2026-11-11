package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BookLimits;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.FormatCodes;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;

import java.util.List;

// The text is changed on its way out. A sign or a book written by hand and one a module writes
// leave through the same two packets.
public final class ColourCodes extends Module {

    private final BoolSetting signs = new BoolSetting("Signs",
        "Colours the signs you write. The plain game and a plain Paper server keep the codes. A plugin"
            + " that guards sign colours may still take them out.", true);
    private final BoolSetting books = new BoolSetting("Books",
        "Colours the pages and the title of the books you write.", true);

    private final ChatWarning tooHeavy = new ChatWarning();

    public ColourCodes() {
        super("ColourCodes", "Turns codes such as &c for red and &l for bold on the signs and books you write"
            + " into real colours and styles.", Category.MISC);
        addSettings(signs, books);
        searchTags("color signs", "colour signs", "sign colour", "book colour", "formatting codes", "ampersand");
    }

    @Override
    protected void onEnable() {
        tooHeavy.clear();
    }

    // True whilst sign lines go out with their codes made real.
    public boolean coloursSigns() {
        return isEnabled() && signs.isOn();
    }

    // After every handler that reads the text as it was typed. A section sign weighs more than the
    // ampersand it stands for. A book Paper takes as typed but would refuse coloured goes as typed.
    @Subscribe(priority = Subscribe.LAST)
    private void onPacketSend(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundSignUpdatePacket sign && signs.isOn()) {
            List<String> lines = sign.lines().stream().map(FormatCodes::forSign).toList();
            if (!lines.equals(sign.lines())) {
                event.setPacket(new ServerboundSignUpdatePacket(sign.pos(), lines, sign.slot()));
            }
        } else if (event.getPacket() instanceof ServerboundEditBookPacket book && books.isOn()) {
            List<String> pages = book.pages().stream().map(FormatCodes::coded).toList();
            if (ServerInfo.runsPaper() && BookLimits.paperTakes(book.pages()) && !BookLimits.paperTakes(pages)) {
                tooHeavy.say("ColourCodes sent a book with its codes as typed. Its colours would make it too heavy"
                    + " for Paper and the server would kick you.");
                return;
            }
            event.setPacket(new ServerboundEditBookPacket(book.slot(), pages, book.title().map(FormatCodes::coded)));
        }
    }
}
