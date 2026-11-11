package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BookLimits;
import com.jellypudding.offlineclient.util.BookPace;
import com.jellypudding.offlineclient.util.BookText;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.FormatCodes;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.MenuClicks;
import com.jellypudding.offlineclient.util.Recipes;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

// Fills book and quill after book and quill with text.
// Random text makes heavy books and a file writes whatever you like.
public final class BookBot extends Module {

    public enum Mode { RANDOM, FILE }

    public enum Characters { ASCII, UNICODE, PAPER }

    // The Paper layout. Pages before this hold one wide character and the rest ASCII.
    private static final int PAPER_LIGHT_PAGES = 50;
    private static final int PAPER_MIXED_WIDE = 110;

    // Three bytes and two bytes of UTF8 each.
    private static final char WIDE_CHARACTER = 0x4E00;
    private static final char MIDDLE_CHARACTER = 0x00A9;

    // A book holds no more characters than this. The rest of a longer file is never read.
    private static final int MOST_READ = WritableBookContent.MAX_PAGES * WritableBookContent.PAGE_EDIT_LENGTH;

    // Ticks the server is given to write a book or fill the crafting grid.
    private static final int SERVER_WAIT = 40;

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
        "Pages per book.", 50, 1, WritableBookContent.MAX_PAGES, 1).max(WritableBookContent.MAX_PAGES)
        .under(mode, () -> mode.is(Mode.RANDOM) && !characters.is(Characters.PAPER));
    private final NumberSetting perPage = new NumberSetting("Characters per page",
        "How many characters each page holds.", 128, 1, WritableBookContent.PAGE_EDIT_LENGTH, 1)
        .max(WritableBookContent.PAGE_EDIT_LENGTH)
        .under(mode, () -> mode.is(Mode.RANDOM) && !characters.is(Characters.PAPER));
    private final BoolSetting wordWrap = new BoolSetting("Word wrap",
        "Breaks lines between words the way the book screen would.", true)
        .under(mode, Mode.FILE);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between books. Paper needs about a second between books and longer whilst it lags.",
        20, 1, 200, 1, " ticks");
    private final BoolSetting sign = new BoolSetting("Sign",
        "Signs the book and locks it against editing.", true);
    private final TextSetting title = new TextSetting("Title",
        "Title given to a signed book.", "Offline").under(sign);
    private final BoolSetting number = new BoolSetting("Number books",
        "Adds the book count to each title.", true).under(sign);
    private final BoolSetting dropWhenDone = new BoolSetting("Drop when done",
        "Throws each book on the ground once it is written.", false);
    private final BoolSetting craftQuills = new BoolSetting("Craft quills",
        "Crafts a book and quill in your inventory when you run out.", false);

    private final InventoryUtil.HotbarLoan loan = new InventoryUtil.HotbarLoan();
    private final Random random = new Random();
    private int timer;
    private int written;
    // The inventory slot of the book last sent until the server writes it. Minus one whilst none.
    private int awaiting = -1;
    private int awaitTicks;
    // True from a recipe request until its result shows in the grid.
    private boolean crafting;
    private int craftTicks;
    // True once chat has said that pages were left off a book.
    private boolean toldLeftOff;
    // True once chat has said that Paper held a book back.
    private boolean toldPace;

    public BookBot() {
        super("BookBot", "Writes book after book for you.", Category.MISC);
        addSettings(mode, file, characters, pages, perPage, wordWrap, delay, sign, title, number,
            dropWhenDone, craftQuills);
        searchTags("book and quill", "write books");
    }

    @Override
    public String getSuffix() {
        return count(written, "written");
    }

    @Override
    protected void onEnable() {
        written = 0;
        awaiting = -1;
        crafting = false;
        toldLeftOff = false;
        toldPace = false;
        timer = delay.getInt();
    }

    @Override
    protected void onDisable() {
        loan.giveBack();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.gui.screen() != null) {
            return;
        }
        if (awaiting != -1) {
            settle();
            return;
        }
        if (!isBlankBook(mc.player.getMainHandItem())) {
            int slot = InventoryUtil.findSlot(BookBot::isBlankBook, InventoryUtil.WHOLE_INVENTORY);
            if (slot != -1) {
                loan.select(slot);
                return;
            }
            if (craftQuills.isOn() && craftQuill()) {
                return;
            }
            tell("has no blank books left.");
            setEnabled(false);
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        if (waitsForPaper()) {
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

    // True whilst Paper would still kick for another book edit. Chat tells the player once each run.
    private boolean waitsForPaper() {
        if (BookPace.INSTANCE.ready()) {
            return false;
        }
        if (!toldPace) {
            toldPace = true;
            tell("waits for Paper between books. Paper kicks a player who writes faster.");
        }
        return true;
    }

    // The server writes a book a moment after the packet. Once it shows written it can be thrown.
    // A book still blank after the wait was refused and is written again. Anything else moved
    // into the slot meanwhile stays.
    private void settle() {
        ItemStack book = mc.player.getInventory().getItem(awaiting);
        if (!isBlankBook(book)) {
            if (dropWhenDone.isOn() && (book.is(Items.WRITTEN_BOOK) || book.is(Items.WRITABLE_BOOK))) {
                InventoryUtil.throwFrom(awaiting);
            }
            awaiting = -1;
        } else if (++awaitTicks > SERVER_WAIT) {
            awaiting = -1;
        }
    }

    // One step of crafting a book and quill in the inventory grid. A book is crafted first when
    // it is the only thing missing. False when there is nothing to craft one from.
    private boolean craftQuill() {
        InventoryMenu menu = mc.player.inventoryMenu;
        if (!InventoryUtil.cursorFree()) {
            return false;
        }
        boolean roomy = mc.player.getInventory().getFreeSlot() != -1;
        // The server sends the result first and the grid a moment later.
        if (crafting) {
            if (menu.getResultSlot().hasItem() && Recipes.gridFilled(menu) && roomy) {
                MenuClicks.quickMove(menu, menu.getResultSlot().index);
                crafting = false;
            } else if (++craftTicks > SERVER_WAIT) {
                disable(roomy ? "The server crafted nothing for BookBot." : "BookBot has no room for what it crafted.");
            }
            return true;
        }
        if (Recipes.gridFilled(menu)) {
            disable("BookBot needs the crafting grid of your inventory empty.");
            return true;
        }
        Recipes.Craft craft = nextCraft(menu);
        if (craft == null) {
            return false;
        }
        if (!roomy) {
            disable("BookBot has no room to craft a book and quill.");
            return true;
        }
        Recipes.placeOne(menu, craft);
        crafting = true;
        craftTicks = 0;
        return true;
    }

    // A book and quill when the inventory holds what it takes. Otherwise a book when that book is
    // all a book and quill still lacks. Null when neither can be crafted.
    private static Recipes.Craft nextCraft(InventoryMenu menu) {
        Map<Item, List<Recipes.Craft>> known = Recipes.known(item -> item == Items.WRITABLE_BOOK
            || item == Items.BOOK);
        List<Recipes.Craft> quills = known.getOrDefault(Items.WRITABLE_BOOK, List.of());
        StackedItemContents carried = Recipes.carried();
        Recipes.Craft quill = Recipes.craftable(quills, menu, carried);
        if (quill != null) {
            return quill;
        }
        StackedItemContents withBook = Recipes.carried();
        withBook.accountStack(new ItemStack(Items.BOOK));
        if (Recipes.craftable(quills, menu, withBook) == null) {
            return null;
        }
        return Recipes.craftable(known.getOrDefault(Items.BOOK, List.of()), menu, carried);
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
        List<String> result = new ArrayList<>(WritableBookContent.MAX_PAGES);
        for (int page = 0; page < WritableBookContent.MAX_PAGES; page++) {
            StringBuilder text = new StringBuilder(WritableBookContent.PAGE_EDIT_LENGTH);
            if (page < PAPER_LIGHT_PAGES) {
                text.append(wide(1)).append(narrow(WritableBookContent.PAGE_EDIT_LENGTH - 1));
            } else if (page == PAPER_LIGHT_PAGES) {
                text.append(wide(PAPER_MIXED_WIDE)).append(MIDDLE_CHARACTER)
                    .append(narrow(WritableBookContent.PAGE_EDIT_LENGTH - PAPER_MIXED_WIDE - 1));
            } else {
                text.append(wide(WritableBookContent.PAGE_EDIT_LENGTH));
            }
            result.add(text.toString());
        }
        return result;
    }

    private String wide(int count) {
        return String.valueOf(WIDE_CHARACTER).repeat(count);
    }

    // No ampersand. ColourCodes turns one in front of a code letter into a section sign that weighs
    // more and the layout has no byte to spare.
    private String narrow(int count) {
        StringBuilder text = new StringBuilder(count);
        while (text.length() < count) {
            int codePoint = random.nextInt(0x21, 0x7F);
            if (codePoint != FormatCodes.TYPED) {
                text.appendCodePoint(codePoint);
            }
        }
        return text.toString();
    }

    // Null when the file cannot be read. A message says why.
    private List<String> fromFile() {
        String name = file.getValue().strip();
        if (name.isEmpty()) {
            ChatUtil.error("BookBot needs the name of a file.");
            return null;
        }
        Path path = DataFiles.inside(DataFiles.path(), name);
        if (path == null) {
            ChatUtil.error("BookBot cannot use " + name + " as a file in the offlineclient folder.");
            return null;
        }
        Optional<String> text = DataFiles.readText(path, MOST_READ);
        if (text.isEmpty()) {
            ChatUtil.error("Could not read " + path.getFileName() + " from the offlineclient folder.");
            return null;
        }
        if (text.get().isBlank()) {
            ChatUtil.error(path.getFileName() + " is empty.");
            return null;
        }
        return wordWrap.isOn() ? wrapPages(text.get()) : cutPages(text.get());
    }

    // The game's own line breaking decides where each page ends.
    private List<String> wrapPages(String text) {
        List<FormattedText> lines = mc.font.splitIgnoringLanguage(Component.literal(text), BookEditScreen.TEXT_WIDTH);
        List<String> result = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int lineCount = 0;
        for (FormattedText line : lines) {
            if (!page.isEmpty()) {
                page.append('\n');
            }
            page.append(line.getString());
            if (++lineCount == BookText.PAGE_LINES) {
                result.add(page.toString());
                page.setLength(0);
                lineCount = 0;
                if (result.size() == WritableBookContent.MAX_PAGES) {
                    return result;
                }
            }
        }
        if (!page.isEmpty()) {
            result.add(page.toString());
        }
        return result;
    }

    // Each cut looks one character past the page to keep a character made of two halves whole.
    private static List<String> cutPages(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length() && result.size() < WritableBookContent.MAX_PAGES) {
            int end = Math.min(text.length(), start + WritableBookContent.PAGE_EDIT_LENGTH + 1);
            String page = BookLimits.cutPage(text.substring(start, end));
            result.add(page);
            start += page.length();
        }
        return result;
    }

    // The packet refuses a longer title and the connection drops with it. Paper kicks a player for
    // pages past its allowance and those are left off the end.
    private void send(List<String> text) {
        List<String> pages = BookLimits.fit(text, ServerInfo.runsPaper());
        int left = text.size() - pages.size();
        if (left > 0 && !toldLeftOff) {
            toldLeftOff = true;
            tell("left the last " + Tally.counted(left, "page")
                + " off. Paper kicks a player for a book any heavier.");
        }
        String count = number.isOn() ? " " + (written + 1) : "";
        String base = title.getValue();
        int room = WrittenBookContent.TITLE_MAX_LENGTH - count.length();
        String name = (base.length() > room ? base.substring(0, room) : base) + count;
        Optional<String> signed = sign.isOn() ? Optional.of(name) : Optional.empty();
        awaiting = InventoryUtil.selectedSlot();
        awaitTicks = 0;
        mc.player.connection.send(new ServerboundEditBookPacket(awaiting, pages, signed));
        written++;
    }
}
