package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.util.Modules;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

// The client's smooth font. The game's own TrueType loader reads the file and the game's own glyph
// sheets hold the letters. A sheet is drawn without smoothing and a letter is only sharp whilst each
// of its pixels lands on one pixel of the screen. A size is therefore made for every whole number of
// screen pixels to a GUI pixel and kept whilst something draws with it. A letter the file lacks is
// drawn by the game's own font.
final class SmoothFont {

    // Letters stay well inside the game's glyph sheets of 256 pixels a side up to this. A denser
    // screen shows them a little enlarged.
    private static final int MOST_PIXELS = 16;
    // A size nothing has drawn with for this many ticks is let go.
    private static final int IDLE_TICKS = 200;
    private static final String SHEETS = "smooth_font/";

    private final BooleanSupplier wanted;
    private final SmoothFontFile file = new SmoothFontFile();
    private final Int2ObjectMap<Size> sizes = new Int2ObjectOpenHashMap<>();
    // Let go of during a frame. Closed at the next tick once nothing draws with them.
    private final List<Size> retired = new ArrayList<>();
    // The bytes the sizes were made from.
    private SmoothFontFile.Source madeFrom;

    private boolean listening;
    private int ticks;
    private int sheetsMade;

    SmoothFont(BooleanSupplier wanted) {
        this.wanted = wanted;
    }

    // The font for this many screen pixels to a GUI pixel. The game's own whilst the file is
    // being read or no smooth font can be used.
    Font at(int pixels) {
        listen();
        SmoothFontFile.Source source = follow();
        if (source == null) {
            file.look();
            return OfflineClient.MC.font;
        }
        int scale = Math.clamp(pixels, 1, MOST_PIXELS);
        Size size = sizes.get(scale);
        if (size == null) {
            size = open(source, scale);
            if (size == null) {
                return OfflineClient.MC.font;
            }
            sizes.put(scale, size);
        }
        size.lastUse = ticks;
        return size.font;
    }

    // The bytes in use. Sizes made from other bytes are let go and whatever already drew with them
    // this frame finishes before the next tick closes them.
    private SmoothFontFile.Source follow() {
        SmoothFontFile.Source source = file.current();
        if (source != madeFrom) {
            retired.addAll(sizes.values());
            sizes.clear();
            madeFrom = source;
        }
        return source;
    }

    private Size open(SmoothFontFile.Source from, int scale) {
        try {
            return new Size(from, scale, Identifier.fromNamespaceAndPath("offlineclient", SHEETS + sheetsMade++));
        } catch (IOException | RuntimeException | LinkageError e) {
            file.failed(from, e);
            return null;
        }
    }

    private void listen() {
        if (!listening) {
            listening = true;
            OfflineClient.INSTANCE.getEventBus().register(this);
        }
    }

    @Subscribe
    private void onTick(ClientTickEvent event) {
        ticks++;
        retired.forEach(Size::close);
        retired.clear();
        if (!wanted.getAsBoolean()) {
            release();
            return;
        }
        follow();
        sizes.values().removeIf(size -> {
            boolean idle = ticks - size.lastUse > IDLE_TICKS;
            if (idle) {
                size.close();
            }
            return idle;
        });
        // A sleeping client reads no file and says nothing until it wakes.
        if (!Modules.dormant()) {
            file.tick();
        }
    }

    // Both switches are off. The next use reads the files afresh and tries again what failed.
    private void release() {
        sizes.values().forEach(Size::close);
        sizes.clear();
        madeFrom = null;
        file.release();
        listening = false;
        OfflineClient.INSTANCE.getEventBus().unregister(this);
    }

    // The game's own glyphs and effects. Asked for each time and never kept. A resource reload
    // replaces the glyphs behind them.
    private static Font.Provider game() {
        return OfflineClient.MC.font.provider;
    }

    // The font made for one number of screen pixels to a GUI pixel. It answers for the game's default
    // font and passes every other font a piece of text may name to the game.
    private final class Size implements Font.Provider, GlyphSource, UnbakedGlyph.Stitcher {

        private final SmoothFontFile.Source from;
        private final GlyphProvider letters;
        private final GlyphStitcher sheets;
        // Half a GUI pixel on whole screen pixels.
        private final float offset;
        private final Int2ObjectMap<BakedGlyph> made = new Int2ObjectOpenHashMap<>();
        // Codepoints the file has no letter for. The game's font draws those.
        private final IntSet lacking = new IntOpenHashSet();
        private final Font font;
        private BakedGlyph missing;
        private boolean broken;
        private int lastUse;

        private Size(SmoothFontFile.Source from, int scale, Identifier sheetName) throws IOException {
            this.from = from;
            letters = SmoothFontFile.load(from.bytes(), scale);
            sheets = new GlyphStitcher(OfflineClient.MC.getTextureManager(), sheetName);
            offset = Math.max(1, Math.round(scale / 2f)) / (float) scale;
            font = new Font(this);
            lastUse = ticks;
        }

        @Override
        public GlyphSource glyphs(FontDescription description) {
            return FontDescription.DEFAULT.equals(description) ? this : game().glyphs(description);
        }

        @Override
        public EffectGlyph effect() {
            return game().effect();
        }

        @Override
        public BakedGlyph getGlyph(int codepoint) {
            BakedGlyph glyph = made.get(codepoint);
            if (glyph != null) {
                return glyph;
            }
            if (!broken && !lacking.contains(codepoint)) {
                glyph = make(codepoint);
                if (glyph != null) {
                    made.put(codepoint, glyph);
                    return glyph;
                }
                lacking.add(codepoint);
            }
            return game().glyphs(FontDescription.DEFAULT).getGlyph(codepoint);
        }

        // Null when the file has no letter for it or the letter is too big for a sheet.
        private BakedGlyph make(int codepoint) {
            try {
                UnbakedGlyph unbaked = letters.getGlyph(codepoint);
                BakedGlyph baked = unbaked == null ? null : unbaked.bake(this);
                return baked == null ? null : new Letter(baked, offset);
            } catch (RuntimeException e) {
                broken = true;
                file.failed(from, e);
                return null;
            }
        }

        // Scrambled text takes the game's letters. Nobody reads it.
        @Override
        public BakedGlyph getRandomGlyph(RandomSource random, int width) {
            return game().glyphs(FontDescription.DEFAULT).getRandomGlyph(random, width);
        }

        @Override
        public BakedGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
            return sheets.stitch(info, bitmap);
        }

        @Override
        public BakedGlyph getMissing() {
            if (missing == null) {
                missing = SpecialGlyphs.MISSING.bake(sheets);
            }
            return missing;
        }

        private void close() {
            sheets.close();
            letters.close();
        }
    }

    // A letter of the smooth font. Its shadow and its bold copy sit half a GUI pixel off like those of
    // the game's own fine unifont letters. A whole pixel suits blocky letters and smears thin smooth ones.
    private record Letter(BakedGlyph glyph, float offset) implements BakedGlyph, GlyphInfo {

        @Override
        public GlyphInfo info() {
            return this;
        }

        @Override
        public float getAdvance() {
            return glyph.info().getAdvance();
        }

        @Override
        public float getBoldOffset() {
            return offset;
        }

        @Override
        public float getShadowOffset() {
            return offset;
        }

        @Override
        public TextRenderable.Styled createGlyph(float x, float y, int color, int shadowColor, Style style,
                                                 float boldOffset, float shadowOffset) {
            return glyph.createGlyph(x, y, color, shadowColor, style, boldOffset, shadowOffset);
        }
    }
}
