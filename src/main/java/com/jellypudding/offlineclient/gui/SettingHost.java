package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.PickList;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

import java.util.function.Consumer;

// Typing into a setting and listening for a bind. Any screen that draws
// settings keeps one of these and hands it the keys it does not want.
public final class SettingHost implements SettingWidget.Host {

    private final Screen owner;
    private final Consumer<String> tooltips;
    private final TextField editField = new TextField();

    private KeybindSetting bindingTarget;
    private Setting<?> editingSetting;

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
    public boolean isEditing(Setting<?> setting) {
        return editingSetting == setting;
    }

    @Override
    public TextField getEditField() {
        return editField;
    }

    // True whilst a field is taking characters. A bind listens for raw keys
    // and wants none.
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
        }
        cancelEditing();
    }

    private void cancelEditing() {
        editingSetting = null;
        editField.clear();
    }

    // Every click starts by putting away whatever was open.
    public void beginClick() {
        commitEditing();
        bindingTarget = null;
    }

    // True when the key belonged to a field or a bind.
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
        int bind = KeybindSetting.fromPress(key);
        if (bind != KeybindSetting.UNKNOWN) {
            bindingTarget.setValue(bind);
        }
        bindingTarget = null;
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        // Only a key that types a character has one to swallow.
        eatNextChar = KeybindSetting.typesCharacter(key);
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
        return editingSetting instanceof NumberSetting ? TextField.NUMBER : TextField.ANY;
    }
}
