package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

// Fills book and quill after book and quill with text.
// Random text makes heavy books and a file writes whatever you like.
public final class BookBot extends Module {

    public enum Mode { RANDOM, FILE }

    public enum Characters { ASCII, UNICODE, PAPER }

    // Book limits the server enforces.
    private static final int MAX_PAGES = 100;
    private static final int MAX_PAGE_LENGTH = 1024;

    // The Paper layout. Pages before this hold one wide character and the rest ASCII.
    private static final int PAPER_LIGHT_PAGES = 50;
    private static final int PAPER_MIXED_WIDE = 110;

    // Three bytes and two bytes of UTF-8 each.
    private static final char WIDE_CHARACTER = 0x4E00;
    private static final char MIDDLE_CHARACTER = 0x00A9;

    // Width in pixels and lines of one book page.
    private static final int PAGE_WIDTH = 114;
    private static final int PAGE_LINES = 14;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Where the text comes from.", Mode.RANDOM)
        .describe(Mode.RANDOM, "Random characters.")
        .describe(Mode.FILE, "The text of a file in the offlineclient folder.");
    private final TextSetting file = new TextSetting("File",
        "Name of the text file inside the offlineclient folder.", "bookbot.txt")
        .under(mode, Mode.FILE);
    private final EnumSetting<Characters> characters = new EnumSetting<>("Characters",
        "Which characters random text draws from.", Characters.UNICODE)
        .describe(Characters.ASCII, "Plain printable letters and symbols.")
        .describe(Characters.UNICODE, "Any printable character. Far heavier per page.")
        .describe(Characters.PAPER, "A fixed hundred page layout sized to the Paper packet limit. Pages and characters are ignored.")
        .under(mode, Mode.RANDOM);
    private final NumberSetting pages = new NumberSetting("Pages",
        "Pages per book.", 50, 1, MAX_PAGES, 1).max(MAX_PAGES)
        .under(mode, () -> mode.is(Mode.RANDOM) && !characters.is(Characters.PAPER));
    private final NumberSetting perPage = new NumberSetting("Characters per page",
        "How many characters each page holds.", 128, 1, MAX_PAGE_LENGTH, 1)
        .max(MAX_PAGE_LENGTH)
        .under(mode, () -> mode.is(Mode.RANDOM) && !characters.is(Characters.PAPER));
    private final BoolSetting wordWrap = new BoolSetting("Word wrap",
        "Breaks lines between words the way the book screen would.", true)
        .under(mode, Mode.FILE);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between books.", 20, 1, 200, 1, " ticks").min(1);
    private final BoolSetting sign = new BoolSetting("Sign",
        "Signs the book and locks it against editing.", true);
    private final TextSetting title = new TextSetting("Title",
        "Title given to a signed book.", "Offline").under(sign);
    private final BoolSetting number = new BoolSetting("Number books",
        "Adds the book count to each title.", true).under(sign);

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final Random random = new Random();
    private int timer;
    private int written;

    public BookBot() {
        super("BookBot", "Writes book after book for you.", Category.MISC);
        addSettings(mode, file, characters, pages, perPage, wordWrap, delay, sign, title, number);
        searchTags("book and quill", "write books");
    }

    @Override
    public String getSuffix() {
        return written == 0 ? null : written + " written";
    }

    @Override
    protected void onEnable() {
        timer = delay.getInt();
        written = 0;
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gui.screen() != null) {
            return;
        }
        if (!isBlankBook(mc.player.getMainHandItem())) {
            int slot = InventoryUtil.findSlot(BookBot::isBlankBook, InventoryUtil.WHOLE_INVENTORY);
            if (slot == -1) {
                ChatUtil.message("§bBookBot §7has no blank books left.");
                setEnabled(false);
                return;
            }
            loan.select(slot);
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        timer = delay.getInt();
        List<String> text = mode.is(Mode.FILE) ? fromFile() : randomPages();
        if (text == null) {
            setEnabled(false);
            return;
        }
        send(text);
    }

    private static boolean isBlankBook(ItemStack stack) {
        if (!stack.is(Items.WRITABLE_BOOK)) {
            return false;
        }
        WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        return content == null || content.pages().isEmpty();
    }

    private List<String> randomPages() {
        if (characters.is(Characters.PAPER)) {
            return paperPages();
        }
        int high = characters.is(Characters.ASCII) ? 0x7F : 0xD800;
        List<String> result = new ArrayList<>();
        for (int page = 0; page < pages.getInt(); page++) {
            StringBuilder text = new StringBuilder();
            while (text.length() < perPage.getInt()) {
                int codePoint = random.nextInt(0x21, high);
                if (!Character.isWhitespace(codePoint) && Character.isDefined(codePoint)) {
                    text.appendCodePoint(codePoint);
                }
            }
            result.add(text.toString());
        }
        return result;
    }

    // A hundred pages built to sit right on the Paper packet size limit. The
    // first fifty are mostly one byte characters and the last fifty are three.
    private List<String> paperPages() {
        List<String> result = new ArrayList<>(MAX_PAGES);
        for (int page = 0; page < MAX_PAGES; page++) {
            StringBuilder text = new StringBuilder(MAX_PAGE_LENGTH);
            if (page < PAPER_LIGHT_PAGES) {
                text.append(wide(1)).append(narrow(MAX_PAGE_LENGTH - 1));
            } else if (page == PAPER_LIGHT_PAGES) {
                text.append(wide(PAPER_MIXED_WIDE)).append(MIDDLE_CHARACTER)
                    .append(narrow(MAX_PAGE_LENGTH - PAPER_MIXED_WIDE - 1));
            } else {
                text.append(wide(MAX_PAGE_LENGTH));
            }
            result.add(text.toString());
        }
        return result;
    }

    private String wide(int count) {
        return String.valueOf(WIDE_CHARACTER).repeat(count);
    }

    private String narrow(int count) {
        StringBuilder text = new StringBuilder(count);
        while (text.length() < count) {
            int codePoint = random.nextInt(0x21, 0x7F);
            text.appendCodePoint(codePoint);
        }
        return text.toString();
    }

    // Null when the file cannot be read. A message says why.
    private List<String> fromFile() {
        Path path = OfflineClient.MC.gameDirectory.toPath().resolve("offlineclient").resolve(file.getValue());
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            ChatUtil.error("Could not read " + path.getFileName() + " from the offlineclient folder.");
            return null;
        }
        if (text.isBlank()) {
            ChatUtil.error(path.getFileName() + " is empty.");
            return null;
        }
        return wordWrap.isOn() ? wrapPages(text) : cutPages(text);
    }

    // The game's own line breaking decides where each page ends.
    private List<String> wrapPages(String text) {
        List<FormattedText> lines = mc.font.splitIgnoringLanguage(Component.literal(text), PAGE_WIDTH);
        List<String> result = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int lineCount = 0;
        for (FormattedText line : lines) {
            if (!page.isEmpty()) {
                page.append('\n');
            }
            page.append(line.getString());
            if (++lineCount == PAGE_LINES) {
                result.add(page.toString());
                page.setLength(0);
                lineCount = 0;
                if (result.size() == MAX_PAGES) {
                    return result;
                }
            }
        }
        if (!page.isEmpty()) {
            result.add(page.toString());
        }
        return result;
    }

    private static List<String> cutPages(String text) {
        List<String> result = new ArrayList<>();
        for (int start = 0; start < text.length() && result.size() < MAX_PAGES; start += MAX_PAGE_LENGTH) {
            result.add(text.substring(start, Math.min(text.length(), start + MAX_PAGE_LENGTH)));
        }
        return result;
    }

    private void send(List<String> text) {
        String name = title.getValue();
        if (number.isOn()) {
            name += " " + (written + 1);
        }
        Optional<String> signed = sign.isOn() ? Optional.of(name) : Optional.empty();
        mc.player.connection.send(new ServerboundEditBookPacket(
            InventoryUtil.selectedSlot(), text, signed));
        written++;
    }
}
