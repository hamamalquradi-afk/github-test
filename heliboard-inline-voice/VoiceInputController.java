/*
 * HeliBoard inline voice dictation: safe interim replacement and recovery.
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
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.Nullable;
import helium314.keyboard.latin.utils.ToolbarKey;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Each partial hypothesis is a REPLACEMENT, not an additional phrase.
 *
 * Some third-party editors do not reliably preserve the composing span used by
 * setComposingText(). Therefore insert a single, plain-text preview and ONLY
 * replace it after confirming that the exact previously inserted text is still
 * immediately before the cursor. If that verification fails, stop instead of
 * risking an accidental duplicate or deletion of somebody else's text.
 *
 * Recognition engines often stop after a phrase; the user-controlled dictation
 * mode restarts them. Watchdogs avoid a green-but-dead microphone. Android's
 * installed speech service can still impose audio/time/network limits.
 */
final class VoiceInputController {
    private static final long NEXT_UTTERANCE_DELAY_MS = 600L;
    // Endpointer hints for a dictated sentence with natural pauses. The service
    // may ignore them; automatic resumption after onResults/onError remains on.
    private static final int POSSIBLY_COMPLETE_SILENCE_MS = 3000;
    private static final int COMPLETE_SILENCE_MS = 5500;
    private static final long SILENCE_RETRY_DELAY_MS = 1100L;
    private static final long READY_TIMEOUT_MS = 12000L;
    private static final long QUIET_TIMEOUT_MS = 24000L;
    private static final long ACTIVE_TIMEOUT_MS = 45000L;
    private static final long FINAL_RESULT_TIMEOUT_MS = 10000L;
    private static final int MAX_ENGINE_ERRORS = 3;
    private static final int MAX_STALLED_SESSIONS = 3;

    private static final int ICON_OFF = 0;
    private static final int ICON_RESTARTING = 1;
    private static final int ICON_LISTENING = 2;

    private final LatinIME ime;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private SpeechRecognizer recognizer;
    @Nullable private InputConnection targetConnection;
    @Nullable private Locale selectedLocale;
    @Nullable private Runnable pendingRestart;
    @Nullable private Runnable pendingWatchdog;

    private boolean enabled;
    private boolean hasPreview;
    private String previewText = "";
    private int generation;
    private int iconGeneration;
    private int consecutiveEngineErrors;
    private int consecutiveStalls;

    VoiceInputController(LatinIME ime) {
        this.ime = ime;
    }

    void toggle(@Nullable Locale locale) {
        if (enabled) {
            stop();
            message("الميكروفون متوقف");
        } else {
            start(locale);
        }
    }

    private void start(@Nullable Locale locale) {
        final EditorInfo editor = ime.getCurrentInputEditorInfo();
        final InputConnection connection = ime.getCurrentInputConnection();
        if (editor == null || connection == null) {
            message("لا يوجد حقل نصي نشط");
            return;
        }
        if (isSensitiveField(editor)) {
            message("الإملاء الصوتي متوقف في حقول الإدخال الحساسة");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
            final Intent permission = new Intent(ime, VoicePermissionActivity.class);
            permission.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ime.startActivity(permission);
            message("اسمح باستخدام الميكروفون، ثم اضغط زر الصوت مجددًا");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ime)) {
            message("لا تتوفر خدمة تعرف صوتي مثبتة على هذا الجهاز");
            return;
        }

        targetConnection = connection;
        selectedLocale = locale;
        consecutiveEngineErrors = 0;
        consecutiveStalls = 0;
        enabled = true;
        updateMicIcon(ICON_RESTARTING);
        message("🎤 الإملاء مفعّل — اضغط زر الصوت لإيقافه");
        beginRecognition();
    }

    private void beginRecognition() {
        if (!enabled) return;
        if (!isTargetActive()) {
            stop();
            return;
        }
        disposeRecognizer();
        final int session = ++generation;
        updateMicIcon(ICON_RESTARTING);
        try {
            final SpeechRecognizer next = SpeechRecognizer.createSpeechRecognizer(ime);
            recognizer = next;
            next.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    if (!isCurrentSession(session)) return;
                    updateMicIcon(ICON_LISTENING);
                    armWatchdog(session, QUIET_TIMEOUT_MS);
                }
                @Override public void onBeginningOfSpeech() {
                    if (!isCurrentSession(session)) return;
                    updateMicIcon(ICON_LISTENING);
                    armWatchdog(session, ACTIVE_TIMEOUT_MS);
                }
                @Override public void onRmsChanged(float rmsdB) {
                    // RMS updates may continue indefinitely even when the
                    // recognition service is stalled; never extend watchdog here.
                }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    if (!isCurrentSession(session)) return;
                    updateMicIcon(ICON_RESTARTING);
                    armWatchdog(session, FINAL_RESULT_TIMEOUT_MS);
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    if (!isCurrentSession(session)) return;
                    final String partial = bestResult(partialResults);
                    if (!partial.isEmpty() && showInterimResult(partial)) {
                        consecutiveStalls = 0;
                        armWatchdog(session, ACTIVE_TIMEOUT_MS);
                    }
                }
                @Override public void onEvent(int eventType, Bundle params) { }
                @Override public void onError(int error) {
                    if (!isCurrentSession(session)) return;
                    // The preview is already visible as ordinary text.
                    // Never commit it a second time on error.
                    clearPreviewTracking();
                    endSession();
                    if (!enabled) return;
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                            || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                            || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                        stopWithMessage("خدمة الصوت أوقفت الإملاء (رمز " + error + ")");
                    } else if (error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        consecutiveEngineErrors = 0;
                        consecutiveStalls = 0;
                        scheduleRestart(SILENCE_RETRY_DELAY_MS);
                    } else if (++consecutiveEngineErrors >= MAX_ENGINE_ERRORS) {
                        stopWithMessage("توقف الصوت بسبب أخطاء متكررة (رمز " + error + ")");
                    } else {
                        scheduleRestart(SILENCE_RETRY_DELAY_MS * consecutiveEngineErrors);
                    }
                }
                @Override public void onResults(Bundle results) {
                    if (!isCurrentSession(session)) return;
                    final String result = bestResult(results);
                    if (!result.isEmpty()) {
                        commitFinalResult(result);
                    } else {
                        clearPreviewTracking();
                    }
                    consecutiveEngineErrors = 0;
                    consecutiveStalls = 0;
                    endSession();
                    if (enabled) scheduleRestart(NEXT_UTTERANCE_DELAY_MS);
                }
            });

            armWatchdog(session, READY_TIMEOUT_MS);
            next.startListening(createDictationIntent(selectedLocale));
        } catch (RuntimeException e) {
            endSession();
            if (++consecutiveEngineErrors >= MAX_ENGINE_ERRORS) {
                stopWithMessage("تعذر تشغيل محرك الصوت");
            } else {
                scheduleRestart(SILENCE_RETRY_DELAY_MS * consecutiveEngineErrors);
            }
        }
    }

    /**
     * Ask the installed recognizer to tolerate pauses in continuous dictation.
     * Android recognizers decide their own endpointing and can ignore these
     * extras. Do not set minimum utterance length: that would delay short
     * phrases and their final transcripts. Keep the result/error restart loop
     * as a fallback when a provider closes the microphone early.
     */
    static Intent createDictationIntent(@Nullable Locale locale) {
        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLY_COMPLETE_SILENCE_MS);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                COMPLETE_SILENCE_MS);
        if (locale != null) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag());
        }
        return intent;
    }

    private boolean isCurrentSession(int session) {
        return enabled && session == generation;
    }

    private boolean isTargetActive() {
        return targetConnection != null
                && targetConnection == ime.getCurrentInputConnection()
                && !isSensitiveField(ime.getCurrentInputEditorInfo());
    }

    private static String bestResult(@Nullable Bundle result) {
        if (result == null) return "";
        final ArrayList<String> values =
                result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values == null || values.isEmpty() || values.get(0) == null
                ? "" : values.get(0).trim();
    }

    /**
     * Return false when no new text was written (unchanged hypothesis).
     * Each preview occupies exactly ONE verified interval immediately before
     * the caret. The service's next cumulative hypothesis replaces that interval.
     */
    private boolean showInterimResult(String partial) {
        if (!isTargetActive()) {
            stop();
            return false;
        }
        if (partial.equals(previewText)) return false;
        final InputConnection connection = targetConnection;
        if (connection == null) return false;

        if (!hasPreview) {
            // Keyboard autocorrect may be composing earlier text. Finish it
            // before we insert our own preview as ordinary committed text.
            connection.finishComposingText();
            if (!connection.commitText(partial, 1)) {
                stopWithMessage("تعذر إظهار الإملاء في حقل الكتابة");
                return false;
            }
        } else {
            if (!replaceVerifiedPreview(partial)) return false;
        }
        previewText = partial;
        hasPreview = true;
        return true;
    }

    /**
     * Verify first, then atomically delete precisely the text WE inserted.
     * This prevents repeated "word word + word word +" partial-result chains.
     */
    private boolean replaceVerifiedPreview(String replacement) {
        final InputConnection connection = targetConnection;
        if (connection == null || !isTargetActive()) {
            stop();
            return false;
        }
        final CharSequence before = connection.getTextBeforeCursor(previewText.length(), 0);
        if (before == null || !previewText.contentEquals(before)) {
            stopWithMessage("تغيّر موضع الكتابة، أُوقف الإملاء لحماية النص");
            return false;
        }
        connection.beginBatchEdit();
        boolean removed = false;
        boolean inserted = false;
        try {
            removed = connection.deleteSurroundingText(previewText.length(), 0);
            if (removed) inserted = connection.commitText(replacement, 1);
        } finally {
            connection.endBatchEdit();
        }
        if (!removed || !inserted) {
            stopWithMessage("لا يدعم حقل الكتابة تحديث الإملاء بأمان");
            return false;
        }
        return true;
    }

    private void commitFinalResult(String finalText) {
        if (!isTargetActive()) {
            stop();
            return;
        }
        final InputConnection connection = targetConnection;
        if (connection == null) return;
        if (hasPreview) {
            if (finalText.equals(previewText)) {
                // Already inserted once. Append only a separator, not another
                // copy of the last partial hypothesis.
                if (!hasVerifiedPreview()) {
                    stopWithMessage("تغيّر موضع الكتابة، أُوقف الإملاء لحماية النص");
                    return;
                }
                if (!connection.commitText(" ", 1)) {
                    stopWithMessage("تعذر إنهاء الكتابة الصوتية");
                    return;
                }
            } else if (!replaceVerifiedPreview(finalText + " ")) {
                return;
            }
        } else if (!connection.commitText(finalText + " ", 1)) {
            stopWithMessage("تعذر إدخال النص الصوتي");
            return;
        }
        clearPreviewTracking();
    }

    private boolean hasVerifiedPreview() {
        if (!isTargetActive() || targetConnection == null) return false;
        final CharSequence before =
                targetConnection.getTextBeforeCursor(previewText.length(), 0);
        return before != null && previewText.contentEquals(before);
    }

    private void clearPreviewTracking() {
        // Preview was already committed as plain text. DO NOT write it again.
        hasPreview = false;
        previewText = "";
    }

    private void armWatchdog(final int session, long timeoutMs) {
        cancelWatchdog();
        pendingWatchdog = () -> {
            pendingWatchdog = null;
            if (!isCurrentSession(session)) return;
            clearPreviewTracking();
            endSession();
            if (!enabled) return;
            if (++consecutiveStalls >= MAX_STALLED_SESSIONS) {
                stopWithMessage("الميكروفون لا يستجيب؛ أُوقف الإملاء، اضغط لإعادة المحاولة");
            } else {
                updateMicIcon(ICON_RESTARTING);
                scheduleRestart(SILENCE_RETRY_DELAY_MS * consecutiveStalls);
            }
        };
        mainHandler.postDelayed(pendingWatchdog, timeoutMs);
    }

    private void cancelWatchdog() {
        if (pendingWatchdog != null) {
            mainHandler.removeCallbacks(pendingWatchdog);
            pendingWatchdog = null;
        }
    }

    private void endSession() {
        cancelWatchdog();
        ++generation; // discard callbacks belonging to the finished session
        disposeRecognizer();
    }

    private void disposeRecognizer() {
        final SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) {
            try { old.cancel(); } catch (RuntimeException ignored) { }
            try { old.destroy(); } catch (RuntimeException ignored) { }
        }
    }

    private void scheduleRestart(long delayMs) {
        if (!enabled) return;
        if (pendingRestart != null) mainHandler.removeCallbacks(pendingRestart);
        updateMicIcon(ICON_RESTARTING);
        pendingRestart = () -> {
            pendingRestart = null;
            if (enabled) beginRecognition();
        };
        mainHandler.postDelayed(pendingRestart, delayMs);
    }

    private void stopWithMessage(String description) {
        stop();
        message(description);
    }

    /** A second tap, hidden input view, or service shutdown stops dictation. */
    void stop() {
        enabled = false;
        ++generation;
        if (pendingRestart != null) {
            mainHandler.removeCallbacks(pendingRestart);
            pendingRestart = null;
        }
        cancelWatchdog();
        // An unfinished interim transcript is already visible and must NOT be
        // re-committed, even if Stop arrives between partial and final callbacks.
        clearPreviewTracking();
        disposeRecognizer();
        targetConnection = null;
        selectedLocale = null;
        consecutiveEngineErrors = 0;
        consecutiveStalls = 0;
        updateMicIcon(ICON_OFF);
    }

    void destroy() {
        stop();
    }

    private static boolean isSensitiveField(@Nullable EditorInfo editor) {
        if (editor == null) return true;
        final int type = editor.inputType;
        final int klass = type & InputType.TYPE_MASK_CLASS;
        final int variation = type & InputType.TYPE_MASK_VARIATION;
        if (klass == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
                return true;
            }
        }
        return klass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    /** Green = recognition engine ready. Amber = connecting/reconnecting. */
    private void updateMicIcon(final int state) {
        final int revision = ++iconGeneration;
        if (ime.getWindow() == null || ime.getWindow().getWindow() == null) return;
        final View root = ime.getWindow().getWindow().getDecorView();
        if (root == null) return;
        root.post(() -> {
            if (revision != iconGeneration || (state != ICON_OFF && !enabled)) return;
            final ImageButton mic = findVoiceButton(root);
            if (mic == null) return;
            if (state == ICON_LISTENING) {
                mic.setColorFilter(Color.rgb(28, 181, 89), PorterDuff.Mode.SRC_IN);
                mic.setContentDescription("الميكروفون يستمع — اضغط لإيقافه");
            } else if (state == ICON_RESTARTING) {
                mic.setColorFilter(Color.rgb(240, 160, 38), PorterDuff.Mode.SRC_IN);
                mic.setContentDescription("إعادة تشغيل الاستماع تلقائيًا — اضغط للإيقاف");
            } else {
                mic.clearColorFilter();
                mic.setContentDescription("الإملاء متوقف — اضغط للتحدث");
            }
        });
    }

    @Nullable
    private static ImageButton findVoiceButton(View root) {
        if (root instanceof ImageButton && root.getTag() == ToolbarKey.VOICE) {
            return (ImageButton) root;
        }
        if (root instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                final ImageButton found = findVoiceButton(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void message(String text) {
        Toast.makeText(ime, text, Toast.LENGTH_SHORT).show();
    }
}
