package com.jellypudding.offlineclient.mixin;

import com.jellypudding.offlineclient.gui.BookButtons;
import com.jellypudding.offlineclient.modules.misc.Conceal;
import com.jellypudding.offlineclient.util.BookText;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.WritableBookContent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// The server keeps the colour codes of a page as they are sent.
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin extends Screen {

    @Shadow
    @Final
    private List<String> pages;

    @Shadow
    private int currentPage;

    @Shadow
    private MultiLineEditBox page;

    @Shadow
    protected abstract void pageBack();

    @Shadow
    protected abstract void updatePageContent();

    @Shadow
    protected abstract void updateButtonVisibility();

    private BookEditScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        if (Conceal.hides(Conceal.Part.SCREENS)) {
            return;
        }
        addRenderableWidget(BookButtons.copy(this, () -> pages));
        addRenderableWidget(BookButtons.paste(this, this::offlineclient$paste));
        BookButtons.palette(this, this::offlineclient$insert).forEach(this::addRenderableWidget);
    }

    @Unique
    private String offlineclient$paste() {
        BookText.Pasted pasted = BookText.paste(pages, currentPage, minecraft.keyboardHandler.getClipboard(),
            BookButtons.wrap(font));
        if (pasted.taken() == 0) {
            return pasted.offered() == 0 ? "Nothing" : "No room";
        }
        pages.clear();
        pages.addAll(pasted.pages());
        currentPage = pasted.first();
        updatePageContent();
        updateButtonVisibility();
        return pasted.taken() < pasted.offered() ? Tally.counted(pasted.taken(), "page") : "Pasted";
    }

    // The box never takes a section sign that is typed or pasted into it and turns down a value
    // with more lines than a page holds.
    @Unique
    private boolean offlineclient$insert(ChatFormatting format) {
        String code = format.toString();
        String text = page.getValue();
        int caret = Math.min(page.textField.cursor(), text.length());
        if (text.length() + code.length() > WritableBookContent.PAGE_EDIT_LENGTH) {
            return false;
        }
        String wanted = text.substring(0, caret) + code + text.substring(caret);
        page.setValue(wanted);
        if (!page.getValue().equals(wanted)) {
            return false;
        }
        page.textField.seekCursor(Whence.ABSOLUTE, caret + code.length());
        return true;
    }

    // It never adds a page the way the arrow does.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || Modules.dormant()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (scrollY > 0) {
            pageBack();
        } else if (currentPage < pages.size() - 1) {
            currentPage++;
            updatePageContent();
            updateButtonVisibility();
        }
        return true;
    }
}
