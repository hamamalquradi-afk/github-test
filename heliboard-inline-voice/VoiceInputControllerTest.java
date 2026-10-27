package helium314.keyboard.latin;

import android.content.Intent;
import android.widget.FrameLayout;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import helium314.keyboard.ShadowInputMethodManager2;
import helium314.keyboard.ShadowProximityInfo;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise the shipped controller against an editor that does not keep composing spans. */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowInputMethodManager2.class, ShadowProximityInfo.class})
public class VoiceInputControllerTest {
    private VoiceInputController voice;
    private LatinIME ime;
    private InputConnection editor;
    private final StringBuilder text = new StringBuilder("Existing: ");

    @Before public void setup() throws Exception {
        ime = spy(Robolectric.setupService(LatinIME.class));
        editor = mock(InputConnection.class);
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT;
        doReturn(info).when(ime).getCurrentInputEditorInfo();
        doReturn(editor).when(ime).getCurrentInputConnection();
        doReturn(null).when(ime).getWindow();
        when(editor.commitText(any(CharSequence.class), eq(1))).thenAnswer(call -> {
            text.append((CharSequence) call.getArgument(0));
            return true;
        });
        when(editor.getTextBeforeCursor(anyInt(), eq(0))).thenAnswer(call -> {
            int length = call.getArgument(0);
            return text.substring(Math.max(0, text.length() - length));
        });
        when(editor.deleteSurroundingText(anyInt(), eq(0))).thenAnswer(call -> {
            int length = call.getArgument(0);
            text.delete(text.length() - length, text.length());
            return true;
        });
        voice = new VoiceInputController(ime);
        set("targetConnection", editor);
        set("enabled", true);
    }

    @After public void cleanup() { if (voice != null) voice.destroy(); }

    private void set(String name, Object value) throws Exception {
        Field field = VoiceInputController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(voice, value);
    }

    private Object call(String name, Class<?> type, Object value) throws Exception {
        Method method = VoiceInputController.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(voice, value);
    }

    private void partial(String value) throws Exception { call("showInterimResult", String.class, value); }
    private void finish(String value) throws Exception { call("commitFinalResult", String.class, value); }

    private void seal() throws Exception {
        Method method = VoiceInputController.class.getDeclaredMethod("sealPreviewAsPhrase");
        method.setAccessible(true);
        method.invoke(voice);
    }

    @Test public void recognitionRequestsLongerSilenceWithoutDelayingShortPhrases() {
        Intent intent = VoiceInputController.createDictationIntent(Locale.ENGLISH);
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.getAction());
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false));
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, -1));
        assertEquals(3000, intent.getIntExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, -1));
        assertEquals(5500, intent.getIntExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1));
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS));
        assertEquals("en", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
    }

    @Test public void recognitionPreservesArabicAndDefaultLanguageSelection() {
        Intent arabic = VoiceInputController.createDictationIntent(new Locale("ar"));
        assertEquals("ar", arabic.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        Intent systemDefault = VoiceInputController.createDictationIntent(null);
        assertFalse(systemDefault.hasExtra(RecognizerIntent.EXTRA_LANGUAGE));
        assertEquals(5500, systemDefault.getIntExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1));
    }

    @Test public void cumulativeArabicReplacesPreviewExactlyOnce() throws Exception {
        partial("لكنها"); partial("لكنها في"); partial("لكنها في بعض الأوقات");
        finish("لكنها في بعض الأوقات");
        assertEquals("Existing: لكنها في بعض الأوقات ", text.toString());
        verify(editor, never()).setComposingText(any(), anyInt());
    }

    @Test public void revisedEnglishFinalReplacesPartial() throws Exception {
        partial("I can"); partial("I can right"); finish("I can write");
        assertEquals("Existing: I can write ", text.toString());
    }

    @Test public void repeatedPartialIsNotAppended() throws Exception {
        partial("hello"); partial("hello"); partial("hello"); finish("hello");
        assertEquals("Existing: hello ", text.toString());
        verify(editor, times(1)).commitText("hello", 1);
    }

    @Test public void stopDoesNotCommitPreviewAgain() throws Exception {
        partial("مرحبا"); voice.stop(); finish("مرحبا");
        assertEquals("Existing: مرحبا ", text.toString());
    }

    @Test public void silenceErrorSealsOldPhraseBeforeNewArabicSpeech() throws Exception {
        partial("مرحبا"); partial("مرحبا كيف");
        seal(); // same as an end-of-session without onResults
        partial("حالك"); partial("حالك اليوم");
        finish("حالك اليوم");
        assertEquals("Existing: مرحبا كيف حالك اليوم ", text.toString());
        verify(editor, times(1)).deleteSurroundingText(eq("مرحبا".length()), eq(0));
    }

    @Test public void repeatedEndCallbacksDoNotAddMultipleSpaces() throws Exception {
        partial("hello");
        seal();
        seal();
        partial("world");
        finish("world");
        assertEquals("Existing: hello world ", text.toString());
    }

    @Test public void stoppingWhilePartialIsVisiblePreservesSentenceBoundary() throws Exception {
        partial("first phrase");
        voice.stop();
        set("targetConnection", editor);
        set("enabled", true);
        partial("second phrase");
        finish("second phrase");
        assertEquals("Existing: first phrase second phrase ", text.toString());
    }

    @Test public void emptySessionDoesNotInsertWhitespace() throws Exception {
        seal();
        voice.stop();
        assertEquals("Existing: ", text.toString());
    }

    @Test public void cursorChangeNeverDeletesEarlierTextDuringSeal() throws Exception {
        partial("مرحبا");
        text.append(" manually entered");
        seal();
        assertEquals("Existing: مرحبا manually entered", text.toString());
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
        final FrameLayout strip = new FrameLayout(ime);
        final VoiceListeningIndicator indicator = new VoiceListeningIndicator(ime, () -> {});
        indicator.showIn(strip);
        indicator.showIn(strip);
        assertEquals(1, strip.getChildCount());
        indicator.setState(2);
        indicator.setRms(6f);
        indicator.setState(1);
        indicator.hide();
        assertEquals(0, strip.getChildCount());
    }

    @Test public void changedCursorTextIsNeverDeleted() throws Exception {
        partial("hello"); text.append(" manual edit"); partial("hello world");
        assertEquals("Existing: hello manual edit", text.toString());
        verify(editor, never()).deleteSurroundingText(anyInt(), anyInt());
    }

    @Test public void unsupportedSurroundingTextStopsSafely() throws Exception {
        partial("hello"); when(editor.getTextBeforeCursor(anyInt(), eq(0))).thenReturn(null);
        finish("hello world");
        assertEquals("Existing: hello", text.toString());
        verify(editor, never()).deleteSurroundingText(anyInt(), anyInt());
    }

    @Test public void changedEditorDoesNotReceiveOldTranscript() throws Exception {
        partial("hello"); InputConnection other = mock(InputConnection.class);
        doReturn(other).when(ime).getCurrentInputConnection(); finish("hello world");
        assertEquals("Existing: hello", text.toString());
        verifyNoInteractions(other);
    }

    @Test public void finalOnlyEngineAndNextUtteranceKeepBothPhrases() throws Exception {
        finish("hello"); partial("world"); finish("world");
        assertEquals("Existing: hello world ", text.toString());
    }

    @Test public void failedDeletionDoesNotAppendDuplicate() throws Exception {
        partial("hello"); when(editor.deleteSurroundingText(anyInt(), eq(0))).thenReturn(false);
        partial("hello world"); assertEquals("Existing: hello", text.toString());
        verify(editor).endBatchEdit();
    }

    @Test public void stoppedSessionRejectsLateCallbacks() throws Exception {
        assertEquals(true, call("isCurrentSession", int.class, 0));
        voice.stop();
        assertEquals(false, call("isCurrentSession", int.class, 0));
    }
}
