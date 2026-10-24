// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import helium314.keyboard.latin.utils.ToolbarKey;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Platform speech API is mocked; these tests do not claim real audio transcription. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class VoiceInputControllerTest {
    private LatinIME ime;
    private SpeechRecognizer recognizer;
    private InputConnection input;
    private EditorInfo editor;
    private ImageButton mic;
    private List<Runnable> posts;

    @Before public void setup() {
        Context ctx = RuntimeEnvironment.getApplication();
        ime = mock(LatinIME.class);
        input = mock(InputConnection.class);
        recognizer = mock(SpeechRecognizer.class);
        editor = new EditorInfo();
        editor.inputType = InputType.TYPE_CLASS_TEXT;
        when(ime.getCurrentInputEditorInfo()).thenAnswer(invocation -> editor);
        when(ime.getCurrentInputConnection()).thenReturn(input);
        when(ime.getApplicationContext()).thenReturn(ctx);
        when(ime.getResources()).thenReturn(ctx.getResources());
        when(ime.getPackageName()).thenReturn(ctx.getPackageName());
        when(ime.getApplicationInfo()).thenReturn(ctx.getApplicationInfo());
        when(ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO)).thenReturn(PackageManager.PERMISSION_GRANTED);
        Dialog dialog = mock(Dialog.class);
        Window window = mock(Window.class);
        FrameLayout root = mock(FrameLayout.class);
        mic = new ImageButton(ctx);
        mic.setTag(ToolbarKey.VOICE);
        when(ime.getWindow()).thenReturn(dialog);
        when(dialog.getWindow()).thenReturn(window);
        when(window.getDecorView()).thenReturn(root);
        when(root.getChildCount()).thenReturn(1);
        when(root.getChildAt(0)).thenReturn(mic);
        posts = new ArrayList<>();
        when(root.post(any(Runnable.class))).thenAnswer(invocation -> { posts.add(invocation.getArgument(0)); return true; });
    }

    private MockedStatic<SpeechRecognizer> platform() {
        MockedStatic<SpeechRecognizer> p = mockStatic(SpeechRecognizer.class);
        p.when(() -> SpeechRecognizer.isRecognitionAvailable(ime)).thenReturn(true);
        p.when(() -> SpeechRecognizer.createSpeechRecognizer(ime)).thenReturn(recognizer);
        return p;
    }

    private RecognitionListener start(VoiceInputController controller) {
        controller.toggle(Locale.forLanguageTag("ar-YE"));
        ArgumentCaptor<RecognitionListener> listener = ArgumentCaptor.forClass(RecognitionListener.class);
        verify(recognizer).setRecognitionListener(listener.capture());
        return listener.getValue();
    }

    private Bundle result(String value) {
        Bundle b = new Bundle();
        ArrayList<String> values = new ArrayList<>(); values.add(value);
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, values);
        return b;
    }

    @Test public void keepsExistingEngineIntentAndCommitsFinalResultOnce() {
        try (MockedStatic<SpeechRecognizer> p = platform()) {
            VoiceInputController c = new VoiceInputController(ime);
            RecognitionListener listener = start(c);
            ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
            verify(recognizer).startListening(intent.capture());
            assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.getValue().getAction());
            assertEquals("ar-YE", intent.getValue().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
            assertFalse(intent.getValue().getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true));
            listener.onPartialResults(result("hello"));
            verify(input, never()).commitText(any(), anyInt());
            listener.onResults(result(" hello "));
            listener.onResults(result("hello"));
            verify(input, times(1)).commitText("hello", 1);
            verify(recognizer, times(1)).destroy();
        }
    }

    @Test public void changedEditorOrPasswordCannotReceiveResults() {
        try (MockedStatic<SpeechRecognizer> p = platform()) {
            VoiceInputController c = new VoiceInputController(ime);
            RecognitionListener listener = start(c);
            when(ime.getCurrentInputConnection()).thenReturn(mock(InputConnection.class));
            listener.onResults(result("private"));
            verify(input, never()).commitText(any(), anyInt());
        }
    }

    @Test public void allPasswordVariationsBlockTheMicrophone() {
        try (MockedStatic<SpeechRecognizer> p = platform()) {
            int[] types = {InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD};
            for (int type : types) { editor.inputType = type; new VoiceInputController(ime).toggle(Locale.US); }
            p.verify(() -> SpeechRecognizer.createSpeechRecognizer(ime), never());
        }
    }

    @Test public void nullResultsAndProviderDestroyFailureAreSafe() {
        try (MockedStatic<SpeechRecognizer> p = platform()) {
            VoiceInputController c = new VoiceInputController(ime);
            RecognitionListener listener = start(c);
            doThrow(new IllegalStateException("provider already gone")).when(recognizer).cancel();
            doThrow(new IllegalStateException("provider already gone")).when(recognizer).destroy();
            listener.onResults(null);
            c.destroy();
            listener.onResults(result("late"));
            verify(input, never()).commitText(any(), anyInt());
        }
    }

    @Test public void staleIconPostsDoNotOverrideNewListeningSession() {
        try (MockedStatic<SpeechRecognizer> p = platform()) {
            VoiceInputController c = new VoiceInputController(ime);
            c.toggle(Locale.US);
            List<Runnable> oldPosts = new ArrayList<>(posts); posts.clear();
            c.stop();
            oldPosts.addAll(posts); posts.clear();
            c.toggle(Locale.US);
            for (Runnable r : posts) r.run();
            assertNotNull(mic.getColorFilter());
            for (Runnable r : oldPosts) r.run();
            assertNotNull(mic.getColorFilter());
            posts.clear(); c.stop();
            for (Runnable r : posts) r.run();
            assertNull(mic.getColorFilter());
        }
    }
}
