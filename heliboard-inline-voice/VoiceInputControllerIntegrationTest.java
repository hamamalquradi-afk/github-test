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

/** Actual controller and EditText editing. Only speech transport is scripted;
 * the service getters supply the test binding. Styled composition is exposed
 * explicitly to isolate K03 from editor-specific surrounding-text behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {ShadowInputMethodManager2.class, ShadowProximityInfo.class})
public class VoiceInputControllerIntegrationTest {
    private LatinIME ime;
    private Activity activity;
    private EditText field;
    private EditorInfo info;
    private Connection connection;
    private VoiceInputController voice;
    private final List<VoiceDictationSession.Listener> listeners = new ArrayList<>();
    private int active, maximum;
    private final class Connection extends InputConnectionWrapper {
        final EditText view;
        Runnable duringCompose;
        int writes;
        Connection(EditText view) { super(view.onCreateInputConnection(new EditorInfo()), false); this.view = view; }
        @Override public CharSequence getTextBeforeCursor(int n, int flags) {
            if (flags != InputConnection.GET_TEXT_WITH_STYLES) return super.getTextBeforeCursor(n, flags);
            int end = view.getSelectionStart(), start = Math.max(0, end - n);
            SpannableString result = new SpannableString(view.getText().subSequence(start, end).toString());
            int composingStart = BaseInputConnection.getComposingSpanStart(view.getText());
            int composingEnd = BaseInputConnection.getComposingSpanEnd(view.getText());
            if (composingStart >= start && composingEnd == end) result.setSpan(new Object(),
                    composingStart - start, end - start, Spanned.SPAN_COMPOSING | Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return result;
        }
        @Override public boolean setComposingText(CharSequence value, int cursor) {
            writes++;
            boolean result = super.setComposingText(value, cursor);
            if (duringCompose != null) { Runnable task = duringCompose; duringCompose = null; task.run(); }
            return result;
        }
        @Override public boolean commitText(CharSequence value, int cursor) { writes++; return super.commitText(value, cursor); }
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

    @Test public void restartAfterPartialKeepsOneToFiveWordUtterance() {
        current().partial("one"); voice.onInputStarted(true); voice.onInputViewStarted();
        assertTrue(voice.isEnabled());
        for (String phrase : new String[]{"one two", "one two three", "one two three four", "one two three four five"}) current().partial(phrase);
        current().result("one two three four five"); assertText("one two three four five");
        assertEquals(1, listeners.size());
    }
    @Test public void newWrapperWithOriginalEditorIdentityRetainsOwnedComposition() {
        current().partial("one"); Connection wrapper = new Connection(field); bind(wrapper, info);
        voice.onInputStarted(true); voice.onInputViewStarted(); current().partial("one two"); current().result("one two");
        assertTrue(voice.isEnabled()); assertText("one two");
    }
    @Test public void newEditorInfoWithSameConnectionRetainsField() {
        current().partial("one"); bind(connection, metadata(7));
        voice.onInputStarted(true); voice.onInputViewStarted(); current().result("one two");
        assertTrue(voice.isEnabled()); assertText("one two");
    }
    @Test public void finishStartReleasesAudioAndResumesOnlyAfterView() {
        current().partial("one"); VoiceDictationSession.Listener old = current();
        voice.onInputFinished(); assertTrue(voice.isEnabled()); assertEquals(0, active);
        voice.onInputStarted(true); idle(); assertEquals(0, active);
        old.result("stale"); assertText("one");
        voice.onInputViewStarted(); idle(); assertEquals(1, active);
        current().result("two three four five"); assertText("one two three four five");
    }
    @Test public void restartCallbackInsideComposingWaitsForVerifiedCheckpoint() {
        connection.duringCompose = () -> voice.onInputStarted(true);
        current().partial("one"); idle(); assertTrue(voice.isEnabled());
        current().result("one two"); assertText("one two");
    }
    @Test public void differentFieldRejectsOldCallbacks() { rejectOther(8, true); }
    @Test public void identicalMetadataAndTextCannotProveDifferentFieldIdentity() { rejectOther(7, true); }
    @Test public void ambiguousFieldIdentityStopsSafely() { rejectOther(0, true); }
    private void rejectOther(int id, boolean restarting) {
        current().partial("one"); VoiceDictationSession.Listener old = current();
        EditText other = newField(id, field.getText().toString()); Connection candidate = new Connection(other);
        bind(candidate, metadata(id)); voice.onInputStarted(restarting); voice.onInputViewStarted(); idle();
        old.partial("private"); old.result("private");
        assertFalse(voice.isEnabled()); assertEquals(0, active); assertEquals(0, candidate.writes);
        assertText("one"); assertEquals("Existing: one", other.getText().toString());
    }
    @Test public void lostComposingOwnershipStopsRatherThanReplacingText() {
        current().partial("one"); connection.finishComposingText(); voice.onInputStarted(true);
        current().result("one two"); assertFalse(voice.isEnabled()); assertText("one");
    }
    @Test public void movedCaretInSameFieldStopsWithoutDeletingPartial() {
        current().partial("one"); field.setSelection(0);
        voice.onInputStarted(true); current().result("private");
        assertFalse(voice.isEnabled()); assertText("one");
    }
    @Test public void changedContextInSameFieldStopsWithoutDeletingText() {
        current().partial("one"); field.getText().insert(0, "manual ");
        voice.onInputStarted(true); current().result("private");
        assertFalse(voice.isEnabled()); assertEquals("manual Existing: one", field.getText().toString());
    }
    @Test public void repeatedLifecycleCallbacksNeverCreateConcurrentRecognizers() {
        current().partial("one"); voice.onInputFinished(); voice.onInputFinished();
        voice.onInputStarted(true); voice.onInputStarted(true);
        voice.onInputViewStarted(); voice.onInputViewStarted(); idle();
        assertTrue(voice.isEnabled()); assertEquals(1, active); assertEquals(2, listeners.size());
        current().result("two"); assertText("one two");
    }
    @Test public void nonRestartStartCannotResumeOldIntentEvenWithSameObjects() {
        current().partial("one"); VoiceDictationSession.Listener old = current();
        voice.onInputStarted(false); voice.onInputViewStarted(); idle(); old.result("private");
        assertFalse(voice.isEnabled()); assertText("one");
    }
    @Test public void hiddenWindowDuringRebindPreventsResume() { cancelWaiting(0); }
    @Test public void manualStopDuringRebindPreventsResume() { cancelWaiting(1); }
    @Test public void serviceDestroyDuringRebindPreventsResume() { cancelWaiting(2); }
    private void cancelWaiting(int how) {
        current().partial("one"); VoiceDictationSession.Listener old = current(); voice.onInputFinished();
        if (how == 0) voice.onWindowHidden(); else if (how == 1) voice.stop(); else voice.destroy();
        voice.onInputStarted(true); voice.onInputViewStarted(); idle(); old.partial("stale"); old.result("stale");
        assertFalse(voice.isEnabled()); assertEquals(0, active); assertText("one");
    }
}
