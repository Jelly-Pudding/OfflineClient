package com.jellypudding.offlineclient.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

public final class Shortcuts {

    public record Pair(String typed, String sent) {
    }

    // Commands that carry a password or a code on a server that asks its players to log in. A
    // line that starts with one goes out exactly as it was typed.
    private static final Set<String> PASSWORD_COMMANDS = Set.of("login", "log", "l", "register", "reg",
        "changepassword", "changepass", "changepw", "cp", "setpassword", "setpw", "password", "passwd", "pass",
        "pw", "unregister", "unreg", "email", "premium", "2fa", "2faconfirm", "totp", "pin", "captcha", "verify",
        "authme", "nlogin");

    private Shortcuts() {
    }

    // A shortcut counts whatever its case and only where it stands apart. What a swap puts in is
    // never searched again.
    public static String swap(String text, List<Pair> pairs) {
        return swap(text, pairs, kept -> false);
    }

    // A word that is the name of a player stays as it is because the command may act on that
    // player. A command that carries a password is left alone.
    public static String swapInCommand(String command, List<Pair> pairs, Predicate<String> isPlayer) {
        int space = command.indexOf(' ');
        if (space == -1 || carriesPassword(command)) {
            return command;
        }
        return command.substring(0, space) + swap(command.substring(space), pairs, isPlayer);
    }

    private static String swap(String text, List<Pair> pairs, Predicate<String> kept) {
        if (pairs.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (at < text.length()) {
            Pair found = longestAt(text, at, pairs);
            if (found == null || kept.test(text.substring(at, at + found.typed().length()))) {
                out.append(text.charAt(at++));
            } else {
                out.append(found.sent());
                at += found.typed().length();
            }
        }
        return out.toString();
    }

    public static boolean carriesPassword(String command) {
        return PASSWORD_COMMANDS.contains(commandWord(command));
    }

    // The command without its slash or the plugin name some servers allow in front of it.
    public static String commandWord(String command) {
        String word = command.strip();
        int space = word.indexOf(' ');
        if (space != -1) {
            word = word.substring(0, space);
        }
        if (word.startsWith("/")) {
            word = word.substring(1);
        }
        return word.substring(word.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
    }

    private static Pair longestAt(String text, int at, List<Pair> pairs) {
        Pair longest = null;
        for (Pair pair : pairs) {
            String typed = pair.typed();
            if ((longest == null || typed.length() > longest.typed().length()) && standsAt(text, at, typed)) {
                longest = pair;
            }
        }
        return longest;
    }

    private static boolean standsAt(String text, int at, String typed) {
        int end = at + typed.length();
        if (typed.isEmpty() || !text.regionMatches(true, at, typed, 0, typed.length())) {
            return false;
        }
        boolean clearBefore = at == 0 || !ChatUtil.isNameChar(typed.charAt(0))
            || !ChatUtil.isNameChar(text.charAt(at - 1));
        boolean clearAfter = end == text.length() || !ChatUtil.isNameChar(typed.charAt(typed.length() - 1))
            || !ChatUtil.isNameChar(text.charAt(end));
        return clearBefore && clearAfter;
    }
}
