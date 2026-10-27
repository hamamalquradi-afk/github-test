# Dictation session and editor repair

Checkpoint inspected: `c3572fa81f722e676344a6b6229268f11cd3793f`, successful
workflow #78 (`40429981248`). Earlier fixes `99c7cfa`, `441bcc3`, `5114086`
extended provider silence hints and sealed previews on errors. Those fixes did
not model a real editor's selection/composition behavior or exercise recognition
callbacks through elapsed silence in the old tests.

## Verified code defects and limits

* The old controller committed partials to the editor and updated them with
  `deleteSurroundingText(previewText.length(), 0)`. Equal text before the caret
  did not identify an absolute owned range. A moved caret at an equal earlier
  suffix could authorize deletion of an old phrase. The old mocked commitText
  always appended and never modeled a composing span or selection replacement.
* `commitText` can replace an editor-owned composing span even when the same
  transcript happens to precede the cursor. Upstream recorrection can install
  such a span after a direct InputConnection write; the old controller only
  called finishComposingText for the first partial. Its RichInputConnection and
  InputLogic state were not synchronized. The new regressions model that span
  and Android instrumentation exercises it on a real EditText.
* `onEndOfSpeech` did not freeze partial updates, and a subsequent partial could
  extend the active watchdog over an endpoint. Three stalls or engine errors
  disabled user intent; finishing/recreating the input view stopped it outright.
  These are reproducible state/lifecycle paths, independently of silence extras.
* The phone's recognition service/version and audio logs are unavailable here.
  Its exact endpoint/disconnect sequence is not asserted as measured. The Android
  SpeechRecognizer contract explicitly leaves endpointer timing to the provider
  and says the API is not intended for continuous recognition. No CI success
  proves uninterrupted physical recording.

## Correction

`VoiceDictationSession` separates user intent, provider readiness, speech,
endpoint/final grace, backoff and stopped/error states. It owns one recognizer
generation, one restart and one watchdog. Silence/no-match never turn user mode
off. Recoverable disconnects use capped backoff, and provider rate limits use a
60-second cooldown. Fatal permission/language errors and unsafe editor changes
stop capture with an explicit error. Retired callbacks cannot affect text/UI.

`VoiceTextBuffer` has no deletion, selection or composing-text replacement API.
Partials remain visible in the microphone strip. One final/buffered phrase is
appended only after verifying a collapsed absolute caret and surrounding text,
finishing composing text, then checking the anchor again. It adds/reuses a single
separator, supports Arabic/English punctuation, and rejects insertion inside a
word. Unsupported/changed editors fail closed; a failed commit is never retried.
Previously committed utterances are never used as replacement ranges.

The adapter suppresses recorrection only while voice owns the editor, aligns
upstream caches, and stops dictation before manual typing. A bounded rotation
pause releases audio and resumes only in the same verified editor. View replacement
reattaches the live strip without restarting user intent. Hiding the keyboard,
switching editors or destroying the service releases audio. RMS alone drives the
waveform and expires after 300 ms; reconnecting never shows an active waveform.

No signing, IDs, layouts, theme assets, preferences, microphone permissions,
recognition provider or external audio service changed. No audio is stored.

## Validation and remaining acceptance

The focused tests exercise 1/3/5/10/30-second silence, endpoint/no-match/timeout,
partial-only and revised finals, repeated hypotheses, three phrases, Arabic,
English/mixed text, stale callbacks/composing spans, cursor/editor changes,
manual stop, provider backoff/rate limits and lifecycle/recreation.

Local host tests run the actual provider-independent production Java. Android
adapter compilation uses the Android SDK; Robolectric and both installed APK
instrumentations run in the full build. Existing Python/signing/fixture tests,
upstream JVM failure comparison and all theme/typing/shortcut gates remain.
APK publication follows all Android gates, including the new real-editor voice
instrumentation. Physical microphone/provider acceptance remains separate in
`branding/VOICE-ACCEPTANCE.md` and must not be described as passed by CI.
