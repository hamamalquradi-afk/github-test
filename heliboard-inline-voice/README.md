# HeliBoard Compact — inline microphone (experimental Android debug APK)

This directory contains an overlay for the pinned upstream [HeliBoard](https://github.com/HeliBorg/HeliBoard) source, commit `e97acdbe7ad16ef06d89d71d35ed1096e6d69f4c` (4.3-beta1). The GitHub Action fetches that exact upstream revision, applies the overlay and builds the APK. The original source repository and this repository's `main` branch are not changed.

## What is included

- Inline Android `SpeechRecognizer` invoked from the keyboard microphone key without switching input methods. First use launches the Android microphone permission screen. Recognition depends on a speech service on the device and **may send speech to a provider/cloud**; offline recognition is not guaranteed.
- Bundled Arabic and English QWERTY layouts, a 16-symbol programming row, accessible Shift/Ctrl/Alt/Tab keys, a compact bottom row and extra Arabic marks. The comma adapts to email and URL input fields.
- Green listening indicator on the microphone toolbar icon when available, plus a status Toast.
- Recognition disabled for passwords/PINs, and existing input connection checked before inserting a final transcript.

## Install safely

1. Download `HeliBoard-inline-voice-debug` from the most recent successful [Actions run](https://github.com/hamamalquradi-afk/github-test/actions/workflows/heliboard-inline-voice.yml) and extract the APK from the ZIP artifact.
2. The compact debug test app has **application ID `helium314.keyboard.compactdebug`**, with unique clipboard/gesture provider authorities. It is intended to install alongside regular HeliBoard and the earlier `helium314.keyboard.debug` testing app. Do **not** uninstall your existing working keyboard.
3. Export/backup preferences and custom layouts before importing them into the test app. Imported custom layouts are not overwritten by the build overlay, but they are not automatically shared across different app IDs.
4. Enable HeliBoard Compact in Android settings, select it as the current keyboard, then test Arabic/English, punctuation, Ctrl/Alt/Shift, and microphone insertion in an ordinary text field.

**Debug signing caveat:** GitHub-hosted runners use temporary Android debug signing keys unless a persistent, private keystore is configured. A later APK with the same `compactdebug` application ID may therefore fail to update the earlier compact test installation due to a different signing certificate. Back up the *compact test app's* settings before uninstalling it, and never uninstall your stable keyboard as a workaround. A release/update-safe production APK requires a securely stored persistent signing key.

## Automated checks

The workflow validates overlay installation authorities and layout invariants, builds the debug APK, attempts the upstream debug JVM tests, and uses an Android emulator to launch the settings/welcome screen and enable/select the IME. Where the earlier working debug APK artifact is accessible, the emulator also tests side-by-side installation. Consult each workflow run for actual pass/fail results; build success alone is **not** proof of microphone accuracy or runtime stability on every phone.

## Manual verification still required

- Start and stop microphone recognition on a real Android phone with an installed recognition service, grant/deny permission, and check Arabic and English final text without switching keyboards.
- Test app startup, default IME selection, Arabic/English layouts, symbols, Ctrl+A/C/V/Z, long-press diacritics, rotation and sensitive fields.
- Verify data migration/backup and device-specific voice behavior. No cloud transcription guarantee is made.

Upstream license: GPL-3.0-only and applicable Apache-2.0 component licenses.
