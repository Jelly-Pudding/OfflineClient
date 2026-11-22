package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.gui.FindsScreen;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BookLimits;
import com.jellypudding.offlineclient.util.BookPace;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.LoadedChunks;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.TextCut;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Held items and the items of frames and dropped stacks arrive with their pages. Paper only hides
// the pages of what other players carry when its item obfuscation is on and never those of frames
// and dropped stacks.
public final class BookReader extends Module implements FindSource {

    // Where says who held it or what showed it. A book and quill has no title and no author.
    private record Book(boolean signed, String title, String author, String where, List<String> pages) {

        // A server can send pages far longer than a book shows and they are cut.
        private static Book of(ItemStack stack, String where) {
            WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (written != null) {
                List<String> pages = new ArrayList<>();
                for (Component page : written.getPages(false)) {
                    if (pages.size() == WritableBookContent.MAX_PAGES) {
                        break;
                    }
                    pages.add(BookLimits.cutPage(page.getString()));
                }
                return new Book(true, TextCut.cut(written.title().raw(), MAX_NAME),
                    TextCut.cut(written.author(), MAX_NAME), where, List.copyOf(pages));
            }
            WritableBookContent writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (writable == null || writable.pages().isEmpty()) {
                return null;
            }
            List<String> pages = writable.getPages(false).limit(WritableBookContent.MAX_PAGES)
                .map(BookLimits::cutPage).toList();
            return new Book(false, "", "", where, pages);
        }

        private static Book of(FindLog.Find find) {
            List<String> content = find.content();
            if (content.size() < HEADER) {
                return null;
            }
            return new Book(find.kind().equals(WRITTEN), content.get(0), content.get(1), content.get(2),
                content.subList(HEADER, content.size()));
        }

        private List<String> content() {
            List<String> content = new ArrayList<>(HEADER + pages.size());
            content.add(title);
            content.add(author);
            content.add(where);
            content.addAll(pages);
            return content;
        }

        // The same book seen anywhere else is the same book.
        private List<String> text() {
            List<String> text = new ArrayList<>(pages);
            text.add(title);
            text.add(author);
            return text;
        }

        private String id() {
            return Integer.toHexString(text().hashCode());
        }

        private int size() {
            return pages.stream().mapToInt(String::length).sum();
        }

        private String name() {
            return signed ? title + " by " + author : "a book and quill";
        }

        private String words() {
            return signed ? "the written book §f" + title + " §7by §f" + author : "a book and quill";
        }

        private String detail() {
            String first = pages.isEmpty() ? "" : pages.getFirst().replace('\n', ' ').strip();
            return name() + SEPARATOR + Tally.counted(pages.size(), "page")
                + (first.isEmpty() ? "" : SEPARATOR + TextCut.quote(first, QUOTE));
        }
    }

    // The entity is null for a book on a shelf.
    private record Sighting(Entity entity, BlockPos shelf, Book book) {

        private AABB box(float partialTicks) {
            return entity == null ? DrawBatch.blockBox(shelf) : EntityUtil.markBox(entity, partialTicks);
        }
    }

    private static final String WRITTEN = "book";
    private static final String QUILL = "book and quill";
    // The title and the author and where the book was come before its pages.
    private static final int HEADER = 3;
    // Longer than any title or name the game lets a player give.
    private static final int MAX_NAME = 64;
    private static final int QUOTE = 40;
    private static final String SEPARATOR = " · ";

    private static final int SCAN_TICKS = 10;
    // A server that shows endless books fills neither memory nor the finds file.
    private static final long KEPT_TEXT = 2_000_000;
    // It only bounds the memory.
    private static final int KEPT_TOLD = 4096;
    // A shelf holds three books and a few can lie on one block. Each takes the next free block up.
    private static final int SPOTS_UP = 8;

    private static final double LABEL_LIFT = 0.3;
    private static final float BROWN = 30;

    private final BoolSetting players = new BoolSetting("Players",
        "Reads the books other players hold.", true);
    private final BoolSetting displays = new BoolSetting("Displays",
        "Reads the books in item frames and on shelves and those armour stands and mannequins hold.", true);
    private final BoolSetting dropped = new BoolSetting("Dropped",
        "Reads the books lying on the ground.", true);
    private final EnumSetting<Notice.Where> messages = Notice.row(
        "Where it posts each new book with links that read it or copy it.", Notice.Where.CHAT);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final ColorSetting colour = new ColorSetting("Colour",
        "Colour of the boxes around the books in view.", BROWN, false);
    private final BoolSetting labels = new BoolSetting("Labels",
        "Writes the title of each book above it.", true);
    private final NumberSetting scale = new NumberSetting("Scale",
        "Size of the labels.", 1, 0.5, 3, 0.1).min(0.1).under(labels);
    private final FindLog finds = new FindLog(this);

    // The text of every book in the finds. Worked out again whenever the finds change in a way this
    // module did not.
    private final Set<List<String>> known = new HashSet<>();
    private long knownText;
    private int knownRevision = -1;
    // A book taken out of the finds by hand stays out until the next toggle.
    private final Set<List<String>> told = Collections.newSetFromMap(new BoundedMap<>(KEPT_TOLD));
    // What each stack in view held at the last scan. A stack the server did not replace is read once.
    private Map<ItemStack, Book> read = new IdentityHashMap<>();
    private List<Sighting> sightings = List.of();
    private final ChatWarning hidden = new ChatWarning();
    private final ChatWarning full = new ChatWarning();
    private final ServerWatch server = new ServerWatch();
    private final WorldWatch world = new WorldWatch();
    private int wait;

    public BookReader() {
        super("BookReader", "Reads the books other players hold or show or drop and copies them for you.",
            Category.HUNTING);
        addSettings(players, displays, dropped, messages);
        addSettings(style.settings());
        addSettings(colour, labels, scale);
        addSettings(FindsScreen.settingsFor(finds));
        searchTags("books", "written book", "book and quill", "read books", "copy book", "lore");
    }

    @Override
    public FindLog findLog() {
        return finds;
    }

    @Override
    public String getSuffix() {
        return count(sightings.size());
    }

    @Override
    protected void onEnable() {
        clear();
    }

    @Override
    protected void onDisable() {
        clear();
    }

    private void clear() {
        told.clear();
        read = new IdentityHashMap<>();
        sightings = List.of();
        hidden.clear();
        full.clear();
        world.forget();
        wait = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (server.changed()) {
            told.clear();
        }
        if (world.changed()) {
            read = new IdentityHashMap<>();
            sightings = List.of();
        }
        // A book judged before the saved finds are in would pass for new.
        if (!finds.ready() || --wait > 0) {
            return;
        }
        wait = SCAN_TICKS;
        scan();
    }

    private void scan() {
        Map<ItemStack, Book> now = new IdentityHashMap<>();
        List<Sighting> seen = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || Modules.isLocalBody(entity)) {
                continue;
            }
            switch (entity) {
                case ItemFrame frame when displays.isOn() -> look(frame, null, frame.getItem(), "in an item frame", now,
                    seen);
                case ItemEntity item when dropped.isOn() -> look(item, null, item.getItem(), "on the ground", now, seen);
                case Player player when players.isOn() -> lookInHands(player,
                    "held by " + EntityUtil.displayNameOf(player), now, seen);
                case ArmorStand stand when displays.isOn() -> lookInHands(stand, "held by an armour stand", now, seen);
                case Mannequin mannequin when displays.isOn() -> lookInHands(mannequin, "held by a mannequin", now,
                    seen);
                default -> {
                }
            }
        }
        if (displays.isOn()) {
            LoadedChunks.forEachBlockEntity(blockEntity -> {
                if (blockEntity instanceof ShelfBlockEntity shelf) {
                    for (ItemStack stack : shelf.getItems()) {
                        look(null, shelf.getBlockPos(), stack, "on a shelf", now, seen);
                    }
                }
            });
        }
        read = now;
        sightings = seen;
    }

    private void lookInHands(LivingEntity holder, String where, Map<ItemStack, Book> now, List<Sighting> seen) {
        for (EquipmentSlot hand : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
            ItemStack stack = holder.getItemBySlot(hand);
            look(holder, null, stack, where, now, seen);
            // A written book without its pages was emptied by the server on its way here.
            if (holder instanceof Player && stack.is(Items.WRITTEN_BOOK) && !stack.has(DataComponents.WRITTEN_BOOK_CONTENT)) {
                hidden.say("BookReader cannot read the books other players hold here. The server hides their pages.");
            }
        }
    }

    private void look(Entity entity, BlockPos shelf, ItemStack stack, String where, Map<ItemStack, Book> now,
                      List<Sighting> seen) {
        if (!stack.is(Items.WRITTEN_BOOK) && !stack.is(Items.WRITABLE_BOOK)) {
            return;
        }
        Book book = read.containsKey(stack) ? read.get(stack) : Book.of(stack, where);
        now.put(stack, book);
        if (book == null) {
            return;
        }
        seen.add(new Sighting(entity, shelf, book));
        log(book, entity == null ? shelf : entity.blockPosition());
    }

    private void log(Book book, BlockPos at) {
        List<String> text = book.text();
        if (told.contains(text)) {
            return;
        }
        if (known().contains(text)) {
            told.add(text);
            return;
        }
        if (knownText + book.size() > KEPT_TEXT) {
            full.say("BookReader keeps no more books on this server. Clear some of its finds to make room.");
            return;
        }
        BlockPos pos = freeSpot(at);
        if (pos == null || !finds.add(pos, book.signed() ? WRITTEN : QUILL, book.detail(), book.content())) {
            return;
        }
        // The one find just added is all that changed since the books were counted.
        known.add(text);
        knownText += book.size();
        knownRevision = finds.revision();
        told.add(text);
        full.clear();
        tell(book, pos);
    }

    private Set<List<String>> known() {
        if (finds.revision() != knownRevision) {
            known.clear();
            knownText = 0;
            for (FindLog.Find find : finds.all()) {
                Book kept = Book.of(find);
                if (kept != null) {
                    known.add(kept.text());
                    knownText += kept.size();
                }
            }
            knownRevision = finds.revision();
        }
        return known;
    }

    private BlockPos freeSpot(BlockPos pos) {
        for (int up = 0; up < SPOTS_UP; up++) {
            if (finds.at(pos.above(up)) == null) {
                return pos.above(up);
            }
        }
        return null;
    }

    private void tell(Book book, BlockPos pos) {
        String id = book.id();
        Notice.post(messages, this, Component.literal("§7found " + book.words() + " §7" + book.where() + " at ")
            .append(ChatUtil.walkLink(pos)).append("§7. ")
            .append(ChatUtil.commandLink("§8[§7read§8]", "book read " + id, "Click to read it"))
            .append(" ")
            .append(ChatUtil.commandLink("§8[§7copy§8]", "book copy " + id,
                "Click to copy it into an empty book and quill you carry")));
    }

    private Book bookById(String id) {
        for (FindLog.Find find : finds.all()) {
            Book book = Book.of(find);
            if (book != null && book.id().equals(id)) {
                return book;
            }
        }
        return null;
    }

    // Null when it opened and else why not.
    public String read(String id) {
        Book book = bookById(id);
        if (book == null) {
            return "No book of that id is in the finds of this server.";
        }
        List<Component> pages = new ArrayList<>(book.pages().size());
        book.pages().forEach(page -> pages.add(Component.literal(page)));
        mc.schedule(() -> mc.gui.setScreen(new BookViewScreen(new BookViewScreen.BookAccess(pages))));
        return null;
    }

    // What the edit cannot carry is left off the end. Hands back what happened.
    public String copy(String id) {
        if (mc.player == null) {
            return "Join a world first.";
        }
        Book book = bookById(id);
        if (book == null) {
            return "No book of that id is in the finds of this server.";
        }
        int slot = emptyQuill();
        if (slot == Inventory.NOT_FOUND_INDEX) {
            return "Put an empty book and quill in your hotbar or offhand first.";
        }
        if (!BookPace.INSTANCE.ready()) {
            return "Wait a moment after the last book edit. The server kicks a player who edits books too fast.";
        }
        List<String> pages = new ArrayList<>(book.pages().size() + 1);
        pages.add(cover(book));
        pages.addAll(book.pages());
        List<String> fitted = BookLimits.fit(pages, ServerInfo.runsPaper());
        mc.player.connection.send(new ServerboundEditBookPacket(slot, fitted, Optional.empty()));
        int left = pages.size() - fitted.size();
        String copied = "Copied " + book.name() + " into the book and quill in "
            + (slot == Inventory.SLOT_OFFHAND ? "your offhand" : "hotbar slot " + (slot + 1)) + ".";
        return left == 0 ? copied : copied + " The last " + Tally.counted(left, "page") + " did not fit in one book.";
    }

    // The server only writes a book in the hotbar or the offhand.
    private int emptyQuill() {
        Inventory inventory = mc.player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (emptyQuill(inventory.getItem(selected))) {
            return selected;
        }
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            if (emptyQuill(inventory.getItem(i))) {
                return i;
            }
        }
        return emptyQuill(inventory.getItem(Inventory.SLOT_OFFHAND)) ? Inventory.SLOT_OFFHAND
            : Inventory.NOT_FOUND_INDEX;
    }

    private static boolean emptyQuill(ItemStack stack) {
        WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        return stack.is(Items.WRITABLE_BOOK) && (content == null || content.pages().isEmpty());
    }

    private static String cover(Book book) {
        String what = book.signed() ? book.title() + "\nby " + book.author() : "a book and quill";
        return "Copy of\n" + what + "\n\n" + book.where();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || sightings.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        int shade = colour.getColor();
        for (Sighting sighting : sightings) {
            if (sighting.entity() == null || !sighting.entity().isRemoved()) {
                style.draw(batch, sighting.box(event.getPartialTicks()), shade, true);
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!labels.isOn() || !inGame() || sightings.isEmpty() || !WorldToScreen.update()) {
            return;
        }
        for (Sighting sighting : sightings) {
            if (sighting.entity() != null && sighting.entity().isRemoved()) {
                continue;
            }
            AABB box = sighting.box(event.getPartialTicks());
            Vec3 screen = WorldToScreen.project(new Vec3((box.minX + box.maxX) / 2, box.maxY + LABEL_LIFT,
                (box.minZ + box.maxZ) / 2));
            if (screen != null) {
                RenderUtil.label(event.getContext(), mc.font, screen.x, screen.y, scale.getFloat(),
                    List.of(ChatUtil.capitalised(sighting.book().name())), List.of(colour.getColor()));
            }
        }
    }
}
