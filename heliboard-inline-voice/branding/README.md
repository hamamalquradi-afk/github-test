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
