/*
 * HeliBoard inline speech input proof-of-concept.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

import androidx.annotation.Nullable;

/**
 * Uses Android's installed speech recognition service INSIDE the existing IME.
 * No voice IME is selected. Audio processing policy belongs to the installed engine.
 * Only final recognition results are inserted to avoid duplicate partial transcripts.
 */
final class VoiceInputController {
    private final LatinIME ime;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private int generation;

    VoiceInputController(LatinIME ime) {
        this.ime = ime;
    }

    void toggle(@Nullable Locale locale) {
        if (listening) {
            stop();
        } else {
            start(locale);
        }
    }

    private void start(@Nullable Locale locale) {
        final EditorInfo editor = ime.getCurrentInputEditorInfo();
        final InputConnection initialConnection = ime.getCurrentInputConnection();
        if (editor == null || initialConnection == null) {
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

        stop();
        final int session = ++generation;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ime);
            listening = true;
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    if (session == generation) message("🎤 أتكلم الآن — اضغط الميكروفون للإلغاء");
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onPartialResults(Bundle partialResults) {
                    // Do not commit partial text: some engines repeat it in onResults.
                }
                @Override public void onEvent(int eventType, Bundle params) { }
                @Override public void onError(int error) {
                    if (session != generation) return;
                    stop();
                    if (error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        message("لم يتم التعرف على كلام واضح");
                    } else {
                        message("تعذر الإملاء الصوتي (رمز " + error + ")");
                    }
                }
                @Override public void onResults(Bundle results) {
                    if (session != generation) return;
                    ArrayList<String> options =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    final String text = (options == null || options.isEmpty())
                            ? "" : options.get(0).trim();
                    // An input field might have changed during recognition.
                    if (!text.isEmpty() && ime.getCurrentInputConnection() == initialConnection
                            && !isSensitiveField(ime.getCurrentInputEditorInfo())) {
                        initialConnection.commitText(text, 1);
                    }
                    stop();
                }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            if (locale != null) {
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag());
            }
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            stop();
            message("تعذر تشغيل محرك التعرف الصوتي");
        }
    }

    void stop() {
        generation++;
        listening = false;
        if (recognizer != null) {
            final SpeechRecognizer old = recognizer;
            recognizer = null;
            try { old.cancel(); } catch (RuntimeException ignored) { }
            old.destroy();
        }
    }

    void destroy() {
        stop();
    }

    private static boolean isSensitiveField(@Nullable EditorInfo editor) {
        if (editor == null) return true;
        int type = editor.inputType;
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

    private void message(String message) {
        Toast.makeText(ime, message, Toast.LENGTH_SHORT).show();
    }
}
