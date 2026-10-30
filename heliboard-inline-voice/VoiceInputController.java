/*
 * KEYNOVA SMART: user-controlled, session-managed voice dictation.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.Nullable;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.latin.utils.ToolbarKey;
import java.util.ArrayList;
import java.util.Locale;

/** Android adapter. SpeechRecognizer is an endpointer-controlled service, not
 * an uninterrupted recording API. A single active generation is reconnected
 * with backoff; only provider callbacks indicate listening/speech/audio levels.
 * No AudioRecord competes for the microphone, no audio is stored or uploaded
 * by this application, and the installed recognition provider remains in use. */
final class VoiceInputController {
    private static final String TAG = "KeynovaVoice";
    private final LatinIME ime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final VoiceDictationSession session;
    @Nullable private InputConnection targetConnection;
    @Nullable private EditorInfo targetEditor;
    @Nullable private VoiceTextBuffer textBuffer;
    @Nullable private Locale selectedLocale;
    @Nullable private VoiceListeningIndicator indicator;
    @Nullable private String targetPackage;
    private int targetField;
    private int targetType;
    private boolean recreating;
    private boolean writing;
    private String partial = "";
    private int uiRevision;
    private final Runnable recreationTimeout = () -> { recreating = false; stop(); };

    VoiceInputController(LatinIME ime) {
        this(ime, null);
    }

    VoiceInputController(LatinIME ime, @Nullable VoiceDictationSession.Factory factory) {
        this.ime = ime;
        session = new VoiceDictationSession(new VoiceDictationSession.Scheduler() {
            @Override public void post(Runnable task, long delay) { handler.postDelayed(task, delay); }
            @Override public void remove(Runnable task) { handler.removeCallbacks(task); }
        }, factory == null ? this::createEngine : factory, new VoiceDictationSession.Host() {
            @Override public boolean valid() { return isTargetActive() && textBuffer != null && textBuffer.valid(); }
            @Override public boolean append(String phrase) {
                writing = true;
                boolean accepted = false;
                try {
                    accepted = textBuffer != null && textBuffer.append(phrase);
                    return accepted;
                } finally {
                    writing = false;
                    // Never finish upstream composing state after a rejected commit.
                    if (accepted) syncInputLogic();
                }
            }
            @Override public void partial(String phrase) {
                // Hypotheses are displayed inside the active editor, never
                // as a message draft in the suggestion/toolbar strip.
                if (!phrase.isEmpty() && textBuffer != null) {
                    writing = true;
                    boolean accepted;
                    try { accepted = textBuffer.preview(phrase); }
                    finally { writing = false; }
                    if (!accepted) { session.abort(); return; }
                }
                partial = "";
                refreshView();
            }
            @Override public void state(VoiceDictationSession.State state) {
                render(state);
                if (state == VoiceDictationSession.State.ERROR) {
                    if (textBuffer != null) textBuffer.abandon();
                    recreating = false;
                    handler.removeCallbacks(recreationTimeout);
                    targetConnection = null;
                    targetEditor = null;
                    textBuffer = null;
                    selectedLocale = null;
                    message("توقف الإملاء لحماية النص أو لخطأ في خدمة الصوت؛ اضغط للمحاولة");
                }
            }
            @Override public void rms(float level) { if (indicator != null) indicator.setRms(level); }
        });
    }

    void toggle(@Nullable Locale locale) {
        if (session.isEnabled()) stop();
        else start(locale);
    }

    private void start(@Nullable Locale locale) {
        EditorInfo editor = ime.getCurrentInputEditorInfo();
        InputConnection connection = ime.getCurrentInputConnection();
        if (editor == null || connection == null) { message("لا يوجد حقل نصي نشط"); return; }
        if (isSensitiveField(editor)) { message("الإملاء متوقف في الحقول الحساسة"); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Intent permission = new Intent(ime, VoicePermissionActivity.class);
            permission.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ime.startActivity(permission);
            message("اسمح بالميكروفون ثم فعّل الإملاء");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ime)) { message("لا تتوفر خدمة تعرف صوتي"); return; }
        targetConnection = connection;
        targetEditor = editor;
        targetPackage = editor.packageName;
        targetField = editor.fieldId;
        targetType = editor.inputType;
        selectedLocale = locale;
        syncInputLogic();
        textBuffer = new VoiceTextBuffer(new VoiceTextBuffer.Editor() {
            @Override public VoiceTextBuffer.Snapshot snapshot() { return readSnapshot(); }
            @Override public boolean finishComposing() {
                return isTargetActive() && targetConnection.finishComposingText();
            }
            @Override public boolean append(String text) {
                return isTargetActive() && targetConnection.commitText(text, 1);
            }
            @Override public boolean compose(String text) {
                return isTargetActive() && targetConnection.setComposingText(text, 1);
            }
            @Override public boolean ownsComposition(int start, int end) {
                if (!isTargetActive() || end <= start) return false;
                // Require the exact Android composing span, not merely a
                // visually identical suffix that might be previously sealed.
                CharSequence styled = targetConnection.getTextBeforeCursor(
                        Math.min(4096, end - start), InputConnection.GET_TEXT_WITH_STYLES);
                if (!(styled instanceof Spanned) || styled.length() != end - start) return false;
                Spanned spans = (Spanned) styled;
                for (Object span : spans.getSpans(0, spans.length(), Object.class)) {
                    if ((spans.getSpanFlags(span) & Spanned.SPAN_COMPOSING) != 0
                            && spans.getSpanStart(span) == 0
                            && spans.getSpanEnd(span) == spans.length()) return true;
                }
                return false;
            }
        });
        if (!textBuffer.valid()) {
            message("لا يدعم الحقل التحقق من موضع النص بأمان");
            targetConnection = null;
            targetEditor = null;
            textBuffer = null;
            return;
        }
        session.start();
    }

    /** The only recognition-service instance in this controller. All creation,
     * callbacks, retirement and retry timers run on the main looper. */
    private VoiceDictationSession.Engine createEngine(VoiceDictationSession.Listener listener) {
        final SpeechRecognizer recognizer;
        try { recognizer = SpeechRecognizer.createSpeechRecognizer(ime); }
        catch (RuntimeException error) {
            Log.w(TAG, "createSpeechRecognizer failed: " + error.getClass().getSimpleName());
            if (error instanceof SecurityException) listener.error(VoiceDictationSession.Failure.FATAL);
            throw error;
        }
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { listener.ready(); }
            @Override public void onBeginningOfSpeech() { listener.speech(); }
            @Override public void onRmsChanged(float rmsdB) { listener.rms(rmsdB); }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { listener.endpoint(); }
            @Override public void onPartialResults(Bundle results) { listener.partial(bestResult(results)); }
            @Override public void onResults(Bundle results) { listener.result(bestResult(results)); }
            @Override public void onEvent(int type, Bundle params) { }
            @Override public void onError(int error) {
                Log.w(TAG, "recognition callback error=" + error);
                listener.error(classifyError(error));
            }
        });
        return new VoiceDictationSession.Engine() {
            @Override public void start() {
                try { recognizer.startListening(createDictationIntent(selectedLocale)); }
                catch (RuntimeException error) {
                    Log.w(TAG, "startListening failed: " + error.getClass().getSimpleName());
                    if (error instanceof SecurityException) listener.error(VoiceDictationSession.Failure.FATAL);
                    throw error;
                }
            }
            @Override public void close() {
                try { recognizer.cancel(); }
                catch (RuntimeException error) { Log.w(TAG, "cancel failed: " + error.getClass().getSimpleName()); }
                try { recognizer.destroy(); }
                catch (RuntimeException error) { Log.w(TAG, "destroy failed: " + error.getClass().getSimpleName()); }
            }
        };
    }

    static VoiceDictationSession.Failure classifyError(int error) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return VoiceDictationSession.Failure.FATAL;
        if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
            return VoiceDictationSession.Failure.THROTTLED;
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            return VoiceDictationSession.Failure.SILENCE;
        return VoiceDictationSession.Failure.RECOVERABLE;
    }

    static Intent createDictationIntent(@Nullable Locale locale) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // Optional hints only. Continuity is implemented by the session manager.
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5500);
        if (locale != null) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag());
        return intent;
    }

    private static String bestResult(@Nullable Bundle result) {
        if (result == null) return "";
        ArrayList<String> values = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values == null || values.isEmpty() || values.get(0) == null ? "" : values.get(0).trim();
    }

    private boolean sameField(@Nullable EditorInfo editor) {
        return editor != null && java.util.Objects.equals(targetPackage, editor.packageName)
                && targetField == editor.fieldId && targetType == editor.inputType && !isSensitiveField(editor);
    }

    private boolean isTargetActive() {
        if (targetConnection == null) return false;
        EditorInfo info = ime.getCurrentInputEditorInfo();
        if (!sameField(info) || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED)) return false;
        InputConnection current = ime.getCurrentInputConnection();
        if (current == targetConnection) return true;
        // A wrapper may change without changing the active editor. Never infer
        // this from matching text alone: require the original EditorInfo object,
        // a concrete field ID and the exact absolute caret/context checkpoint.
        // A different editor, ambiguous ID or changed selection fails closed.
        if (current == null || info != targetEditor || targetField <= 0
                || textBuffer == null || !textBuffer.matchesSnapshot(readSnapshotFrom(current))) return false;
        targetConnection = current;
        Log.i(TAG, "rebound verified InputConnection for existing editor");
        return true;
    }

    /** The only mutable text is a bounded, verified Android composing span.
     * A moved caret or changed editor fails closed; finalized text is immutable. */
    @Nullable private VoiceTextBuffer.Snapshot readSnapshot() {
        if (!isTargetActive()) return null;
        return readSnapshotFrom(targetConnection);
    }

    @Nullable private VoiceTextBuffer.Snapshot readSnapshotFrom(InputConnection connection) {
        ExtractedTextRequest request = new ExtractedTextRequest();
        request.hintMaxChars = 2 * VoiceTextBuffer.CONTEXT;
        ExtractedText extracted = connection.getExtractedText(request, 0);
        if (extracted == null || extracted.selectionStart < 0
                || extracted.selectionStart != extracted.selectionEnd || extracted.startOffset < 0) return null;
        CharSequence before = connection.getTextBeforeCursor(VoiceTextBuffer.CONTEXT, 0);
        CharSequence after = connection.getTextAfterCursor(VoiceTextBuffer.CONTEXT, 0);
        if (before == null || after == null) return null;
        return new VoiceTextBuffer.Snapshot(extracted.startOffset + extracted.selectionStart,
                before.toString(), after.toString());
    }

    private void syncInputLogic() {
        if (!isTargetActive()) return;
        ime.mHandler.cancelResumeSuggestions();
        ime.mHandler.cancelUpdateSuggestionStrip();
        ime.mInputLogic.finishInput();
        VoiceTextBuffer.Snapshot snapshot = readSnapshot();
        if (snapshot != null) ime.mInputLogic.mConnection.resetCachesUponCursorMoveAndReturnSuccess(
                snapshot.cursor, snapshot.cursor, true);
    }

    /** Return true to bypass recorrection while voice owns the editor. */
    boolean onUpdateSelection() {
        if (!session.isEnabled()) return false;
        // commitText / setComposingText can dispatch selection synchronously before
        // the new anchor has been checked. Finishing upstream input here can
        // interfere with the in-flight commit; synchronize once after append.
        if (writing) return true;
        if (textBuffer == null || !isTargetActive() || !textBuffer.valid()) {
            session.abort();
            return false; // Let upstream process the selection after voice abort.
        }
        return true;
    }

    void suspendForRecreation() {
        if (!session.isEnabled()) return;
        recreating = true;
        session.pause();
        handler.removeCallbacks(recreationTimeout);
        handler.postDelayed(recreationTimeout, 5000);
    }

    void onInputStarted(boolean restarting) {
        if (!session.isEnabled()) return;
        if (!recreating || !restarting || !sameField(ime.getCurrentInputEditorInfo())) { stop(); return; }
        targetConnection = ime.getCurrentInputConnection();
        targetEditor = ime.getCurrentInputEditorInfo();
        if (targetField <= 0 || textBuffer == null || !textBuffer.valid()) { session.abort(); return; }
        // Resume only when the input VIEW is ready, not in a hidden editor.
    }

    void onInputViewStarted() {
        if (recreating && session.isEnabled()) {
            recreating = false;
            handler.removeCallbacks(recreationTimeout);
            if (isTargetActive() && textBuffer != null && textBuffer.valid()) {
                syncInputLogic();
                session.resume();
            } else session.abort();
        }
        refreshView();
    }

    void onInputFinished() { if (!recreating) stop(); }
    boolean isEnabled() { return session.isEnabled(); }

    void stop() {
        recreating = false;
        handler.removeCallbacks(recreationTimeout);
        session.stop();
        targetConnection = null;
        targetEditor = null;
        textBuffer = null;
        selectedLocale = null;
    }

    void destroy() {
        stop();
        handler.removeCallbacksAndMessages(null);
        if (indicator != null) indicator.hide();
        indicator = null;
    }

    private static boolean isSensitiveField(@Nullable EditorInfo editor) {
        if (editor == null) return true;
        int klass = editor.inputType & InputType.TYPE_MASK_CLASS;
        int variation = editor.inputType & InputType.TYPE_MASK_VARIATION;
        return klass == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    void refreshView() { render(session.state()); }

    private void render(VoiceDictationSession.State state) {
        final int revision = ++uiRevision;
        // setInputView/theme reload can replace the strip without a recognition
        // callback. Reattach the same user session after the view is laid out.
        handler.post(() -> {
            if (revision != uiRevision) return;
            if (state == VoiceDictationSession.State.STOPPED) {
                if (indicator != null) indicator.hide();
                indicator = null;
            } else {
                FrameLayout strip = KeyboardSwitcher.getInstance().getStripContainer();
                if (strip != null && strip.getVisibility() == View.VISIBLE) {
                    if (indicator == null) indicator = new VoiceListeningIndicator(ime, () -> toggle(null));
                    indicator.showIn(strip);
                    indicator.setState(state);
                    indicator.setTranscript(partial);
                }
            }
            if (ime.getWindow() == null || ime.getWindow().getWindow() == null) return;
            View root = ime.getWindow().getWindow().getDecorView();
            ImageButton mic = root == null ? null : findVoiceButton(root);
            if (mic == null) return;
            boolean listening = state == VoiceDictationSession.State.LISTENING || state == VoiceDictationSession.State.SPEECH;
            if (state == VoiceDictationSession.State.STOPPED) mic.clearColorFilter();
            else mic.setColorFilter(state == VoiceDictationSession.State.ERROR ? Color.RED
                    : listening ? Color.rgb(28, 181, 89) : Color.rgb(240, 160, 38), PorterDuff.Mode.SRC_IN);
            mic.setContentDescription(VoiceListeningIndicator.description(state));
        });
    }

    @Nullable private static ImageButton findVoiceButton(View root) {
        if (root instanceof ImageButton && root.getTag() == ToolbarKey.VOICE) return (ImageButton) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageButton found = findVoiceButton(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void message(String text) { Toast.makeText(ime, text, Toast.LENGTH_SHORT).show(); }
}
