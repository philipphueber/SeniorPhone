package app.phueber.seniorphone;

/**
 * The rules of the number pad, kept free of Android so they can be tested:
 * what a key adds, what Delete removes, what a number from another app becomes,
 * and when Call may be pressed.
 *
 * A number is kept exactly as typed (digits, *, # and one leading +). It is
 * not grouped or reformatted on screen: a senior compares it digit by digit
 * with the paper it was copied from, and spaces that were never typed only
 * get in the way of that.
 */
final class DialNumber {
    /** Longer than any real number incl. codes like *21*+49...#, short enough to show. */
    static final int MAX_LENGTH = 32;

    private DialNumber() {}

    /** Whether the pad has a key for c. */
    static boolean isKey(char c) {
        return (c >= '0' && c <= '9') || c == '*' || c == '#' || c == '+';
    }

    /**
     * The number after pressing key. A + only counts as the very first
     * character (the international prefix); anything past MAX_LENGTH and any
     * character the pad does not have is ignored, so the result is always valid.
     */
    static String append(String number, char key) {
        String n = number == null ? "" : number;
        if (!isKey(key) || n.length() >= MAX_LENGTH) return n;
        if (key == '+' && !n.isEmpty()) return n;
        return n + key;
    }

    /** The number without its last character ("" stays ""). */
    static String deleteLast(String number) {
        if (number == null || number.isEmpty()) return "";
        return number.substring(0, number.length() - 1);
    }

    /**
     * A number handed over by another app (the part after "tel:", already
     * URL-decoded), reduced to what the pad could have typed: spaces, dashes,
     * dots, slashes and brackets are dropped, so are pause/wait characters and
     * everything after them (extension digits would be dialled straight away),
     * a + survives only at the start, and it is cut to MAX_LENGTH.
     */
    static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < MAX_LENGTH; i++) {
            char c = raw.charAt(i);
            if (c == ',' || c == ';' || c == 'p' || c == 'P' || c == 'w' || c == 'W') break;
            if (c == '+') {
                if (out.length() == 0) out.append(c);
            } else if (isKey(c)) {
                out.append(c);
            }
            // Separators and anything else are silently dropped.
        }
        return out.toString();
    }

    /** Call is allowed once the number has at least one digit ("+" or "*" alone is not a number). */
    static boolean canCall(String number) {
        if (number == null) return false;
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c >= '0' && c <= '9') return true;
        }
        return false;
    }

    /** The small letters printed under a digit key, as on every phone ("" for 0, 1, * and #). */
    static String letters(char digit) {
        switch (digit) {
            case '2': return "ABC";
            case '3': return "DEF";
            case '4': return "GHI";
            case '5': return "JKL";
            case '6': return "MNO";
            case '7': return "PQRS";
            case '8': return "TUV";
            case '9': return "WXYZ";
            default: return "";
        }
    }

    /**
     * The number for a screen reader: one character at a time ("0 1 7 1"),
     * so TalkBack reads digits instead of "one hundred and seventy-one".
     * plus, star and hash are the spoken words for +, * and #.
     */
    static String spoken(String number, String plus, String star, String hash) {
        if (number == null || number.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            if (i > 0) out.append(' ');
            char c = number.charAt(i);
            if (c == '+') out.append(plus);
            else if (c == '*') out.append(star);
            else if (c == '#') out.append(hash);
            else out.append(c);
        }
        return out.toString();
    }
}
