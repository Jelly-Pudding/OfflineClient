package com.jellypudding.offlineclient.rig.core;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.module.ModuleManager;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.ArrayList;
import java.util.List;

// The client as a player reaches it. Modules are switched through the module manager and
// settings and commands go through the command manager as typed lines do.
public final class Client {

    // The colour code the client starts a refusal with.
    private static final String ERROR_MARK = "§c";
    private static final String COLOUR_CODE = "§.";
    private static final int COMMAND_TICKS = 2;

    private final Rig rig;

    Client(Rig rig) {
        this.rig = rig;
    }

    private static ModuleManager manager() {
        return OfflineClient.INSTANCE.getModuleManager();
    }

    public List<Module> modules() {
        return rig.ask(mc -> manager().getAll());
    }

    public Module module(String name) {
        Module module = rig.ask(mc -> manager().get(name));
        if (module == null) {
            throw new RigFailure("The client has no module called " + name);
        }
        return module;
    }

    public void enable(String name) {
        switchTo(module(name), true);
    }

    public void disable(String name) {
        switchTo(module(name), false);
    }

    public void switchTo(Module module, boolean on) {
        rig.act(mc -> module.setEnabled(on));
    }

    public void press(String name) {
        Module module = module(name);
        // A module that is no switch does its work on its key. A control run keeps that back too.
        if (!rig.control()) {
            rig.act(mc -> module.onKeybind());
        }
    }

    public boolean isOn(String name) {
        Module module = module(name);
        return rig.ask(mc -> module.isEnabled());
    }

    // The command is typed without its prefix. Hands back what the client wrote in reply with the
    // colour codes taken out.
    public List<String> command(String line) {
        int mark = ChatTap.mark();
        rig.act(mc -> {
            CommandManager commands = OfflineClient.INSTANCE.getCommandManager();
            if (!commands.run(commands.getPrefix() + line)) {
                throw new RigFailure("The client did not take " + line + " as a command");
            }
        });
        rig.ticks(COMMAND_TICKS);
        return plain(ChatTap.since(mark));
    }

    public void say(String line) {
        rig.act(mc -> {
            ChatScreen box = new ChatScreen("", false);
            mc.gui.setScreen(box);
            box.handleChatInput(line, false);
            mc.gui.setScreen(null);
        });
    }

    // Changes a setting the way the set command does and fails when the client refuses.
    public void set(String module, String setting, String value) {
        Module owner = module(module);
        if (rig.ask(mc -> owner.getSetting(setting)) == null) {
            throw new RigFailure(module + " has no setting called " + setting);
        }
        int mark = ChatTap.mark();
        command("set " + Setting.idFor(module) + " " + Setting.idFor(setting) + " " + value);
        for (String reply : ChatTap.since(mark)) {
            if (reply.contains(ERROR_MARK)) {
                throw new RigFailure("The client refused to set " + module + " " + setting + " to " + value + " with "
                    + reply.replaceAll(COLOUR_CODE, ""));
            }
        }
    }

    public String valueOf(String module, String setting) {
        Module owner = module(module);
        return rig.ask(mc -> {
            Setting<?> found = owner.getSetting(setting);
            if (found == null) {
                throw new RigFailure(module + " has no setting called " + setting);
            }
            return found.getValueString();
        });
    }

    public int chatMark() {
        return ChatTap.mark();
    }

    public List<String> saidSince(int mark) {
        return plain(ChatTap.since(mark));
    }

    private static List<String> plain(List<String> lines) {
        List<String> plain = new ArrayList<>();
        for (String line : lines) {
            plain.add(line.replaceAll(COLOUR_CODE, ""));
        }
        return plain;
    }

    // Puts every module and setting back to how a first run starts. One case never leaks into the next.
    public void restoreDefaults() {
        rig.act(mc -> {
            for (Module module : manager().getAll()) {
                for (Setting<?> setting : module.allSettings()) {
                    if (setting.hasDefault() && !setting.isDefault()) {
                        setting.reset();
                    }
                }
                module.getKeybind().reset();
                if (module.isTogglable() && module.isEnabled() != module.enabledByDefault()) {
                    module.setEnabled(module.enabledByDefault());
                }
            }
        });
    }
}
