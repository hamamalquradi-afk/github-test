# HeliBoard — inline voice input (experimental)
**بالعربية:** هذا فرع تجريبي على حسابك. لا يغيّر مستودعك الرئيسي ولا تخطيط لوحة المفاتيح المخصص. لعدم توفر صلاحية إنشاء Fork من الاتصال الحالي، توجد هنا ملفات تعديل تُطبَّق تلقائيًا على المصدر الرسمي المثبّت بالإصدار المحدد عند البناء.

## Goal
Press the voice key and use Android SpeechRecognizer **without switching away from HeliBoard**. The keyboard and custom programming-symbol row remain visible during recognition.

## Upstream
- Source: https://github.com/HeliBorg/HeliBoard
- Locked commit: `e97acdbe7ad16ef06d89d71d35ed1096e6d69f4c` (2026-10-18)
- Source licensed under GPL-3.0-only / Apache-2.0 components.

## Implementation
- `VoiceInputController.java`: recognizer lifecycle, locale, final text insertion, password protection, results deduplication.
- `VoicePermissionActivity.java`: Android microphone runtime permission.
- `apply_overlay.py`: fail-closed modifications to `LatinIME.java` and `AndroidManifest.xml`.
- `.github/workflows/heliboard-inline-voice.yml`: clones the official source, applies edits, and attempts to build a debug APK.

## Using it
Open this branch's **Actions** tab and look for *HeliBoard inline voice APK*. Each push to this branch attempts a build. If successful, open the run and download the **HeliBoard-inline-voice-debug** artifact. The Android debug package is separate from the stable app (app ID `helium314.keyboard.debug`).

1. Back up your HeliBoard preferences and your imported custom keyboard layout.
2. Install the debug APK, switch Android's default input method to the new app for testing, and import your backed-up layout if needed.
3. On the first mic press, allow microphone access, return to the text field, and press the mic again.
4. With a recognition service installed and configured, you can speak while the keyboard remains visible. Press mic again to cancel.

## Limits / security
- Speech processing may use the recognizer provider's **network/cloud**: this patch does **not** guarantee offline/private transcription.
- The current status is shown with a temporary Toast instead of a toolbar listening indicator.
- Results are inserted only when the same input connection is still active.
- Password and numeric PIN fields are blocked.
- No automated build or device test has been verified until a successful GitHub Actions run confirms it.
- A customized layout shown only in screenshots cannot be reconstructed exactly from screenshots. This patch does not edit those layouts. Import the existing layout into the debug app.
