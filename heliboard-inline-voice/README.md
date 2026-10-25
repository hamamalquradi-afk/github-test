# KEYNOVA SMART — كينوفا سمارت (HeliBoard Compact overlay)

This directory contains an overlay for the pinned upstream [HeliBoard](https://github.com/HeliBorg/HeliBoard) source, commit `e97acdbe7ad16ef06d89d71d35ed1096e6d69f4c` (4.3-beta1). The GitHub Action fetches that exact upstream revision, applies the overlay and builds the APK. The original source repository and this repository's `main` branch are not changed.


## Phase 01 identity

KEYNOVA SMART / كينوفا سمارت uses the owner-approved tilted Nova Pulse keycap
identity, with `TYPE • SPEAK • CREATE` / `اكتب • تحدث • أبدع`. The launcher, IME,
welcome and About screen use the new resources. Both existing build variants keep
their package IDs, provider authorities and signing configuration. The adaptive,
monochrome and legacy icon resources are in [branding](branding/README.md).
The internal backup archive identity remains unchanged for compatibility.
Artifact/APK filenames retain the upstream naming used by the build infrastructure.

## What is included

- An offline [80-theme gallery](themes/README.md) in Appearance → Themes, with native keyboard previews, Arabic localization, saved custom appearances and bounded personal-image handling.

- Inline Android `SpeechRecognizer` invoked from the keyboard microphone key without switching input methods. First use launches the Android microphone permission screen. Recognition depends on a speech service on the device and **may send speech to a provider/cloud**; offline recognition is not guaranteed.
- Bundled Arabic and English QWERTY layouts, a 16-symbol programming row, accessible Shift/Ctrl/Alt/Tab keys, a compact bottom row and extra Arabic marks. The comma adapts to email and URL input fields.
- One-tap, user-controlled dictation mode. After a pause / Android recognizer result, the app automatically requests the next utterance, without requiring another tap. A second tap stops the loop; hiding the keyboard or changing the input target also stops it. Android recognition services still impose per-utterance limits and may briefly release the microphone between sessions; genuinely uninterrupted audio capture is not guaranteed.
- Interim speech is shown as one plain-text preview when the recognition engine supplies partial hypotheses. Each revised hypothesis first confirms that the previous preview is still immediately before the cursor and replaces only that region. If confirmation fails, dictation stops instead of duplicating text or deleting unrelated content. Engines without partial results still display their text after final recognition.
- The microphone is green only when the recognizer reports that it is ready, amber while starting/reconnecting/waiting for final results, and off after stopping. Speech-service watchdogs recover stalled sessions and stop with an explanatory message after repeated failures. Android recognition engines can still impose timeouts and temporary recording gaps.
- Recognition disabled for passwords/PINs, and existing input connection checked before inserting transcribed text.

## Install safely

1. Download `HeliBoard-inline-voice-debug` from the most recent successful [Actions run](https://github.com/hamamalquradi-afk/github-test/actions/workflows/heliboard-inline-voice.yml) and extract the APK from the ZIP artifact.
2. The compact debug test app has **application ID `helium314.keyboard.compactdebug`**, with unique clipboard/gesture provider authorities. It is intended to install alongside regular HeliBoard and the earlier `helium314.keyboard.debug` testing app. Do **not** uninstall your existing working keyboard.
3. Export/backup preferences and custom layouts before importing them into the test app. Imported custom layouts are not overwritten by the build overlay, but they are not automatically shared across different app IDs.
4. Enable KEYNOVA SMART / كينوفا سمارت in Android settings, select it as the current keyboard, then test Arabic/English, punctuation, Ctrl/Alt/Shift, and microphone insertion in an ordinary text field.

**Debug signing caveat:** The gallery workflow retains its debug signing key for subsequent builds. Earlier builds used temporary runner keys, and their private keys are not present in this repository. An older Compact installation may reject an update because its certificate differs. Keep the installed keyboard and its settings; a compatible update requires its original keystore. Do not uninstall the existing keyboard as a workaround. A production release channel still requires a securely stored persistent release key.

## Automated checks

The workflow validates overlay installation authorities and layout invariants, builds and verifies the signature of the debug APK, and runs the full debug JVM suite plus ten controller regressions. These cover cumulative Arabic, revised English finals, repeated partials, stopping, cursor edits, unavailable surrounding text, editor changes, final-only engines, failed deletion, and stale sessions.

If the JVM suite fails, the same full suite runs on a separate, unmodified checkout of the exact pinned upstream commit. The comparison rejects new or changed failures, missing tests, newly skipped tests, and incomplete reports. Existing failures are retained in both XML reports and printed as warnings; a passing regression gate does **not** mean all upstream tests passed. No known-failure exclusion list is used. The German layout assertion counts real keys rather than the two alignment spacers introduced by narrower Compact Shift/Delete keys.

An Android emulator launches the settings/welcome screen and enables/selects the IME. Where the earlier working debug APK artifact is accessible, it also tests side-by-side installation. Consult each workflow run for actual pass/fail counts; build success alone is **not** proof of microphone accuracy or runtime stability on every phone. These validation changes do not modify the shipped voice controller or layouts.

## Manual verification still required

- On a real Android phone, say a growing partial hypothesis such as "لكنها" then "لكنها في" and confirm only one evolving preview appears; confirm the final transcript is inserted exactly once. Test mic restarts, service downtime (green must not stay stuck), changing cursor, editors lacking surrounding-text support, repeated microphone taps, and both Arabic and English.
- Test app startup, default IME selection, Arabic/English layouts, symbols, Ctrl+A/C/V/Z, long-press diacritics, rotation and sensitive fields.
- Verify data migration/backup and device-specific voice behavior. No cloud transcription guarantee is made.

Upstream license: GPL-3.0-only and applicable Apache-2.0 component licenses.
