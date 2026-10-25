# KEYNOVA SMART — Phase 01

The owner approved the attached tilted Nova Pulse keycap design. `keynova_master.png`
is a high-resolution production rendition prepared from that attachment using image
generation, rather than the earlier front-facing concept. It is not a byte-identical
copy of the low-resolution attachment. Navy, cyan, blue and violet preserve its identity.

Android 21–25 uses the full-color bitmap through density-independent launcher XML.
Android 26+ uses a navy adaptive background and inset full-color foreground; themed
icons use the dedicated white vector K silhouette. Both existing variants retain
application IDs, provider authorities, signing configuration, preferences and backup
archive naming. Attribution and links to upstream HeliBoard remain unchanged.

The bilingual launcher/IME name also supplies the welcome title. Welcome subtitle,
settings title and About title/subtitle use the new localized identity. No keyboard
palette, theme catalog, layout or voice controller behavior is changed.

Validation: apply `apply_overlay.py` to the pinned clean upstream checkout, then run
`validate_overlay.py`. The existing GitHub workflow builds both APKs, runs JVM tests,
compares any failures to the pinned baseline, verifies signatures/checksums, and runs
the bilingual 80-theme emulator regressions. Consult actual run results before claiming
APK, signing or device validation. A new identity does not recover historical signing keys.

The emulator host fixture resets only the Settings activity task after selecting the
IME; it does not clear application data or theme preferences. The debug-only touch
harness uses UiAutomation for input across Android activity/IME windows, checks
injection success, and retains the actual English/Arabic editor-text assertions.
The verification-only workflow downloads the exact source-run artifacts, rejects
application-source drift, rechecks SHA-256/signatures, and exposes real JVM counts.
It skips repeating emulator tests only when the original source run succeeded.

Certificate comparison with the previous Compact checkpoint found different debug
signing identities. Retain the installed keyboard and data. A compatible in-place
update requires its original private keystore; the public APK cannot reconstruct
that key. The package IDs and signing configuration were not replaced to hide this
limitation. The existing separate Themes package remains available for testing.
