/* SPDX-License-Identifier: GPL-3.0-only */
package helium314.keyboard.latin;

import org.junit.Test;
import static org.junit.Assert.*;

/** Editable model follows commitText semantics: a composing span OR selection
 * is replaced, not appended. This catches what the old append-only mock missed. */
public class VoiceTextBufferTest {
    static final class Editor implements VoiceTextBuffer.Editor {
        final StringBuilder text;
        int start; int end; int composingStart = -1; int composingEnd = -1;
        int commits; boolean accessible = true; boolean failCommit; boolean failFinish;
        Editor(String initial) { text = new StringBuilder(initial); start = end = text.length(); }
        @Override public VoiceTextBuffer.Snapshot snapshot() {
            if (!accessible || start != end) return null;
            return new VoiceTextBuffer.Snapshot(start,
                    text.substring(Math.max(0, start - VoiceTextBuffer.CONTEXT), start),
                    text.substring(end, Math.min(text.length(), end + VoiceTextBuffer.CONTEXT)));
        }
        @Override public boolean finishComposing() {
            if (failFinish) return false;
            composingStart = composingEnd = -1; return true;
        }
        @Override public boolean append(String value) {
            commits++;
            if (failCommit) return false;
            int from = composingStart >= 0 ? composingStart : start;
            int to = composingStart >= 0 ? composingEnd : end;
            text.replace(from, to, value); start = end = from + value.length();
            composingStart = composingEnd = -1; return true;
        }
    }

    @Test public void finalizedArabicEnglishAndMixedPhrasesAreImmutable() {
        Editor editor = new Editor("Existing: "); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        assertTrue(buffer.append("مرحبا كيف حالك")); assertTrue(buffer.append("اليوم سوف نجرب الميكروفون"));
        assertTrue(buffer.append("English والعربية"));
        assertEquals("Existing: مرحبا كيف حالك اليوم سوف نجرب الميكروفون English والعربية", editor.text.toString());
    }

    @Test public void staleEditorComposingSpanCannotReplacePreviousPhrase() {
        Editor editor = new Editor("previous words "); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        editor.composingStart = 0; editor.composingEnd = 14;
        assertTrue(buffer.append("new words")); assertEquals("previous words new words", editor.text.toString());
    }

    @Test public void sameSuffixAtDifferentCursorCannotAuthorizeDeletingOldWords() {
        Editor editor = new Editor("hello hello"); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        editor.start = editor.end = 5;
        assertFalse(buffer.append("world")); assertEquals("hello hello", editor.text.toString()); assertEquals(0, editor.commits);
    }

    @Test public void cursorMoveDuringSilenceNeverSplitsExistingWord() {
        Editor editor = new Editor("hello"); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        editor.start = editor.end = 2;
        assertFalse(buffer.append("speech")); assertEquals("hello", editor.text.toString());
    }

    @Test public void startingInsideWordFailsClosed() {
        Editor editor = new Editor("hello"); editor.start = editor.end = 2;
        VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        assertFalse(buffer.append("speech")); assertEquals(0, editor.commits);
    }

    @Test public void selectedTextCannotBeReplaced() {
        Editor editor = new Editor("protected text"); editor.start = 0;
        VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        assertFalse(buffer.append("speech")); assertEquals("protected text", editor.text.toString());
    }

    @Test public void changedContextAtSameAbsoluteCursorFailsClosed() {
        Editor editor = new Editor("hello"); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        editor.text.replace(0, 5, "other");
        assertFalse(buffer.append("speech")); assertEquals("other", editor.text.toString());
    }

    @Test public void editedTextAfterCaretFailsClosed() {
        Editor editor = new Editor("hello "); editor.start = editor.end = 5;
        VoiceTextBuffer buffer = new VoiceTextBuffer(editor); editor.text.append("changed");
        assertFalse(buffer.append("speech")); assertEquals(0, editor.commits);
    }

    @Test public void failedCommitInvalidatesAnchorAndCannotRetry() {
        Editor editor = new Editor("kept "); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        editor.failCommit = true; assertFalse(buffer.append("speech"));
        editor.failCommit = false; assertFalse(buffer.append("speech")); assertEquals(1, editor.commits);
    }

    @Test public void unsupportedReadAndFailedFinishCannotWrite() {
        Editor editor = new Editor("kept "); editor.accessible = false;
        VoiceTextBuffer buffer = new VoiceTextBuffer(editor); assertFalse(buffer.append("speech"));
        editor.accessible = true; buffer = new VoiceTextBuffer(editor); editor.failFinish = true;
        assertFalse(buffer.append("speech")); assertEquals(0, editor.commits);
    }

    @Test public void exactlyOneSeparatorBetweenPhrasesIncludingProviderWhitespace() {
        Editor editor = new Editor(""); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        assertTrue(buffer.append("  مرحبا  ")); assertTrue(buffer.append("\u00a0world\u2003"));
        assertTrue(buffer.append("")); assertTrue(buffer.append("  "));
        assertEquals("مرحبا world", editor.text.toString()); assertEquals(2, editor.commits);
    }

    @Test public void existingTrailingWhitespaceIsReusedWithoutReplacingIt() {
        Editor editor = new Editor("Existing  "); editor.start = editor.end = 9;
        VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        assertTrue(buffer.append("first")); assertTrue(buffer.append("second"));
        assertEquals("Existing first second ", editor.text.toString());
    }

    @Test public void wordBoundaryIsInsertedWhenExistingTextLacksSpace() {
        Editor editor = new Editor("مرحبا"); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        assertTrue(buffer.append("كيف حالك")); assertEquals("مرحبا كيف حالك", editor.text.toString());
    }

    @Test public void punctuationIsPreservedAndNotSeparatedFromPreviousWord() {
        Editor editor = new Editor("hello"); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        assertTrue(buffer.append("!")); assertTrue(buffer.append("مرحبا، بالعالم."));
        assertEquals("hello! مرحبا، بالعالم." , editor.text.toString());
    }

    @Test public void punctuationAfterTwoUtterancesHasNoSpaceBeforeMark() {
        Editor editor = new Editor(""); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        assertTrue(buffer.append("Hello")); assertTrue(buffer.append("world"));
        assertTrue(buffer.append(".")); assertTrue(buffer.append("Next"));
        assertEquals("Hello world. Next", editor.text.toString());
        assertEquals(4, editor.commits);
    }

    @Test public void preexistingSpaceBeforePunctuationFailsClosedWithoutDeleting() {
        Editor editor = new Editor("hello "); VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        assertFalse(buffer.append("."));
        assertEquals("hello ", editor.text.toString()); assertEquals(0, editor.commits);
    }

    @Test public void longUnicodeContextAndAbsoluteCursorRemainStable() {
        Editor editor = new Editor(new String(new char[300]).replace('\0', 'ع') + " 🙂 ");
        VoiceTextBuffer buffer = new VoiceTextBuffer(editor);
        assertTrue(buffer.append("hello مرحبا 🙂")); assertTrue(buffer.valid());
        assertTrue(buffer.append("next")); assertTrue(editor.text.toString().endsWith("hello مرحبا 🙂 next"));
    }
}
