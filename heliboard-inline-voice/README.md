# HeliBoard — inline voice input (experimental)

## Compact runtime repair (Android 12 regression)

Work is isolated on `fix/heliboard-compact-runtime-20261024`. The working
`dc1c35b` commit and `heliboard-inline-voice-v1` branch are not changed.

The exact APK from run `40098360205` was installed and launched on an API 31
emulator in run `40102641866`. Logcat reproduced:

```
android.content.res.Resources$NotFoundException: String resource ID #0x0
at helium314.keyboard.settings.WelcomeWizardKt.WelcomeWizard(WelcomeWizard.kt:86)
```

The compact overlay used a literal application label. Android therefore set
`ApplicationInfo.labelRes` to zero, but the welcome screen calls
`stringResource(applicationInfo.labelRes)`. The overlay now uses a dedicated
`@string/compact_ime_name` resource for both application and service labels.
The relevant captured stack is in `evidence/baseline-40098360205/stacktrace.txt`.

The repair APK uses `helium314.keyboard.compactfixdebug`, a different ID from
both the working voice APK (`helium314.keyboard.debug`) and the broken compact
APK (`helium314.keyboard.compactdebug`). Both debug FileProvider authorities
are changed as well, avoiding conflicts when the previous app remains installed.
Debug signing keys remain per-run; keep testing as a separate installation.

The recognizer provider and recognition intent are preserved. Editor transitions
cancel the old session, stale icon posts are ignored, null results are safe,
and provider cleanup failures do not escape IME teardown. Only final results
are committed, once. Password/PIN input remains blocked.

Arabic/English character layouts, the 16-key coding row, Arabic popup marks,
and compact height defaults are preserved. Shift and Esc are directly accessible
in the bottom control row with Ctrl, Alt, Tab, language, space, period, action,
and symbols. Tests decode the actual packaged layouts using `LayoutParser`.

`.github/workflows/heliboard-compact-runtime.yml` reproduces the old APK failure,
runs upstream unit tests plus overlay regressions, builds the new debug APK,
and exercises it on Android 12 before publishing **HeliBoard-Compact-Fix-debug**.
Inspect each run's unit reports and runtime JSON/Logcat/screenshots for its actual
outcome. An emulator pass does not certify every Android device or speech provider.
Speech unit tests mock Android's platform recognizer; the emulator does not prove
real audio transcription, speech accuracy, or live audio on the user's phone.

The emulator fixture is a separate tiny APK compiled only during CI; it is never
included in the delivered HeliBoard APK. It provides text/password fields for
real IME typing, switching, popup, controls, hide/reopen and rotation checks.

---
The original voice-overlay notes below describe the earlier proof of concept.
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
