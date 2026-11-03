/* SPDX-License-Identifier: GPL-3.0-only */
package helium314.keyboard.latin;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import helium314.keyboard.ShadowInputMethodManager2;
import helium314.keyboard.ShadowProximityInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual controller and real EditText/InputConnection; speech transport is
 * scripted. Absence modes strip spans, never manufacture SPAN_COMPOSING. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {ShadowInputMethodManager2.class, ShadowProximityInfo.class})
public class VoiceCompositionCompatibilityTest {
    private LatinIME ime;
    private Activity activity;
    private EditText field;
    private EditorInfo info;
    private Connection connection;
    private VoiceInputController voice;
    private final List<VoiceDictationSession.Listener> listeners = new ArrayList<>();
    private int active, maximum;
    private Mode mode = Mode.PLAIN;
    private boolean missingPosition;
    private boolean optionalThrows;
    private int extractionOffset;
    private enum Mode { PLAIN, SURROUNDING, EXTRACTED, UNMARKED, UNMARKED_SURROUNDING, DELTA, BAD_OFFSET, BAD_LENGTH, BAD_CONTENT }

    private final class Connection extends InputConnectionWrapper {
        final EditText view;
        Runnable duringCompose;
        int writes;
        Connection(EditText view) { super(view.onCreateInputConnection(new EditorInfo()), false); this.view = view; }
        @Override public CharSequence getTextBeforeCursor(int n, int flags) {
            if (flags != InputConnection.GET_TEXT_WITH_STYLES) return super.getTextBeforeCursor(n, flags);
            if (optionalThrows) throw new UnsupportedOperationException("optional styles");
            int end = view.getSelectionStart(), start = Math.max(0, end - n);
            CharSequence slice = view.getText().subSequence(start, end);
            if (mode == Mode.SURROUNDING) return new SpannableString(slice);
            if (mode == Mode.UNMARKED || mode == Mode.UNMARKED_SURROUNDING)
                return new SpannableString(slice.toString()); // No synthesized composing spans.
            return slice.toString();
        }
        @Override public CharSequence getTextAfterCursor(int n, int flags) {
            if (flags != InputConnection.GET_TEXT_WITH_STYLES) return super.getTextAfterCursor(n, flags);
            int start = view.getSelectionEnd(), end = Math.min(view.length(), start + n);
            CharSequence slice = view.getText().subSequence(start, end);
            return mode == Mode.SURROUNDING ? new SpannableString(slice) : slice.toString();
        }
        @Override public android.view.inputmethod.ExtractedText getExtractedText(
                android.view.inputmethod.ExtractedTextRequest request, int flags) {
            if (missingPosition) return null;
            if (optionalThrows && request.flags == InputConnection.GET_TEXT_WITH_STYLES)
                throw new UnsupportedOperationException("optional styles");
            android.view.inputmethod.ExtractedText result = super.getExtractedText(request, flags);
            if (request.flags != InputConnection.GET_TEXT_WITH_STYLES || result == null) return result;
            int offset = extractionOffset;
            CharSequence slice = view.getText().subSequence(offset, view.length());
            result.startOffset = offset;
            result.selectionStart = view.getSelectionStart() - offset;
            result.selectionEnd = view.getSelectionEnd() - offset;
            result.partialStartOffset = result.partialEndOffset = -1;
            if (mode == Mode.EXTRACTED || mode == Mode.UNMARKED_SURROUNDING || mode == Mode.DELTA || mode == Mode.BAD_OFFSET || mode == Mode.BAD_LENGTH || mode == Mode.BAD_CONTENT)
                result.text = new SpannableString(slice); // Only the real Editable's spans.
            else if (mode == Mode.UNMARKED) result.text = new SpannableString(slice.toString());
            else result.text = slice.toString();
            if (mode == Mode.DELTA) result.partialStartOffset = result.partialEndOffset = 0;
            if (mode == Mode.BAD_OFFSET) result.startOffset += 1000;
            if (mode == Mode.BAD_LENGTH) result.selectionEnd = result.text.length() + 1;
            if (mode == Mode.BAD_CONTENT) {
                android.text.SpannableStringBuilder changed = new android.text.SpannableStringBuilder(result.text);
                int at = BaseInputConnection.getComposingSpanStart(view.getText()) - offset + 1;
                changed.replace(at, at + 1, "X"); result.text = changed;
            }
            return result;
        }
        @Override public boolean setComposingText(CharSequence value, int cursor) {
            writes++;
            boolean result = super.setComposingText(value, cursor);
            if (duringCompose != null) { Runnable task = duringCompose; duringCompose = null; task.run(); }
            return result;
        }
        @Override public boolean commitText(CharSequence value, int cursor) {
            assertEquals("foreign composition must be finished before commitText", -1,
                    BaseInputConnection.getComposingSpanStart(view.getText()));
            writes++; return super.commitText(value, cursor);
        }
        @Override public boolean deleteSurroundingText(int before, int after) { fail("K03 must never delete relative to cursor"); return false; }
    }
    private EditText newField(int id, String text) {
        EditText result = new EditText(activity); result.setId(id);
        result.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        result.setText(text); result.setSelection(result.length()); return result;
    }
    private EditorInfo metadata(int id) {
        EditorInfo result = new EditorInfo(); result.packageName = "test.editor";
        result.fieldId = id; result.inputType = android.text.InputType.TYPE_CLASS_TEXT; return result;
    }
    private void bind(Connection conn, EditorInfo editor) {
        doReturn(conn).when(ime).getCurrentInputConnection();
        doReturn(editor).when(ime).getCurrentInputEditorInfo();
    }
    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        ime = spy(Robolectric.setupService(LatinIME.class));
        doReturn(PackageManager.PERMISSION_GRANTED).when(ime).checkSelfPermission(Manifest.permission.RECORD_AUDIO);
        doReturn(null).when(ime).getWindow();
        field = newField(7, "Existing: "); info = metadata(7); connection = new Connection(field); bind(connection, info);
        voice = new VoiceInputController(ime, listener -> {
            listeners.add(listener);
            return new VoiceDictationSession.Engine() {
                boolean closed;
                public void start() { maximum = Math.max(maximum, ++active); listener.ready(); }
                public void close() { assertFalse(closed); closed = true; active--; }
            };
        });
        try (MockedStatic<SpeechRecognizer> speech = mockStatic(SpeechRecognizer.class)) {
            speech.when(() -> SpeechRecognizer.isRecognitionAvailable(ime)).thenReturn(true);
            voice.toggle(Locale.ENGLISH);
        }
        assertTrue(voice.isEnabled());
    }
    @After public void cleanup() { voice.destroy(); assertEquals(0, active); assertTrue(maximum <= 1); activity.finish(); }
    private VoiceDictationSession.Listener current() { return listeners.get(listeners.size()-1); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(750)); }
    private void assertText(String expected) { assertEquals("Existing: " + expected, field.getText().toString()); }


    @Test public void plainStringBothChannelsBuffersPartialsUntilCorrectedFinal() {
        current().partial("I can right"); assertText(""); assertEquals(0, connection.writes);
        current().result("I can write"); assertText("I can write"); assertTrue(voice.isEnabled());
    }
    @Test public void optionalStyleRequestsThrowingStillAllowSafeFinalOnly() {
        optionalThrows = true; current().partial("draft"); assertText("");
        current().result("final"); assertText("final"); assertTrue(voice.isEnabled());
    }
    @Test public void finalOnlyAlsoWorksInAnEmptyField() {
        voice.stop(); field.setText(""); field.setSelection(0); startAgain();
        current().partial("مرحبا"); assertEquals("", field.getText().toString());
        current().result("مرحبا بالعالم"); assertEquals("مرحبا بالعالم", field.getText().toString());
    }
    private void startAgain() {
        try (MockedStatic<SpeechRecognizer> speech = mockStatic(SpeechRecognizer.class)) {
            speech.when(() -> SpeechRecognizer.isRecognitionAvailable(ime)).thenReturn(true);
            voice.toggle(Locale.ENGLISH);
        }
    }
    @Test public void extractedSpansAuthorizeLivePartialsWhenSurroundingReturnsString() {
        mode = Mode.EXTRACTED; current().partial("I can right"); assertText("I can right");
        current().result("I can write"); assertText("I can write"); assertTrue(voice.isEnabled());
    }
    @Test public void extractedSpansWorkWhenSurroundingSpannedHasNoComposingMarkers() {
        mode = Mode.UNMARKED_SURROUNDING; current().partial("مرحبا"); assertText("مرحبا");
        current().result("مرحبا بالعالم"); assertText("مرحبا بالعالم"); assertTrue(voice.isEnabled());
    }
    @Test public void supportingSurroundingChannelKeepsLivePartialBehavior() {
        mode = Mode.SURROUNDING; current().partial("one"); assertText("one");
        current().partial("one two"); current().result("one two three"); assertText("one two three");
    }
    @Test public void validNonzeroExtractionOffsetIsMappedToAbsoluteOwnedRange() {
        mode = Mode.EXTRACTED; extractionOffset = 3;
        current().partial("one"); current().result("one two"); assertText("one two"); assertTrue(voice.isEnabled());
    }
    @Test public void metadataDisappearingAfterVisiblePartialPreservesTextAndReports() {
        mode = Mode.EXTRACTED; current().partial("one"); assertText("one");
        mode = Mode.PLAIN; current().result("one two"); assertText("one"); assertFalse(voice.isEnabled());
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("ملكية"));
    }
    @Test public void unmarkedSpannedIsNotOwnershipEvidenceForReplacement() {
        mode = Mode.UNMARKED; current().partial("one"); current().result("one two");
        assertText("one"); assertFalse(voice.isEnabled());
    }
    @Test public void deltaExtractionCannotAuthorizeReplacingPartial() {
        mode = Mode.EXTRACTED; current().partial("one"); mode = Mode.DELTA;
        current().result("one two"); assertText("one"); assertFalse(voice.isEnabled());
    }
    @Test public void wrongExtractionOffsetCannotAuthorizeReplacingPartial() {
        mode = Mode.EXTRACTED; current().partial("one"); mode = Mode.BAD_OFFSET;
        current().result("one two"); assertText("one"); assertFalse(voice.isEnabled());
    }
    @Test public void invalidExtractionSelectionBoundsCannotAuthorizeReplacement() {
        mode = Mode.EXTRACTED; current().partial("one"); mode = Mode.BAD_LENGTH;
        current().result("one two"); assertText("one"); assertFalse(voice.isEnabled());
    }
    @Test public void extractedSpanOnDifferentTextCannotAuthorizeReplacement() {
        mode = Mode.EXTRACTED; current().partial("one"); mode = Mode.BAD_CONTENT;
        current().result("one two"); assertText("one"); assertFalse(voice.isEnabled());
    }
    @Test public void foreignComposingRangeIsNotReplacedEvenWithMatchingText() {
        mode = Mode.EXTRACTED; current().partial("one"); connection.setComposingRegion(0, 3);
        current().result("one two"); assertText("one"); assertFalse(voice.isEnabled());
    }
    @Test public void broaderForeignSpanCannotBeMistakenForClippedOwnedSuffix() {
        mode = Mode.SURROUNDING; current().partial("one");
        connection.setComposingRegion(0, field.length()); current().result("one two");
        assertText("one"); assertFalse(voice.isEnabled());
    }
    @Test public void foreignSpanExtendingAfterCaretCannotAuthorizeReplacement() {
        voice.stop(); field.setText("Existing: !"); field.setSelection(10); startAgain();
        mode = Mode.SURROUNDING; current().partial("one");
        connection.setComposingRegion(10, field.length()); current().result("one two");
        assertEquals("Existing: one!", field.getText().toString()); assertFalse(voice.isEnabled());
    }
    @Test public void finalOnlyFinishesForeignCompositionWithoutReplacingPreviousText() {
        connection.setComposingRegion(0, 8); current().partial("new"); assertText("");
        current().result("new phrase"); assertText("new phrase"); assertTrue(voice.isEnabled());
    }
    @Test public void threeFinalOnlyUtterancesPreserveArabicEnglishAndMixedText() {
        String[] phrases = {"مرحبا بالعالم", "hello world", "مرحبا KEYNOVA"};
        for (String phrase : phrases) { current().partial(phrase + " draft"); current().result(phrase); idle(); }
        assertText("مرحبا بالعالم hello world مرحبا KEYNOVA"); assertTrue(voice.isEnabled());
    }
    @Test public void threeLiveUtterancesPreserveAllEarlierFinals() {
        mode = Mode.EXTRACTED;
        for (String phrase : new String[]{"مرحبا", "hello", "مرحبا KEYNOVA"}) {
            current().partial(phrase); current().result(phrase); idle();
        }
        assertText("مرحبا hello مرحبا KEYNOVA"); assertTrue(voice.isEnabled());
    }
    @Test public void earlierTextMatchingHypothesisIsNeverClaimedAsOwned() {
        voice.stop(); field.setText("hello"); field.setSelection(5); startAgain();
        current().partial("hello"); assertEquals("hello", field.getText().toString());
        current().result("hello"); assertEquals("hello hello", field.getText().toString());
    }
    @Test public void movedCaretStopsFinalOnlyBeforeAnyWrite() {
        current().partial("private"); field.setSelection(0); current().result("private");
        assertFalse(voice.isEnabled()); assertText(""); assertEquals(0, connection.writes);
    }
    @Test public void missingEssentialPositionStopsRatherThanUsingTextMatch() {
        current().partial("private"); missingPosition = true; current().result("private");
        assertFalse(voice.isEnabled()); assertText(""); assertEquals(0, connection.writes);
    }
    @Test public void changedFieldAndOldCallbacksCannotWriteFinalOnlyHypothesis() {
        current().partial("private"); VoiceDictationSession.Listener old = current();
        EditText other = newField(8, "Existing: "); Connection next = new Connection(other); bind(next, metadata(8));
        voice.onInputStarted(false); old.result("private"); idle();
        assertFalse(voice.isEnabled()); assertText(""); assertEquals("Existing: ", other.getText().toString()); assertEquals(0,next.writes);
    }
    @Test public void manualStopSealsOnceThenRejectsLateWritesAndResumption() {
        current().partial("saved"); VoiceDictationSession.Listener old = current(); voice.stop();
        assertText("saved"); int writes = connection.writes;
        voice.onInputStarted(true); voice.onInputViewStarted(); idle(); old.result("late"); old.partial("late");
        assertFalse(voice.isEnabled()); assertText("saved"); assertEquals(writes,connection.writes);
    }
}
