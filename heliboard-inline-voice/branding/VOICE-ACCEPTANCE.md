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
| Dictate an English sentence slowly, revise a word mid-utterance | Live partials update one preview; final sentence appears once | NOT RUN |
| Dictate Arabic: مرحبا هذا اختبار لوحة المفاتيح | Correct Arabic insertion; no cumulative duplicated partials | NOT RUN |
| Pause, then dictate a second phrase without touching the mic | Listening restarts; both phrases remain once | NOT RUN |
| Tap microphone to stop while a partial is visible | Microphone stops and preview is not appended again | NOT RUN |
| Move cursor or edit text during dictation | Existing text is protected; stale preview is not blindly deleted | NOT RUN |
| Change editor or hide the keyboard during dictation | Old recognition callbacks cannot write into the new field | NOT RUN |
| Interrupt recognition using the service/network, then retry | Bounded recovery and truthful listening/restarting/off state | NOT RUN |
| Open a password field | Dictation remains blocked | NOT RUN |
| Change theme, rotate, switch Arabic/English, and type afterwards | Keyboard and shortcuts remain functional | NOT RUN |

A tester must replace NOT RUN with dated evidence before describing real voice
acceptance as passed. Report service limitations honestly; do not disable text
protection or duplicate prevention to satisfy this checklist.
