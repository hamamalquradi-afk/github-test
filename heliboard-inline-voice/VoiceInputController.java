/*
 * HeliBoard inline speech input (manual toggle, auto-restarting recognition).
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
 * SpeechRecognizer is single-utterance on many Android recognition services.
 * Keep a user-controlled dictation MODE active across utterances by starting
 * another recognition session after final results or benign silence errors.
 * Display interim hypotheses as composing text and replace (not append) them
 * with the final transcript, so onPartialResults cannot duplicate words.
 *
 * A platform recognition engine can still impose pauses, rate limits, and
 * silence timeouts. This is not a guarantee of uninterrupted audio capture.
 */
final class VoiceInputController {
    private static final long NEXT_UTTERANCE_DELAY_MS = 450L;
    private static final long SILENCE_RETRY_DELAY_MS = 800L;
    private static final int MAX_CONSECUTIVE_ENGINE_ERRORS = 3;

    private final LatinIME ime;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private SpeechRecognizer recognizer;
    @Nullable private InputConnection targetConnection;
    @Nullable private Locale selectedLocale;
    @Nullable private Runnable pendingRestart;

    private boolean enabled;
    private boolean hasPreview;
    private String previewText = "";
    private int generation;
    private int iconGeneration;
    private int consecutiveEngineErrors;

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

        enabled = true;
        selectedLocale = locale;
        targetConnection = connection;
        consecutiveEngineErrors = 0;
        updateMicIcon(true);
        message("🎤 الإملاء الصوتي مفعّل — اضغط الميكروفون لإيقافه");
        beginRecognition();
    }

    /** Called on the main thread, once for each service-managed utterance. */
    private void beginRecognition() {
        if (!enabled) return;
        if (!isTargetActive()) {
            stop();
            return;
        }

        // Android requires each startListening to follow completion of the
        // previous session. Dispose the old instance before making a new one.
        disposeRecognizer();
        final int session = ++generation;
        try {
            final SpeechRecognizer next = SpeechRecognizer.createSpeechRecognizer(ime);
            recognizer = next;
            next.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    if (isCurrentSession(session)) updateMicIcon(true);
                }
                @Override public void onBeginningOfSpeech() {
                    if (isCurrentSession(session)) consecutiveEngineErrors = 0;
                }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    // Silence ends an utterance, NOT the user-controlled mode.
                    // Wait for onResults/onError before starting a new session.
                }
                @Override public void onPartialResults(Bundle results) {
                    if (isCurrentSession(session)) showInterimResult(results);
                }
                @Override public void onEvent(int eventType, Bundle params) { }
                @Override public void onError(int error) {
                    if (!isCurrentSession(session)) return;
                    finishPreview("");
                    endSession();
                    if (!enabled) return;
                    if (error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        consecutiveEngineErrors = 0;
                        scheduleRestart(SILENCE_RETRY_DELAY_MS);
                    } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                            || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                            || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                        stop();
                        message("توقّف الإملاء: خدمة الصوت رفضت الطلب (رمز " + error + ")");
                    } else if (++consecutiveEngineErrors >= MAX_CONSECUTIVE_ENGINE_ERRORS) {
                        stop();
                        message("توقّف الإملاء بعد تكرار خطأ في خدمة الصوت (رمز " + error + ")");
                    } else {
                        scheduleRestart(SILENCE_RETRY_DELAY_MS * consecutiveEngineErrors);
                    }
                }
                @Override public void onResults(Bundle results) {
                    if (!isCurrentSession(session)) return;
                    finishPreview(bestResult(results));
                    consecutiveEngineErrors = 0;
                    endSession();
                    scheduleRestart(NEXT_UTTERANCE_DELAY_MS);
                }
            });
            final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            if (selectedLocale != null) {
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                        selectedLocale.toLanguageTag());
            }
            next.startListening(intent);
        } catch (RuntimeException exception) {
            endSession();
            if (++consecutiveEngineErrors >= MAX_CONSECUTIVE_ENGINE_ERRORS) {
                stop();
                message("تعذر تشغيل محرك التعرف الصوتي");
            } else {
                scheduleRestart(SILENCE_RETRY_DELAY_MS * consecutiveEngineErrors);
            }
        }
    }

    private boolean isCurrentSession(int session) {
        return enabled && session == generation;
    }

    private boolean isTargetActive() {
        return targetConnection != null
                && ime.getCurrentInputConnection() == targetConnection
                && !isSensitiveField(ime.getCurrentInputEditorInfo());
    }

    private static String bestResult(@Nullable Bundle results) {
        if (results == null) return "";
        final ArrayList<String> choices =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return choices == null || choices.isEmpty() || choices.get(0) == null
                ? "" : choices.get(0).trim();
    }

    /** Update ONE composing region; don't append each repeated partial result. */
    private void showInterimResult(@Nullable Bundle results) {
        if (!isTargetActive()) {
            stop();
            return;
        }
        final String partial = bestResult(results);
        if (partial.isEmpty() || partial.equals(previewText)) return;
        if (!hasPreview) {
            // Preserve any already-composing text from normal keyboard typing.
            targetConnection.finishComposingText();
        }
        if (targetConnection.setComposingText(partial, 1)) {
            previewText = partial;
            hasPreview = true;
        }
    }

    /**
     * Android commitText replaces the active composing region, if present.
     * This replaces the preview with the final recognition, once per utterance.
     * On errors/manual stop, retain the last interim transcript if available.
     */
    private void finishPreview(@Nullable String finalText) {
        final String text = finalText != null && !finalText.trim().isEmpty()
                ? finalText.trim() : previewText;
        if (isTargetActive() && !text.isEmpty()) {
            targetConnection.commitText(text + " ", 1);
        }
        hasPreview = false;
        previewText = "";
    }

    private void endSession() {
        ++generation; // ignore any late callbacks from the ending session
        disposeRecognizer();
    }

    private void disposeRecognizer() {
        final SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old == null) return;
        try { old.cancel(); } catch (RuntimeException ignored) { }
        try { old.destroy(); } catch (RuntimeException ignored) { }
    }

    private void scheduleRestart(long delayMs) {
        if (!enabled) return;
        if (pendingRestart != null) mainHandler.removeCallbacks(pendingRestart);
        pendingRestart = () -> {
            pendingRestart = null;
            if (enabled) beginRecognition();
        };
        mainHandler.postDelayed(pendingRestart, delayMs);
    }

    /** Explicit user off, input view hidden, or service destroyed. */
    void stop() {
        enabled = false;
        ++generation;
        if (pendingRestart != null) {
            mainHandler.removeCallbacks(pendingRestart);
            pendingRestart = null;
        }
        // Do not lose the last speech hypothesis when the user presses Stop.
        finishPreview("");
        disposeRecognizer();
        targetConnection = null;
        selectedLocale = null;
        consecutiveEngineErrors = 0;
        updateMicIcon(false);
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

    /**
     * Green means dictation MODE is enabled; the Android engine may temporarily
     * release the physical microphone while a recognition session restarts.
     */
    private void updateMicIcon(final boolean active) {
        final int update = ++iconGeneration;
        if (ime.getWindow() == null || ime.getWindow().getWindow() == null) return;
        final View root = ime.getWindow().getWindow().getDecorView();
        if (root == null) return;
        root.post(() -> {
            if (update != iconGeneration || (active && !enabled)) return;
            final ImageButton mic = findVoiceButton(root);
            if (mic == null) return;
            if (active) {
                mic.setColorFilter(Color.rgb(28, 181, 89), PorterDuff.Mode.SRC_IN);
                mic.setContentDescription("الإملاء الصوتي مفعّل — اضغط للإيقاف");
            } else {
                mic.clearColorFilter();
                mic.setContentDescription("الميكروفون متوقف — اضغط للتحدث");
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
