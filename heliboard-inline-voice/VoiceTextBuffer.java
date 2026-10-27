/* SPDX-License-Identifier: GPL-3.0-only */
package helium314.keyboard.latin;

/** Append-only editor boundary. Partials NEVER enter editor-owned composing
 * spans. The indicator displays them until one immutable phrase is committed.
 * There is deliberately no delete, setSelection or replacement API here. */
final class VoiceTextBuffer {
    static final int CONTEXT = 128;
    interface Editor {
        Snapshot snapshot();
        boolean finishComposing();
        boolean append(String text);
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

    VoiceTextBuffer(Editor editor) {
        this.editor = editor;
        expected = editor.snapshot();
    }

    boolean valid() { return expected != null && expected.same(editor.snapshot()); }

    boolean append(String phrase) {
        String value = cleanPhrase(phrase);
        if (value.isEmpty()) return true;
        if (!valid()) return false;
        // Reject insertion within a word, rather than moving the caret or
        // splitting text that the user did not ask dictation to edit.
        if (!expected.after.isEmpty() && word(expected.after.codePointAt(0))) return false;
        if (!editor.finishComposing() || !valid()) return false;
        String prefix = "";
        if (!expected.before.isEmpty()) {
            int last = expected.before.codePointBefore(expected.before.length());
            int first = value.codePointAt(0);
            if (!space(last) && !closing(first) && !opening(last)) prefix = " ";
        }
        // Reuse whitespace already immediately after the insertion point.
        String suffix = !expected.after.isEmpty() && space(expected.after.codePointAt(0)) ? "" : " ";
        String insertion = prefix + value + suffix;
        Snapshot before = expected;
        if (!editor.append(insertion)) { expected = null; return false; }
        Snapshot after = editor.snapshot();
        String context = before.before + insertion;
        context = context.substring(Math.max(0, context.length() - CONTEXT));
        // A nonconforming editor must never cause a retry/duplicate commit.
        if (after == null || after.cursor != before.cursor + insertion.length()
                || !after.before.equals(context) || !after.after.equals(before.after)) {
            expected = null;
            return false;
        }
        expected = after;
        return true;
    }

    private static boolean space(int c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
    static String cleanPhrase(String text) {
        if (text == null) return "";
        int start = 0;
        int end = text.length();
        while (start < end && space(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
        while (end > start && space(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end));
        return text.substring(start, end);
    }
    private static boolean word(int c) {
        int type = Character.getType(c);
        return Character.isLetterOrDigit(c) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }
    private static boolean closing(int c) { return ".,!?;:،؛؟)]}»\"".indexOf(c) >= 0; }
    private static boolean opening(int c) { return "([{«".indexOf(c) >= 0; }
}
