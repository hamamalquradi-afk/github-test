/* SPDX-License-Identifier: GPL-3.0-only */
package helium314.keyboard.latin;

/**
 * Stable words remain append-only. Only the currently owned Android composing
 * range may be revised by a provider partial; it is never used for older text.
 * If the editor loses composition ownership, all further writes fail closed.
 */
final class VoiceTextBuffer {
    static final int CONTEXT = 128;
    interface Editor {
        Snapshot snapshot();
        boolean finishComposing();
        boolean append(String text);
        default boolean compose(String text) { return false; }
        default boolean ownsComposition(int start, int end) { return false; }
    }
    static final class Snapshot {
        final int cursor;
        final String before;
        final String after;
        Snapshot(int cursor, String before, String after) {
            this.cursor = cursor;
            this.before = before;
            this.after = after;
        }
        boolean same(Snapshot other) {
            return other != null && cursor == other.cursor
                    && before.equals(other.before) && after.equals(other.after);
        }
    }

    private final Editor editor;
    private Snapshot expected;
    private int composingStart = -1;
    private Snapshot anchor;
    private String composing = "";

    VoiceTextBuffer(Editor editor) {
        this.editor = editor;
        expected = editor.snapshot();
    }

    boolean valid() {
        return matchesSnapshot(editor.snapshot()) && (composingStart < 0
                || editor.ownsComposition(composingStart, expected.cursor));
    }

    boolean matchesSnapshot(Snapshot snapshot) {
        return expected != null && expected.same(snapshot);
    }

    private static String context(String before, String addition) {
        String value = before + addition;
        return value.substring(Math.max(0, value.length() - CONTEXT));
    }

    private boolean checkAfter(Snapshot before, String inserted) {
        Snapshot after = editor.snapshot();
        if (after == null || after.cursor != before.cursor + inserted.length()
                || !after.before.equals(context(before.before, inserted))
                || !after.after.equals(before.after)) {
            expected = null;
            return false;
        }
        expected = after;
        return true;
    }

    private String insertion(Snapshot anchor, String phrase) {
        if (phrase.isEmpty()) return "";
        if (!anchor.after.isEmpty() && word(anchor.after.codePointAt(0))) return null;
        if (!anchor.before.isEmpty() && space(anchor.before.codePointBefore(anchor.before.length()))
                && closing(phrase.codePointAt(0))) return null;
        if (anchor.before.isEmpty()) return phrase;
        int last = anchor.before.codePointBefore(anchor.before.length());
        int first = phrase.codePointAt(0);
        return (!space(last) && !closing(first) && !opening(last) ? " " : "") + phrase;
    }

    /** Reflect recognition partials directly in the editor, but only while
     * Android reports our exact composing span at the verified absolute caret.
     * No deletion, setSelection or old phrase replacement is available. */
    boolean preview(String phrase) {
        String value = cleanPhrase(phrase);
        if (value.isEmpty()) return true;
        if (!valid()) return false;
        if (composingStart < 0) {
            if (!editor.finishComposing() || !valid()) return false;
            Snapshot before = expected;
            String inserted = insertion(before, value);
            if (inserted == null) return false;
            if (!editor.compose(inserted)) { expected = null; return false; }
            if (!checkAfter(before, inserted)) return false;
            composingStart = before.cursor;
            anchor = before;
            composing = inserted;
        } else {
            if (!editor.ownsComposition(composingStart, expected.cursor)) return false;
            String inserted = insertion(anchor, value);
            if (inserted == null) return false;
            if (!editor.compose(inserted)) { expected = null; return false; }
            Snapshot after = editor.snapshot();
            if (after == null || after.cursor != composingStart + inserted.length()
                    || !after.before.equals(context(anchor.before, inserted))
                    || !after.after.equals(anchor.after)) { expected = null; return false; }
            expected = after;
            composing = inserted;
        }
        return editor.ownsComposition(composingStart, expected.cursor);
    }

    /** Seal one final phrase. If partial text is visible, update and finish
     * only its verified composing span; otherwise commit as plain new text. */
    boolean append(String phrase) {
        String value = cleanPhrase(phrase);
        if (value.isEmpty()) return true;
        if (!valid()) return false;
        if (composingStart >= 0) {
            Snapshot before = expected;
            if (!editor.ownsComposition(composingStart, before.cursor)) return false;
            String inserted = insertion(anchor, value);
            if (inserted == null) return false;
            if (!editor.compose(inserted)) { expected = null; return false; }
            Snapshot after = editor.snapshot();
            if (after == null || after.cursor != composingStart + inserted.length()
                    || !after.before.equals(context(anchor.before, inserted))
                    || !after.after.equals(anchor.after)
                    || !editor.ownsComposition(composingStart, after.cursor)) {
                expected = null; return false;
            }
            if (!editor.finishComposing()) { expected = null; return false; }
            expected = after;
            composingStart = -1;
            anchor = null;
            composing = "";
            return valid();
        }
        // Existing fallback for providers that send only final results.
        if (!editor.finishComposing() || !valid()) return false;
        Snapshot before = expected;
        String inserted = insertion(before, value);
        if (inserted == null) return false;
        if (!editor.append(inserted)) { expected = null; return false; }
        return checkAfter(before, inserted);
    }

    /** When authorization is lost, never touch an unverified composing range. */
    void abandon() {
        composingStart = -1;
        anchor = null;
        composing = "";
        expected = null;
    }

    private static boolean space(int c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
    static String cleanPhrase(String text) {
        if (text == null) return "";
        int start = 0, end = text.length();
        while (start < end && space(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
        while (end > start && space(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end));
        return text.substring(start, end);
    }
    private static boolean word(int c) {
        int type = Character.getType(c);
        return Character.isLetterOrDigit(c) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }
    private static boolean closing(int c) { return ".,!?;:،؛؟)]}»\\"".indexOf(c) >= 0; }
    private static boolean opening(int c) { return "([{«".indexOf(c) >= 0; }
}
