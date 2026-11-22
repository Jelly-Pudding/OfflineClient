package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.ServerSpoof;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

// Reads a text the way the game would with no mod in it. A server can show the client a text made of
// translation keys and key bind names and have it sent back as plain words. Words only a mod could
// have supplied tell it the mod is there. The game's own keys and key binds still resolve. A client
// that left those alone would stand out just as much.
public final class UnmoddedText {

    // Where the game itself gets packs from. A pack from anywhere else was put there by a mod.
    private static final Set<PackSource> OWN_SOURCES = Set.of(PackSource.DEFAULT, PackSource.BUILT_IN,
        PackSource.FEATURE, PackSource.WORLD, PackSource.SERVER);

    private static final String LANGUAGE_FILE = "lang/%s.json";

    // The table is built again whenever the game swaps its own after a reload.
    private static Language builtFor;
    private static Language plain;
    private static Set<String> ownBinds;

    private UnmoddedText() {
    }

    public static String flatten(Component text) {
        return new Reader(UnmoddedText::plain, UnmoddedText::isOwnBind).read(text);
    }

    public static boolean asksForMods(Component text) {
        boolean asks = switch (text.getContents()) {
            case TranslatableContents translation -> onlyAModKnows(translation.getKey())
                || Stream.of(translation.getArgs()).anyMatch(arg -> arg instanceof Component part && asksForMods(part));
            case KeybindContents keybind -> !isOwnBind(keybind.getName()) && KeyMapping.get(keybind.getName()) != null;
            default -> false;
        };
        return asks || text.getSiblings().stream().anyMatch(UnmoddedText::asksForMods);
    }

    private static boolean onlyAModKnows(String key) {
        return Language.getInstance().has(key) && !plain().has(key);
    }

    // The name of an item as the anvil reads it on its way back to the server. Whilst ServerSpoof
    // hides mod text it holds the words a client without mods reads.
    public static Component anvilName(ItemStack stack) {
        Component name = wholeName(stack);
        String plainWords = ServerSpoof.plainWords(name);
        return plainWords == null ? name : Component.literal(plainWords);
    }

    // The name the way the game puts it together. AntiCrash cuts the name the game draws and a cut
    // name is words already read with the translations of every mod.
    static Component wholeName(ItemStack stack) {
        Component custom = stack.getCustomName();
        return custom == null ? stack.getItemName() : custom;
    }

    // The table is only asked for once the text names a key. Plain words never build it.
    record Reader(Supplier<Language> plain, Predicate<String> ownBind) {

        String read(Component text) {
            StringBuilder words = new StringBuilder();
            append(words, text);
            return words.toString();
        }

        // The contents come first and the siblings after them in the order the game reads a text.
        private void append(StringBuilder words, Component text) {
            switch (text.getContents()) {
                case TranslatableContents translation -> words.append(translated(translation));
                case KeybindContents keybind -> append(words, bound(keybind.getName()));
                case ObjectContents object when object.fallback().isPresent() ->
                    append(words, object.fallback().get());
                case ComponentContents other -> other.visit(part -> {
                    words.append(part);
                    return Optional.empty();
                });
            }
            for (Component sibling : text.getSiblings()) {
                append(words, sibling);
            }
        }

        // The game fills a template in itself when it is the fallback of a key it does not know.
        private String translated(TranslatableContents translation) {
            String fallback = translation.getFallback();
            String template = plain.get().getOrDefault(translation.getKey(),
                fallback == null ? translation.getKey() : fallback);
            Object[] args = translation.getArgs().clone();
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Component arg) {
                    args[i] = Component.literal(read(arg));
                }
            }
            return Component.translatableWithFallback(unknownKey(), template, args).getString();
        }

        // A key bind of the game itself reads as the key it sits on. Any other name reads as a
        // translation key the way it does in a game where nothing made such a bind.
        private Component bound(String name) {
            KeyMapping mapping = ownBind.test(name) ? KeyMapping.get(name) : null;
            return mapping == null ? Component.translatable(name) : mapping.getTranslatedKeyMessage();
        }
    }

    private static String unknownKey() {
        Language real = Language.getInstance();
        String key = "";
        while (real.has(key)) {
            key += " ";
        }
        return key;
    }

    private static Language plain() {
        Language real = Language.getInstance();
        if (real != builtFor) {
            Minecraft mc = OfflineClient.MC;
            plain = load(mc.getResourceManager().listPacks(), mc.getLanguageManager().getSelected());
            builtFor = real;
        }
        return plain;
    }

    // The game builds the table itself from the packs it would hold without mods. The packs stay
    // open because the game still reads from them.
    static Language load(Stream<PackResources> packs, String selected) {
        ResourceManager own = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
            packs.filter(pack -> OWN_SOURCES.contains(pack.location().source())).toList());
        boolean translated = !selected.equals(Language.DEFAULT) && carries(own, selected);
        return ClientLanguage.loadFrom(own,
            translated ? List.of(Language.DEFAULT, selected) : List.of(Language.DEFAULT), false);
    }

    // A language that only a mod brought has no file left and the game refuses to load such a one.
    private static boolean carries(ResourceManager packs, String language) {
        String file = String.format(LANGUAGE_FILE, language);
        return packs.getNamespaces().stream().anyMatch(
            namespace -> !packs.getResourceStack(Identifier.fromNamespaceAndPath(namespace, file)).isEmpty());
    }

    private static boolean isOwnBind(String name) {
        if (ownBinds == null) {
            ownBinds = keysOf(OfflineClient.MC.getVanillaPackResources().fullResources());
        }
        return ownBinds.contains(name);
    }

    // The language file inside the game jar names every key bind the game makes. No pack a server
    // sends can add to it the way it can add to the table above.
    static Set<String> keysOf(PackResources game) {
        Set<String> keys = new HashSet<>();
        IoSupplier<InputStream> file = game.getResource(PackType.CLIENT_RESOURCES,
            Identifier.withDefaultNamespace(String.format(LANGUAGE_FILE, Language.DEFAULT)));
        if (file == null) {
            return keys;
        }
        try (InputStream in = file.get()) {
            Language.loadFromJson(in, (key, words) -> keys.add(key));
        } catch (IOException e) {
            OfflineClient.LOG.warn("Could not read the language file of the game", e);
        }
        return keys;
    }
}
