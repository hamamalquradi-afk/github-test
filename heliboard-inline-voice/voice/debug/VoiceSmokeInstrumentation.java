/* SPDX-License-Identifier: GPL-3.0-only */
package helium314.keyboard.latin;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;

/** Real Android Editable/InputConnection regression. The provider is scripted;
 * this test makes NO claim about emulator microphone or real speech accuracy. */
public final class VoiceSmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            runOnMainSync(this::verify);
            report.putString("stream", "VOICE_SMOKE_PASS: real Android InputConnection; scripted provider; physical microphone NOT TESTED\n");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable error) {
            report.putString("stream", "VOICE_SMOKE_FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, report);
        }
    }

    private void verify() {
        EditText editor = new EditText(new ContextThemeWrapper(getTargetContext(), android.R.style.Theme_Material_Light));
        editor.setText("Existing: "); editor.setSelection(editor.length());
        InputConnection connection = editor.onCreateInputConnection(new EditorInfo());
        check(connection != null, "missing real editor connection");
        VoiceTextBuffer buffer = new VoiceTextBuffer(new VoiceTextBuffer.Editor() {
            @Override public VoiceTextBuffer.Snapshot snapshot() {
                ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
                if (extracted == null || extracted.selectionStart != extracted.selectionEnd) return null;
                return new VoiceTextBuffer.Snapshot(extracted.startOffset + extracted.selectionStart,
                        connection.getTextBeforeCursor(128, 0).toString(), connection.getTextAfterCursor(128, 0).toString());
            }
            @Override public boolean finishComposing() { return connection.finishComposingText(); }
            @Override public boolean append(String value) { return connection.commitText(value, 1); }
            @Override public boolean compose(String value) { return connection.setComposingText(value, 1); }
            @Override public boolean ownsComposition(int start, int end) {
                return BaseInputConnection.getComposingSpanStart(editor.getText()) == start
                        && BaseInputConnection.getComposingSpanEnd(editor.getText()) == end;
            }
        });
        Clock clock = new Clock();
        VoiceDictationSession.Listener[] current = new VoiceDictationSession.Listener[1];
        int[] active = {0};
        VoiceDictationSession voice = new VoiceDictationSession(clock, listener -> {
            current[0] = listener;
            return new VoiceDictationSession.Engine() {
                @Override public void start() { check(++active[0] == 1, "concurrent recognizers"); listener.ready(); }
                @Override public void close() { check(--active[0] == 0, "recognizer released twice"); }
            };
        }, new VoiceDictationSession.Host() {
            @Override public boolean valid() { return buffer.valid(); }
            @Override public boolean append(String phrase) { return buffer.append(phrase); }
            @Override public void partial(String phrase) {
                if (!phrase.isEmpty()) check(buffer.preview(phrase), "live composing span rejected");
            }
            @Override public void state(VoiceDictationSession.State state) { }
            @Override public void rms(float level) { }
        });
        voice.start();
        current[0].partial("مرحبا كيف");
        check(editor.getText().toString().equals("Existing: مرحبا كيف"),
                "partial did not appear directly in the actual Android EditText");
        current[0].result("مرحبا كيف حالك"); clock.advance(350);
        check(editor.getText().toString().equals("Existing: مرحبا كيف حالك"), "first phrase incorrect");
        // A real composing span over previous speech used to make commitText
        // overwrite it. Append must finish that span before any insertion.
        connection.setComposingRegion(10, editor.length());
        current[0].partial("اليوم سوف نجرب"); current[0].endpoint();
        clock.advance(2000); clock.advance(1000);
        check(editor.getText().toString().equals("Existing: مرحبا كيف حالك اليوم سوف نجرب"), "previous Arabic phrase replaced");
        current[0].partial("I can right"); current[0].result("I can write"); clock.advance(350);
        String expected = "Existing: مرحبا كيف حالك اليوم سوف نجرب I can write";
        check(editor.getText().toString().equals(expected), "revised final duplicated or lost text");
        // The punctuation is deliberately supplied in its own provider session:
        // an eager trailing separator would produce an invalid "word ،".
        current[0].result("،"); clock.advance(350);
        expected += "،";
        check(editor.getText().toString().equals(expected), "punctuation was separated");
        current[0].result("next"); clock.advance(350);
        expected += " next";
        check(editor.getText().toString().equals(expected), "missing phrase separator");
        for (int i = 0; i < 5; i++) {
            current[0].error(VoiceDictationSession.Failure.SILENCE);
            clock.advance(750);
            current[0].partial("word" + i);
            current[0].result("word" + i);
            clock.advance(350);
            expected += " word" + i;
            check(editor.getText().toString().equals(expected), "old text lost in cycle " + i);
        }
        current[0].partial("must not overwrite");
        check(editor.getText().toString().equals(expected + " must not overwrite"),
                "live partial missing from real editor");
        editor.setSelection(3);
        current[0].result("unsafe");
        check(editor.getText().toString().equals(expected + " must not overwrite"),
                "moved caret modified previously sealed text");
        check(!voice.isEnabled() && active[0] == 0, "unsafe editor did not stop capture");
        FrameLayout first = new FrameLayout(getTargetContext());
        FrameLayout recreated = new FrameLayout(getTargetContext());
        VoiceListeningIndicator indicator = new VoiceListeningIndicator(getTargetContext(), () -> {});
        indicator.showIn(first); indicator.setState(VoiceDictationSession.State.SPEECH); indicator.setRms(5);
        indicator.showIn(recreated); indicator.setState(VoiceDictationSession.State.RECONNECTING);
        check(first.getChildCount() == 0 && recreated.getChildCount() == 1, "voice strip not reattached");
        indicator.hide(); check(recreated.getChildCount() == 0, "voice strip leak");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Clock implements VoiceDictationSession.Scheduler {
        final List<Task> tasks = new ArrayList<>(); long now;
        @Override public void post(Runnable action, long delay) { tasks.add(new Task(action, now + delay)); }
        @Override public void remove(Runnable action) { tasks.removeIf(task -> task.action == action); }
        void advance(long delay) {
            long end = now + delay;
            while (true) {
                Task next = null;
                for (Task task : tasks) if (task.at <= end && (next == null || task.at < next.at)) next = task;
                if (next == null) break;
                tasks.remove(next); now = next.at; next.action.run();
            }
            now = end;
        }
        private static final class Task {
            final Runnable action; final long at;
            Task(Runnable action, long at) { this.action = action; this.at = at; }
        }
    }
}
