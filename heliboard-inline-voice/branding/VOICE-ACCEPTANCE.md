# KEYNOVA SMART real microphone acceptance

Published APKs have passed automated signing, JVM, theme, bilingual typing,
shortcut, persistence, rotation and RTL gates when their workflow succeeds.
Real microphone-to-text acceptance is a separate device test; this document is
an uncompleted checklist, not an automatic PASS or a final release declaration.
No production transcript or microphone recording is uploaded by CI.

Use an Android device with a working installed recognition service and network
when required by that service. Record Android version, APK SHA-256, certificate
SHA-256, recognition service/version, language, result and observed failure.
Use invented phrases without private information. Test both applicable APKs.

| Check | Expected result | Result |
| --- | --- | --- |
| Enable keyboard, open a normal editor, grant microphone permission | Keyboard stays available; microphone starts on the next explicit tap | NOT RUN |
| Dictate an English sentence slowly, revise a word mid-utterance | Live partials update the voice strip only; the finalized sentence is appended once | NOT RUN |
| Dictate Arabic: مرحبا هذا اختبار لوحة المفاتيح | Correct Arabic insertion; no cumulative duplicated partials | NOT RUN |
| Pause for 1, 3, 5, 10 and 30 seconds, then speak without touching the mic | User mode stays enabled; provider reconnects automatically; each phrase remains once | NOT RUN |
| Dictate three consecutive phrases, including مرحبا كيف حالك then اليوم سوف نجرب الميكروفون | First phrase never changes; exactly one space between finalized phrases and after the last | NOT RUN |
| Provider emits partials but no final, then endpoint/timeout/no-match | Last nonempty partial is committed once with a separator; late callbacks cannot rewrite it | NOT RUN |
| Provider emits a revised final before its terminal deadline | Revised final appears once, with no stale partial duplicated | NOT RUN |
| Tap microphone to stop while a partial is visible | Microphone stops; one nonempty buffered phrase and one separator are committed once | NOT RUN |
| Move cursor or edit text during dictation | Existing text is protected; stale preview is not blindly deleted | NOT RUN |
| Change editor or hide the keyboard during dictation | Old recognition callbacks cannot write into the new field; hiding stops capture | NOT RUN |
| Interrupt recognition using the service/network, then retry | Backoff recovery with listening/speech/reconnecting/stopped/error states; no false waveform | NOT RUN |
| Open a password field | Dictation remains blocked | NOT RUN |
| Change theme, rotate, switch Arabic/English, and type afterwards | Keyboard and shortcuts remain functional | NOT RUN |

A tester must replace NOT RUN with dated evidence before describing real voice
acceptance as passed. Report service limitations honestly; do not disable text
protection or duplicate prevention to satisfy this checklist.

Implementation boundary: Android SpeechRecognizer services control their own
microphone/endpointer and are documented as unsuitable for uninterrupted
continuous recording. KEYNOVA now maintains the user's dictation mode across
single service sessions, retires each callback generation, and reconnects one
recognizer at a time. Reconnection gaps can lose speech; a provider that refuses
requests cannot be forced to capture. Silence extras are optional hints only.
No competing AudioRecord, audio files, new cloud endpoint, model download or
permission is introduced. The installed provider can still use its own network
service, as it did before.

Partials are live in the keyboard strip, not mutable editor text. A final result,
normal error, manual stop or watchdog seals one immutable phrase. After an
endpoint, partials are ignored; a final has a 2-second terminal grace. Without an
endpoint/final, 5 seconds without a changed partial seals the buffered phrase.
Late results from retired sessions are dropped rather than risking replacement
or duplication. A silent session with no hypotheses has a 45-second watchdog.
These are recovery bounds, not promises that a provider obeys silence extras.

The automated VoiceSmokeInstrumentation uses a scripted provider with a REAL
Android Editable/InputConnection in each installed minified APK. It verifies
append-only utterances, stale composing spans, partial-only endpoint finalization,
Arabic/English, cursor protection and strip reattachment. It does not feed a real
microphone or prove recognition-provider compatibility. Run this checklist on the
reported phone/provider before calling the physical-device bugs fixed.
