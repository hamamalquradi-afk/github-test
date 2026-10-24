package helium314.keyboard.latin;

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
        assertEquals("Existing: مرحبا", text.toString());
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
