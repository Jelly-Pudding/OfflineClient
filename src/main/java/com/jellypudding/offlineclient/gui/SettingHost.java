package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.PickList;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.KeyUse;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import java.util.function.Consumer;

public final class SettingHost implements SettingWidget.Host {

    private static final TextField.Filter HEX_CODE =
        c -> ColorSetting.HEX_MARK.indexOf(c) >= 0 || Character.digit(c, 16) >= 0;

    private final Screen owner;
    private final Consumer<String> tooltips;
    private final TextField editField = new TextField();

    private final BindCapture capture = new BindCapture();
    private KeybindSetting bindingTarget;
    private Setting<?> editingSetting;
    // A colour is only read back from a code the player edited.
    private String editingFrom = "";

    // The character of the key just bound arrives right after the key event
    // and has to be eaten.
    private boolean eatNextChar;

    public SettingHost(Screen owner, Consumer<String> tooltips) {
        this.owner = owner;
        this.tooltips = tooltips;
    }

    @Override
    public void setTooltip(String text) {
        tooltips.accept(text);
    }

    @Override
    public boolean isBinding(KeybindSetting setting) {
        return bindingTarget == setting;
    }

    @Override
    public void startListening(KeybindSetting setting) {
        bindingTarget = setting;
        capture.begin();
    }

    @Override
    public void openPicker(PickList<?> setting) {
        commitEditing();
        openPickerTyped(setting);
    }

    private <T> void openPickerTyped(PickList<T> setting) {
        OfflineClient.MC.gui.setScreen(new ListPickerScreen<>(owner, setting));
    }

    @Override
    public void startEditing(NumberSetting setting) {
        commitEditing();
        editingSetting = setting;
        editField.set(setting.getValueString().replaceAll("[^0-9.-]", ""));
        editField.selectAll();
    }

    @Override
    public void startEditing(TextSetting setting) {
        commitEditing();
        editingSetting = setting;
        editField.set(setting.getValue());
    }

    @Override
    public void startEditing(ColorSetting setting) {
        commitEditing();
        editingSetting = setting;
        editingFrom = setting.hex();
        editField.set(editingFrom);
        editField.selectAll();
    }

    @Override
    public boolean isEditing(Setting<?> setting) {
        return editingSetting == setting;
    }

    @Override
    public TextField getEditField() {
        return editField;
    }

    // A bind listens for raw keys and wants none.
    public boolean isEditing() {
        return editingSetting != null;
    }

    public boolean isListening() {
        return bindingTarget != null;
    }

    public void commitEditing() {
        if (editingSetting instanceof NumberSetting number) {
            if (!editField.isEmpty()) {
                try {
                    number.setValue(Double.parseDouble(editField.get()));
                    OfflineClient.INSTANCE.getConfigManager().saveSoon();
                } catch (NumberFormatException e) {
                    ChatUtil.error(editField.get() + " is not a number.");
                }
            }
        } else if (editingSetting instanceof TextSetting text) {
            text.setValue(editField.get().trim());
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        } else if (editingSetting instanceof ColorSetting colour && !editField.get().equals(editingFrom)) {
            if (colour.setHex(editField.get())) {
                OfflineClient.INSTANCE.getConfigManager().saveSoon();
            } else if (!editField.isEmpty()) {
                ChatUtil.error(editField.get() + " is not a colour. Type six hex digits such as "
                    + ColorSetting.HEX_MARK + "33CCFF.");
            }
        }
        cancelEditing();
    }

    private void cancelEditing() {
        editingSetting = null;
        editField.clear();
    }

    public void beginClick() {
        commitEditing();
        bindingTarget = null;
    }

    // A screen asks before it does anything else with the click.
    public boolean takeClick(int button) {
        if (bindingTarget == null || !capture.mouseClicked(button, this::bind)) {
            return false;
        }
        bindingTarget = null;
        return true;
    }

    public boolean keyReleased(KeyEvent event) {
        if (bindingTarget == null || !capture.keyReleased(event, this::bind)) {
            return false;
        }
        bindingTarget = null;
        return true;
    }

    private void bind(Bind bind) {
        bindingTarget.setValue(bind);
        KeyUse.warn(bind, bindingTarget);
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        // Only a key that types a character has one to swallow.
        eatNextChar = bind.typesCharacter();
    }

    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        eatNextChar = false;
        if (editingSetting != null) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                commitEditing();
            } else if (key == InputConstants.KEY_ESCAPE) {
                cancelEditing();
            } else {
                editField.keyPressed(event, filter());
            }
            return true;
        }
        if (bindingTarget == null) {
            return false;
        }
        if (capture.keyPressed(event, this::bind)) {
            bindingTarget = null;
        }
        return true;
    }

    public boolean charTyped(char typed) {
        if (eatNextChar) {
            eatNextChar = false;
            return true;
        }
        if (bindingTarget != null) {
            return true;
        }
        if (editingSetting == null) {
            return false;
        }
        editField.charTyped(typed, filter());
        return true;
    }

    private TextField.Filter filter() {
        return switch (editingSetting) {
            case NumberSetting ignored -> TextField.NUMBER;
            case ColorSetting ignored -> HEX_CODE;
            case null, default -> TextField.ANY;
        };
    }
}
