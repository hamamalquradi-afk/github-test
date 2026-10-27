package helium314.keyboard.latin;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.FrameLayout;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputConnection;
import helium314.keyboard.ShadowInputMethodManager2;
import helium314.keyboard.ShadowProximityInfo;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Locale;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Android adapter against realistic selection/composition-aware editor mocks.
 * Old coverage is retained; hypotheses now stay in the live strip until sealed. */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowInputMethodManager2.class, ShadowProximityInfo.class})
public class VoiceInputControllerTest {
    private VoiceInputController voice;
    private LatinIME ime;
    private InputConnection editor;
    private VoiceDictationSession.Listener listener;
    private final VoiceTextBufferTest.Editor model = new VoiceTextBufferTest.Editor("Existing: ");
    private final StringBuilder text = model.text;

    @Before public void setup() {
        ime = spy(Robolectric.setupService(LatinIME.class));
        editor = mock(InputConnection.class);
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT; info.packageName = "test.editor"; info.fieldId = 7;
        doReturn(info).when(ime).getCurrentInputEditorInfo();
        doReturn(editor).when(ime).getCurrentInputConnection();
        doReturn(PackageManager.PERMISSION_GRANTED).when(ime).checkSelfPermission(Manifest.permission.RECORD_AUDIO);
        doReturn(null).when(ime).getWindow();
        when(editor.commitText(any(CharSequence.class), eq(1))).thenAnswer(call -> model.append(call.getArgument(0).toString()));
        when(editor.finishComposingText()).thenAnswer(call -> model.finishComposing());
        when(editor.getTextBeforeCursor(anyInt(), eq(0))).thenAnswer(call -> {
            int length = call.getArgument(0);
            return text.substring(Math.max(0, model.start - length), model.start);
        });
        when(editor.getTextAfterCursor(anyInt(), eq(0))).thenAnswer(call -> {
            int length = call.getArgument(0);
            return text.substring(model.end, Math.min(text.length(), model.end + length));
        });
        when(editor.getExtractedText(any(), eq(0))).thenAnswer(call -> {
            ExtractedText extracted = new ExtractedText();
            extracted.text = text.toString(); extracted.selectionStart = model.start; extracted.selectionEnd = model.end;
            return extracted;
        });
        voice = new VoiceInputController(ime, callback -> {
            listener = callback;
            return new VoiceDictationSession.Engine() {
                @Override public void start() { callback.ready(); }
                @Override public void close() { }
            };
        });
        start();
    }

    private void start() {
        try (MockedStatic<SpeechRecognizer> speech = mockStatic(SpeechRecognizer.class)) {
            speech.when(() -> SpeechRecognizer.isRecognitionAvailable(ime)).thenReturn(true);
            voice.toggle(Locale.ENGLISH);
        }
    }
    @After public void cleanup() { if (voice != null) voice.destroy(); }
    private void partial(String value) { listener.partial(value); }
    private void finish(String value) { listener.result(value); }
    private void restart() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(750));
    }
    private void seal() { listener.error(VoiceDictationSession.Failure.SILENCE); restart(); }

    @Test public void recognitionRequestsLongerSilenceWithoutDelayingShortPhrases() {
        Intent intent = VoiceInputController.createDictationIntent(Locale.ENGLISH);
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.getAction());
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false));
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, -1));
        assertEquals(3000, intent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, -1));
        assertEquals(5500, intent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1));
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS));
        assertEquals("en", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
    }
    @Test public void recognitionPreservesArabicAndDefaultLanguageSelection() {
        assertEquals("ar", VoiceInputController.createDictationIntent(new Locale("ar")).getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        assertFalse(VoiceInputController.createDictationIntent(null).hasExtra(RecognizerIntent.EXTRA_LANGUAGE));
    }
    @Test public void cumulativeArabicReplacesPreviewExactlyOnce() {
        partial("لكنها"); partial("لكنها في"); partial("لكنها في بعض الأوقات"); finish("لكنها في بعض الأوقات");
        assertEquals("Existing: لكنها في بعض الأوقات ", text.toString());
        verify(editor, never()).setComposingText(any(), anyInt());
        verify(editor, never()).deleteSurroundingText(anyInt(), anyInt());
    }
    @Test public void revisedEnglishFinalReplacesPartial() {
        partial("I can"); partial("I can right"); finish("I can write");
        assertEquals("Existing: I can write ", text.toString());
    }
    @Test public void repeatedPartialIsNotAppended() {
        partial("hello"); partial("hello"); partial("hello"); finish("hello");
        assertEquals("Existing: hello ", text.toString()); verify(editor, times(1)).commitText("hello ", 1);
    }
    @Test public void stopDoesNotCommitPreviewAgain() {
        partial("مرحبا"); voice.stop(); finish("مرحبا"); assertEquals("Existing: مرحبا ", text.toString());
    }
    @Test public void silenceErrorSealsOldPhraseBeforeNewArabicSpeech() {
        partial("مرحبا"); partial("مرحبا كيف"); seal(); partial("حالك"); partial("حالك اليوم"); finish("حالك اليوم");
        assertEquals("Existing: مرحبا كيف حالك اليوم ", text.toString());
        verify(editor, never()).deleteSurroundingText(anyInt(), anyInt());
    }
    @Test public void repeatedEndCallbacksDoNotAddMultipleSpaces() {
        VoiceDictationSession.Listener old = listener;
        partial("hello"); seal(); old.error(VoiceDictationSession.Failure.SILENCE);
        partial("world"); finish("world"); assertEquals("Existing: hello world ", text.toString());
    }
    @Test public void stoppingWhilePartialIsVisiblePreservesSentenceBoundary() {
        partial("first phrase"); voice.stop(); start(); partial("second phrase"); finish("second phrase");
        assertEquals("Existing: first phrase second phrase ", text.toString());
    }
    @Test public void emptySessionDoesNotInsertWhitespace() {
        seal(); voice.stop(); assertEquals("Existing: ", text.toString());
    }
    @Test public void cursorChangeNeverDeletesEarlierTextDuringSeal() {
        partial("مرحبا"); text.append("manually entered"); model.start = model.end = text.length(); seal();
        assertEquals("Existing: manually entered", text.toString());
        verify(editor, never()).deleteSurroundingText(anyInt(), anyInt());
    }
    @Test public void waveformInputIsClampedAndRejectsInvalidLevels() {
        assertEquals(0f, VoiceListeningIndicator.normalizeRms(-99f), 0f);
        assertEquals(1f, VoiceListeningIndicator.normalizeRms(99f), 0f);
        assertEquals(0f, VoiceListeningIndicator.normalizeRms(Float.NaN), 0f);
        assertEquals(0f, VoiceListeningIndicator.normalizeRms(Float.POSITIVE_INFINITY), 0f);
        assertTrue(VoiceListeningIndicator.normalizeRms(4f) > 0f);
        assertTrue(VoiceListeningIndicator.normalizeRms(4f) < 1f);
    }
    @Test public void voiceStripIsAttachedAndRemovedWithoutAlteringKeyboard() {
        FrameLayout strip = new FrameLayout(ime);
        VoiceListeningIndicator indicator = new VoiceListeningIndicator(ime, () -> {});
        indicator.showIn(strip); indicator.showIn(strip); assertEquals(1, strip.getChildCount());
        indicator.setState(VoiceDictationSession.State.LISTENING); indicator.setRms(6f);
        indicator.setState(VoiceDictationSession.State.RECONNECTING); indicator.hide(); assertEquals(0, strip.getChildCount());
    }
    @Test public void changedCursorTextIsNeverDeleted() {
        partial("hello"); text.append("manual edit"); model.start = model.end = text.length(); partial("hello world");
        assertEquals("Existing: manual edit", text.toString()); verify(editor, never()).deleteSurroundingText(anyInt(), anyInt());
    }
    @Test public void unsupportedSurroundingTextStopsSafely() {
        partial("hello"); when(editor.getTextBeforeCursor(anyInt(), eq(0))).thenReturn(null); finish("hello world");
        assertEquals("Existing: ", text.toString()); verify(editor, never()).deleteSurroundingText(anyInt(), anyInt());
    }
    @Test public void changedEditorDoesNotReceiveOldTranscript() {
        partial("hello"); InputConnection other = mock(InputConnection.class);
        doReturn(other).when(ime).getCurrentInputConnection(); finish("hello world");
        assertEquals("Existing: ", text.toString()); verifyNoInteractions(other);
    }
    @Test public void finalOnlyEngineAndNextUtteranceKeepBothPhrases() {
        finish("hello"); restart(); partial("world"); finish("world"); assertEquals("Existing: hello world ", text.toString());
    }
    @Test public void failedDeletionDoesNotAppendDuplicate() {
        // A deletion-incapable editor now works: voice never asks it to delete.
        when(editor.deleteSurroundingText(anyInt(), eq(0))).thenReturn(false);
        partial("hello"); partial("hello world"); finish("hello world");
        assertEquals("Existing: hello world ", text.toString()); verify(editor, never()).deleteSurroundingText(anyInt(), anyInt());
    }
    @Test public void stoppedSessionRejectsLateCallbacks() {
        voice.stop(); partial("stale"); finish("stale"); assertFalse(voice.isEnabled()); assertEquals("Existing: ", text.toString());
    }
    @Test public void editorOwnedStaleCompositionCannotReplaceCommittedSpeech() {
        finish("first"); restart(); model.composingStart = 10; model.composingEnd = text.length();
        partial("second"); finish("second"); assertEquals("Existing: first second ", text.toString());
    }
    @Test public void realCursorSelectionCannotAuthorizeOldSuffixReplacement() {
        finish("hello hello"); restart(); model.start = model.end = 15;
        voice.onUpdateSelection(); partial("new"); finish("new");
        assertEquals("Existing: hello hello ", text.toString()); assertFalse(voice.isEnabled());
    }
    @Test public void viewRecreationPreservesEnabledModeAndReattachesStrip() {
        partial("before"); voice.suspendForRecreation(); voice.refreshView();
        assertTrue(voice.isEnabled()); voice.onInputFinished(); assertTrue(voice.isEnabled());
        voice.onInputStarted(true); voice.onInputViewStarted(); restart();
        partial("after"); finish("after"); assertEquals("Existing: before after ", text.toString());
    }
    @Test public void hiddenKeyboardAndServiceShutdownReleaseUserMode() {
        partial("saved"); voice.onInputFinished(); voice.destroy(); restart();
        assertFalse(voice.isEnabled()); assertEquals("Existing: saved ", text.toString());
    }
    @Test public void normalErrorsDoNotBecomeUnrecoverableErrors() {
        assertEquals(VoiceDictationSession.Failure.SILENCE, VoiceInputController.classifyError(SpeechRecognizer.ERROR_NO_MATCH));
        assertEquals(VoiceDictationSession.Failure.SILENCE, VoiceInputController.classifyError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT));
        assertEquals(VoiceDictationSession.Failure.RECOVERABLE, VoiceInputController.classifyError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED));
        assertEquals(VoiceDictationSession.Failure.THROTTLED, VoiceInputController.classifyError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS));
        assertEquals(VoiceDictationSession.Failure.FATAL, VoiceInputController.classifyError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS));
    }
}
