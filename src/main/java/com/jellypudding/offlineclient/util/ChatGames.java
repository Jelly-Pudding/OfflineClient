package com.jellypudding.offlineclient.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

// Works out the answer to a chat game from the line that asks for it. The kind of game is told
// by a word in the line such as unscramble or solve. The puzzle is what the line sets in quotes
// or failing that what follows its last colon. A game that only wants text typed back must set
// one word in quotes and say it is a game. Type 'cancel' to stop is a prompt and gets no answer.
// A line from somebody talking only gets a sum or a trivia answer. Any other answer could be
// words the talker made up and set as the puzzle. An answer is only ever plain words for chat.
public final class ChatGames {

    // In the order they are looked for. A line that asks to unscramble and type a word is an
    // unscramble game.
    public enum Kind { TRIVIA, UNSCRAMBLE, REVERSE, FILL_IN, SUM, TYPE }

    private static final List<String> UNSCRAMBLE_WORDS = List.of("unscramble", "unscrambles", "scrambled",
        "scramble", "descramble", "anagram");
    private static final List<String> REVERSE_WORDS = List.of("reverse", "reversed", "backwards", "backward");
    private static final List<String> FILL_WORDS = List.of("fill", "missing", "blank", "blanks", "complete");
    private static final List<String> SUM_WORDS = List.of("solve", "solves", "calculate", "equation", "math",
        "maths", "sum", "what is", "equals");
    // A line that tells of somebody else such as Bob says hi is no game. Only the words that ask
    // the reader count.
    private static final List<String> TYPE_WORDS = List.of("type", "types", "write", "say", "retype", "copy");
    // What tells a game from a line that only asks you to type something.
    private static final List<String> GAME_WORDS = List.of("first", "fastest", "quickest", "win", "wins", "winner",
        "reward", "prize", "chat game", "chat games", "chatgame", "chatgames");
    // What a game may call itself in front of a chat mark such as ChatGames or Unscramble.
    private static final List<String> GAME_NAMES = Stream.of(UNSCRAMBLE_WORDS, REVERSE_WORDS, FILL_WORDS,
        SUM_WORDS, TYPE_WORDS, GAME_WORDS, List.of("game", "games", "trivia", "quiz")).flatMap(List::stream).toList();

    // A quote that opens a puzzle and under it the one that closes it.
    private static final String OPENING = "\"'`“‘«";
    private static final String CLOSING = "\"'`”’»";

    // What a puzzle of missing letters has in place of each one.
    private static final char GAP = '_';

    // A longer answer than this is no chat game.
    private static final int LONGEST_ANSWER = 64;

    // A word shorter than this is not looked for among the words of a line.
    private static final int SHORTEST_SCRAMBLE = 3;

    // A list of words ready for the games. A scrambled word is found by its letters in one look.
    // That look up is built the first time a game unscrambles.
    public static final class Words {

        private final List<String> listed;
        private Map<String, List<String>> byLetters;

        public Words(Collection<String> words) {
            listed = List.copyOf(words);
        }

        // The listed words made of the same letters in the order they were listed.
        private List<String> withLetters(String scrambled) {
            if (byLetters == null) {
                Map<String, List<String>> built = new HashMap<>();
                for (String word : listed) {
                    built.merge(letters(word), List.of(word), Words::joined);
                }
                byLetters = built;
            }
            return byLetters.getOrDefault(letters(scrambled), List.of());
        }

        private static List<String> joined(List<String> earlier, List<String> later) {
            List<String> all = new ArrayList<>(earlier.size() + later.size());
            all.addAll(earlier);
            all.addAll(later);
            return all;
        }
    }

    private ChatGames() {
    }

    // The answer the line asks for. Null for a line that is no game or a game of a kind that
    // is left out or one that has no answer among the words and trivia given. The words are only
    // fetched for a game that needs them. A question of the trivia is the text before its answer
    // in lower case.
    public static String solve(String line, Set<Kind> kinds, Supplier<Words> words, Map<String, String> trivia) {
        String text = BoundedRegex.cut(line.strip());
        String lower = text.toLowerCase(Locale.ROOT);
        Kind kind = kindOf(text, lower, trivia);
        if (kind == null || !kinds.contains(kind)) {
            return null;
        }
        String answer = switch (kind) {
            case TRIVIA -> triviaAnswer(lower, trivia);
            case UNSCRAMBLE -> unscramble(text, words.get());
            case REVERSE -> reversed(single(quoted(text)));
            case FILL_IN -> fillIn(gapped(text), words.get());
            case SUM -> sum(text);
            case TYPE -> single(quoted(text));
        };
        return sendable(answer) ? answer : null;
    }

    // Null for a line that asks for nothing.
    private static Kind kindOf(String text, String lower, Map<String, String> trivia) {
        if (triviaAnswer(lower, trivia) != null) {
            return Kind.TRIVIA;
        }
        if (holdsAny(lower, UNSCRAMBLE_WORDS) && fromGame(text)) {
            return Kind.UNSCRAMBLE;
        }
        if (holdsAny(lower, REVERSE_WORDS) && echoGame(text, lower)) {
            return Kind.REVERSE;
        }
        if (holdsAny(lower, FILL_WORDS) && fromGame(text) && gapped(text) != null) {
            return Kind.FILL_IN;
        }
        if (holdsAny(lower, SUM_WORDS) && sum(text) != null) {
            return Kind.SUM;
        }
        return holdsAny(lower, TYPE_WORDS) && echoGame(text, lower) ? Kind.TYPE : null;
    }

    // A game whose answer is text from the line itself. Nothing checks such an answer and the
    // line has to look like a game.
    private static boolean echoGame(String text, String lower) {
        return quoted(text) != null && holdsAny(lower, GAME_WORDS) && fromGame(text);
    }

    // False for a line that reads as somebody talking such as one from [Discord] Dave unless the
    // name in front of it is one a game goes by.
    private static boolean fromGame(String text) {
        String writer = ChatSender.apparentWriter(text);
        return writer == null || holdsAny(writer.toLowerCase(Locale.ROOT), GAME_NAMES);
    }

    // The puzzle of a game that echoes it when it is one word. Null for more.
    private static String single(String puzzle) {
        return puzzle == null || puzzle.chars().anyMatch(Character::isWhitespace) ? null : puzzle;
    }

    // Plain words for chat and never anything that would read as a command.
    private static boolean sendable(String answer) {
        return answer != null && !answer.isBlank() && answer.length() <= LONGEST_ANSWER && !answer.startsWith("/");
    }

    private static boolean holdsAny(String lower, List<String> words) {
        for (String word : words) {
            if (ChatUtil.wholeWordIndex(lower, word, 0) != -1) {
                return true;
            }
        }
        return false;
    }

    // The question has to stand whole in the line. A question of cat is not asked by a line about a category.
    private static String triviaAnswer(String lower, Map<String, String> trivia) {
        for (Map.Entry<String, String> entry : trivia.entrySet()) {
            if (!entry.getKey().isBlank() && ChatUtil.wholeWordIndex(lower, entry.getKey(), 0) != -1) {
                return entry.getValue();
            }
        }
        return null;
    }

    // What the line sets apart as its puzzle. Null when it sets nothing apart.
    private static String puzzle(String text) {
        String quoted = quoted(text);
        if (quoted != null) {
            return quoted;
        }
        int colon = text.lastIndexOf(':');
        if (colon == -1) {
            return null;
        }
        String rest = text.substring(colon + 1).strip();
        while (!rest.isEmpty() && ".!?".indexOf(rest.charAt(rest.length() - 1)) != -1) {
            rest = rest.substring(0, rest.length() - 1).strip();
        }
        return rest.isEmpty() ? null : rest;
    }

    // The first stretch between a pair of quotes. An apostrophe inside a word is no quote.
    private static String quoted(String text) {
        for (int open = 0; open < text.length(); open++) {
            int pair = OPENING.indexOf(text.charAt(open));
            if (pair == -1 || open > 0 && Character.isLetterOrDigit(text.charAt(open - 1))) {
                continue;
            }
            char close = CLOSING.charAt(pair);
            for (int end = text.indexOf(close, open + 1); end != -1; end = text.indexOf(close, end + 1)) {
                if (end + 1 == text.length() || !Character.isLetterOrDigit(text.charAt(end + 1))) {
                    String inside = text.substring(open + 1, end).strip();
                    if (!inside.isEmpty()) {
                        return inside;
                    }
                    break;
                }
            }
        }
        return null;
    }

    private static String reversed(String puzzle) {
        return puzzle == null ? null : new StringBuilder(puzzle).reverse().toString();
    }

    // The puzzle is unscrambled word for word. With no puzzle set apart the words of the line
    // are tried from the last to the first and one that is a word already is passed over.
    private static String unscramble(String text, Words words) {
        String puzzle = puzzle(text);
        if (puzzle != null) {
            StringBuilder out = new StringBuilder();
            for (String part : puzzle.split("\\s+")) {
                String word = unscrambled(part, words, false);
                if (word == null) {
                    return null;
                }
                out.append(out.isEmpty() ? "" : " ").append(word);
            }
            return out.toString();
        }
        String[] parts = text.split("[^\\p{L}\\p{N}]+");
        for (int i = parts.length - 1; i >= 0; i--) {
            String word = parts[i].length() < SHORTEST_SCRAMBLE ? null : unscrambled(parts[i], words, true);
            if (word != null) {
                return word;
            }
        }
        return null;
    }

    // The listed word made of the same letters. A puzzle that is a listed word as it stands
    // answers itself unless it must differ. Null when there is none.
    private static String unscrambled(String scrambled, Words words, boolean mustDiffer) {
        String found = null;
        String itself = null;
        for (String word : words.withLetters(scrambled)) {
            if (word.length() != scrambled.length()) {
                continue;
            }
            if (word.equalsIgnoreCase(scrambled)) {
                if (mustDiffer) {
                    return null;
                }
                itself = itself == null ? word : itself;
            } else if (found == null) {
                found = word;
            }
        }
        return inCaseOf(scrambled, found != null ? found : itself);
    }

    // The answer in small letters for a puzzle in small letters and in capitals for one in
    // capitals. A puzzle of mixed case gets the word as it was listed. Null stays null.
    private static String inCaseOf(String puzzle, String word) {
        if (word == null) {
            return null;
        }
        if (puzzle.equals(puzzle.toLowerCase(Locale.ROOT))) {
            return word.toLowerCase(Locale.ROOT);
        }
        return puzzle.equals(puzzle.toUpperCase(Locale.ROOT)) ? word.toUpperCase(Locale.ROOT) : word;
    }

    // The letters of a word in small letters and in order. Words made of the same letters share it.
    private static String letters(String word) {
        char[] letters = word.toLowerCase(Locale.ROOT).toCharArray();
        Arrays.sort(letters);
        return new String(letters);
    }

    // The word of the line with gaps in it. Letters spaced out with gaps between them are
    // drawn together. Null when no word has both a gap and a letter.
    private static String gapped(String text) {
        String puzzle = puzzle(text);
        if (puzzle != null && puzzle.indexOf(GAP) != -1) {
            String joined = puzzle.replace(" ", "");
            boolean spaced = Arrays.stream(puzzle.split(" +")).allMatch(part -> part.length() == 1);
            return hasLetter(joined) ? (spaced ? joined : puzzle) : null;
        }
        for (String part : text.split("\\s+")) {
            String word = part.replaceAll("^[^\\p{L}\\p{N}_]+|[^\\p{L}\\p{N}_]+$", "");
            if (word.indexOf(GAP) != -1 && hasLetter(word)) {
                return word;
            }
        }
        return null;
    }

    private static boolean hasLetter(String word) {
        return word.chars().anyMatch(Character::isLetterOrDigit);
    }

    // The first listed word that fits the letters given round the gaps.
    private static String fillIn(String gapped, Words words) {
        if (gapped == null) {
            return null;
        }
        for (String word : words.listed) {
            if (fits(word, gapped)) {
                return inCaseOf(gapped, word);
            }
        }
        return null;
    }

    private static boolean fits(String word, String gapped) {
        if (word.length() != gapped.length()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            char given = gapped.charAt(i);
            if (given != GAP && Character.toLowerCase(given) != Character.toLowerCase(word.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // The answer to the longest stretch of the line that reads as a sum. A question mark that
    // only ends the sentence is left off.
    private static String sum(String text) {
        String best = null;
        int bestLength = 0;
        int from = 0;
        while (from < text.length()) {
            if (!ChatSum.isSumChar(text, from)) {
                from++;
                continue;
            }
            int to = from;
            while (to < text.length() && ChatSum.isSumChar(text, to)) {
                to++;
            }
            String stretch = text.substring(from, to).strip();
            String answer = stretch.length() > bestLength ? sumOf(stretch) : null;
            if (answer != null) {
                best = answer;
                bestLength = stretch.length();
            }
            from = to;
        }
        return best;
    }

    // A stretch with one number in it is only a number that happened to stand in the line.
    private static String sumOf(String stretch) {
        String sum = stretch;
        while (sum.endsWith(".") || sum.endsWith("?") && closesOperand(sum)) {
            sum = sum.substring(0, sum.length() - 1).strip();
        }
        return numbers(sum) > 1 ? ChatSum.solve(sum) : null;
    }

    // How many numbers the stretch holds with a question mark or an underscore counted as one.
    private static int numbers(String sum) {
        int count = 0;
        boolean inNumber = false;
        for (char c : sum.toCharArray()) {
            boolean digit = Character.isDigit(c) || c == '.';
            if (digit && !inNumber || c == '?' || c == GAP) {
                count++;
            }
            inNumber = digit;
        }
        return count;
    }

    // True when the last sign of the stretch follows a number or a bracket and is no unknown.
    private static boolean closesOperand(String sum) {
        String before = sum.substring(0, sum.length() - 1).strip();
        return !before.isEmpty() && (Character.isDigit(before.charAt(before.length() - 1)) || before.endsWith(")"));
    }
}
