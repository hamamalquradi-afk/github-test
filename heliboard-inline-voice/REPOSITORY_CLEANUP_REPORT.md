# KEYNOVA SMART repository cleanup audit

Audit date: 2026-10-27 (Asia/Aden). Branch: `heliboard-inline-voice-v1`.
Repository: https://github.com/hamamalquradi-afk/github-test

## 1. Baseline and scope

- Baseline HEAD: `4a817a63ee1c34a5cce85fbb3a2671e305a74787`.
- Baseline complete build: [run #81 / 40530078225](https://github.com/hamamalquradi-afk/github-test/actions/runs/40530078225), completed **success** before changes. Its actual reports were downloaded and inspected.
- Baseline JVM: 277 overlay tests, 269 passed, 8 failed, 0 skipped; all 8 failures match the unmodified pinned upstream's 193-test suite. All 84 project-focused tests, including 68 voice tests, passed. These are existing upstream failures, not a claim that all tests are green.
- Baseline APKs: Debug SHA-256 `4422fbbbea1803eabb665392220b01bd81a5a722e7d87151ae639825910a4c40`; Themes SHA-256 `c38d4047f6172ce6c6fb065fe88dc9e957fca4396aa8c65fcf1b9e37b5dbe9f9`.
- Original permanent certificate SHA-256: `2274da5280a2acc2456835e8d56de5d37887f6e85d5b3df2acb088428d1d6270`; both baseline APKs use this identity. No production private key or Secret value was inspected locally, regenerated, replaced, deleted or written to Git; Actions continues using the existing Secrets. Disposable test keys are created and removed only by the existing keystore regression tests.
- Pinned HeliBoard 4.3-beta1 source: `e97acdbe7ad16ef06d89d71d35ed1096e6d69f4c`. This repository is an overlay, not a checked-in standalone Android/Gradle project. Both workflows fetch the pinned tree and apply the existing overlay.
- Inspected all **113 tracked files**, including hidden root files and all four workflows, in **35 directories excluding the root** (36 including it). There were no untracked branch files at the baseline. The 113 files comprise 110 text files and 3 PNGs; all were read for structural/reference analysis. Binary identity, PNG dimensions/chunk CRC/integrity were checked without altering images. The KEYNOVA overlay has 62 files; the other 51 are shared configuration/workflows or unrelated web/static projects.
- Git's worktree administrative `.git` pointer and shared object database/reflogs are metadata, not branch source. They were not pruned or rewritten. The older workspace and its pre-existing local modifications were left intact; all work uses an isolated checkout of the latest remote checkpoint.
- HAMNOVA/web/static files were inventoried and their dependency entrypoints traced. They remain byte-identical. This audit does not claim a new functional QA run of the unrelated web application or a live legacy PostgreSQL database.

## 2. Classifications and exact deletions

| Classification | Baseline files | Action |
| --- | ---: | --- |
| REQUIRED — KEEP | 111 | Retain active source, build/test/signing inputs, independent project files and development documentation. |
| REQUIRES FURTHER INVESTIGATION | 2 | Retain the two standalone quiz URLs until external usage and redirect requirements are established. |
| SAFE TO DELETE | 0 | No tracked file met the deletion evidence threshold. |

**Exact tracked files deleted: none.** No source/resource/test consolidation or
move was made. There were no committed APKs, build trees, Python caches, local SDK
properties, signing containers or obsolete build artifacts to remove. A smaller
tree is not sufficient justification to remove a required or uncertain file.
No claim of disk space savings is made.

## 3. Dependency and retention decisions

- The two duplicate launcher pairs have identical bytes but separate `ic_launcher` and `ic_launcher_round` resource identities. Android manifests, upstream resources and BrandingTest use both; v21 versus v26 selects different platform implementations. All four are retained.
- `index.html` and `parts-of-speech-quiz-30.html` are byte-identical but expose different public paths. The Pages workflow uploads the entire root, making direct URLs implicit dependencies. Keep the root entrypoint and retain the alias pending external-use evidence. The university quiz is a distinct independent public entrypoint, also retained.
- `themes/generate_catalog.py` is a maintenance entrypoint, not a dead runtime import. In disposable storage it reproduces `assets/compact_themes.json` **byte for byte**, including all 80 themes in 10 categories. Retain both authoring source and packaged data.
- Kotlin gallery source/tests/debug runners are copied through `glob('*.kt')`, XML through `rglob('*.xml')`, and branding resources through recursive `copytree`. Filename-only searches would incorrectly label these as unused. The generated source, manifests and tests remain intact.
- `ThemeCatalog` opens `compact_themes.json` through AssetManager; layouts are renamed to upstream `.txt` assets before the keyboard parser loads them. English/Arabic resources are selected by Android qualifiers. No dynamic asset or locale variant was removed.
- Manifest component names instantiate VoicePermissionActivity, both instrumentation classes and ThemeSmokeActivity. R8 keep rules preserve the runners and reflected `KeyboardSwitcher.mLatinIME`; modifier tests reflect `metaState` and `metaPressStates`. All such paths and keep rules remain unchanged.
- The 1254x1254 approved icon master is a runtime launcher/welcome input and has a pinned validator hash. Its 2 MB size does not make it disposable. HAMNOVA images are separately referenced by its UI/metadata/tests and were retained.
- JVM and Python tests are discovered by Gradle/JUnit and unittest globs, not ordinary runtime imports. Fixture SQL/migrations are still explicitly loaded by `npm test:db` and schema tests. No test or fixture was removed as supposedly unused.
- Generated-but-versioned `next-env.d.ts` and `package-lock.json` are compiler/reproducibility inputs. Empty `.nojekyll` is a hosting convention. Documentation records accepted fixes, signing recovery, real-device acceptance and the owner's newly added roadmap; it is retained maintenance evidence.

## 4. Changes and affected references

Only `.gitignore` and this audit report change. Existing runtime source, workflow
configuration, overlay application logic, manifests/resources/layouts, 80-theme
catalog, tests, package IDs, Gradle/signing settings and all other tracked files
are untouched. No dependency/reference needs rewriting because no dependency was
removed or relocated.

`.gitignore` now excludes Python bytecode/cache, workflow-generated
`heliboard-source/`, `heliboard-baseline/`, `verified/`, KEYNOVA `test-results/`,
Android build/cache/local SDK paths, and private keystore container extensions.
These patterns affect future Git staging only; they do not delete files, change
runtime loading, disable artifact uploads or prevent GitHub Secrets restoration.
Existing web exclusions and `.env.example` exception remain unchanged.

Validated `git check-ignore --no-index` on 15 representative generated/private
paths. All 113 baseline tracked paths, including lockfiles, .env.example, icon
masters and every source/test/resource, plus this report remain eligible for Git
tracking. No actual owner key was created for this test.

## 5. Repository size

Sizes are the sum of tracked regular-file bytes, not filesystem block usage,
APK artifact storage or shared historical Git objects. The report is included in
the final value; historical Git objects were deliberately not rewritten.

| Measure | Before | Delivered tree |
| --- | ---: | ---: |
| Tracked files | 113 | 114 |
| Directories excluding root | 35 | 35 |
| Logical bytes | 5,696,243 | 5,731,104 |
| Logical MiB | 5.432 | 5.466 |

Application/build/test inputs are byte-identical; the increase is only the
433-byte `.gitignore` addition and this audit report. No tracked payload was
removed. Host test outputs and downloaded CI evidence are outside the checkout
or ignored and are not committed.

## 6. Local validation actually performed

| Local check | Actual result |
| --- | --- |
| Existing Python suites | PASS: 18 fixture tests + 31 branding/signing/diagnostic tests = 49; no skipped/disabled tests. |
| Existing production Java session/text tests | PASS: 44 JUnit tests (29 session + 15 text buffer), executed with the JDK compiler/JUnit 4.13.2. |
| Android Java/API compatibility compile | PASS against Android API 35; minimal compile-only IME/KeyboardSwitcher stubs validate adapter API usage. This is not a substitute for the full Gradle or Robolectric run below. |
| Python syntax | PASS: all 15 Python files parsed. |
| JSON/XML/PNG | PASS: all 7 JSON files, 10 XML files and 3 full PNG chunk/CRC streams. |
| YAML and Actions validation | PASS: all 4 workflows parsed and actionlint v1.7.12 clean. |
| Shell/embedded Python | PASS: 4 shell files, 31 workflow run blocks and 8 embedded Python blocks. |
| Curated theme source | PASS: generator run in disposable storage reproduces packaged JSON byte for byte; 80 themes, 10 categories. |
| Gitignore positive/negative checks | PASS: 15 generated/private examples ignored; all 113 baseline sources and the added report remain trackable. |
| Clean pinned upstream overlay, before/after | PASS: apply_overlay.py and validate_overlay.py for both baseline and cleaned tree. All 2,259 generated files, paths and bytes are identical (tree digest `ec1a39840f9ba127a82f3772f205be0e9f00e5c3711805e2b55c845bb2ef4692`). Includes Gradle, manifests/components, R8/reflective members, layouts, themes, voice and resources. |
| Baseline production APKs | PASS: downloaded #81 artifacts; actual APK SHA-256, apksigner integrity/v1+v2, public certificate and aapt IDs/version/branding verified independently; Debug 699 ZIP entries, Themes 695. |
| Baseline JVM evidence | PASS: downloaded reports; 277 overlay cases/8 failures and 193 upstream cases/8 identical failures; 0 skipped. |
| Final local diff | Only .gitignore and the required report; no deleted/renamed files or changed runtime/test/workflow inputs. |
| Local emulator/full Gradle | Not available locally (no adb/KVM/full SDK/NDK toolchain). Both complete builds and Android instrumentation are executed in GitHub Actions below. |

No claim is made that host tests alone exercise a physical speech service,
that all upstream tests pass, or that the unrelated web/database suites were
re-run. Their files are inventoried and unchanged.


## 7. Android build, regression and signing evidence

New complete Android build: **NOT YET SUBMITTED** at initial report creation.
The report will be finalized with the actual source commit, exact workflow URL,
unit/emulator outcomes, APK checksums and signing comparison after completion.
No required Android validation is claimed passed by local host checks alone.

## 8. Remaining risks and follow-up recommendations

1. Physical microphone/provider acceptance is still separate from scripted recognition tests and the real-InputConnection emulator tests. The existing roadmap and `branding/VOICE-ACCEPTANCE.md` describe this limitation; cleanup does not claim to repair unrelated voice issues or complete real-device acceptance.
2. The eight pre-existing upstream JVM failures remain disclosed and compared strictly; no failures, skipped cases or quality gates were suppressed. They concern grapheme/emoji, remote link/dictionary expectations and the existing Hangul case.
3. Baseline #81 already reported setup-java v4 deprecation and Node.js 20 action-runtime migration warnings. These were recorded before cleanup. Updating action majors is a separate reviewed infrastructure task; working workflow dependencies were preserved here.
4. External consumers of the two standalone quiz URLs cannot be enumerated from this repository. Obtain access/ownership evidence and a redirect plan before future removal. Do not delete HAMNOVA or its legacy tests as part of KEYNOVA maintenance.
5. The permanent private key remains exclusively in existing Secrets/runner temporary storage. `.gitignore` reduces accidental staging; it is not a replacement for Secret access controls or secure owner backups. No historical Git/key cleanup was attempted.
6. The exhaustive inventory below records purpose and retention rationale, not a claim of formal verification of every unrelated application's behavior. Zero removals is the safe result for this already small source-only branch.

## 9. Per-file inventory at the baseline

Every one of the 113 tracked baseline files appears exactly once. Direct references,
framework conventions, generated-source copy rules, dynamic resource loading,
component/reflective lookup and independent publication entrypoints were considered.
The added report is required audit evidence and is not part of the baseline count.

| Path | Bytes | Classification | Purpose / dependency / retention reason |
| --- | ---: | --- | --- |
| `.env.example` | 122 | REQUIRED — KEEP | Documented legacy environment template; placeholders only; preserved with the unrelated web project. |
| `.github/workflows/heliboard-inline-voice.yml` | 13,977 | REQUIRED — KEEP | Sole automatic KEYNOVA branch build; pinned upstream, overlay, both APKs, signing, JVM/baseline and Android gates. |
| `.github/workflows/heliboard-theme-verify.yml` | 18,263 | REQUIRED — KEEP | Reusable/manual existing-APK verification; referenced by the main workflow; provenance/signing/emulator dependencies. |
| `.github/workflows/pages.yml` | 737 | REQUIRED — KEEP | Independent main-branch/manual Pages deployment; implicit HTML/.nojekyll/public-file entrypoints; unchanged. |
| `.github/workflows/phase4-validation.yml` | 721 | REQUIRED — KEEP | Independent main-branch PR/manual web validation; package scripts and tests; unchanged. |
| `.gitignore` | 70 | REQUIRED — KEEP | Git tracking policy; generated/private-path exclusions validated without hiding any existing source. |
| `.nojekyll` | 0 | REQUIRED — KEEP | GitHub Pages hosting convention; empty sentinel is operational, not disposable. |
| `README.md` | 3,872 | REQUIRED — KEEP | HAMNOVA runtime, backup and legacy database-test documentation; preserve completed project work. |
| `app/actions.ts` | 2,867 | REQUIRED — KEEP | HAMNOVA application actions; imported by customer/reminder UI and UI tests; uses repository/domain validation. |
| `app/contact/page.tsx` | 2,666 | REQUIRED — KEEP | Next filesystem /contact route; owner/contact data and hamnova-logo asset; UI tests. |
| `app/customers/[id]/page.tsx` | 11,239 | REQUIRED — KEEP | Next dynamic customer-detail route; linked from customers/dashboard; edit/payment/renewal functions. |
| `app/customers/page.tsx` | 10,402 | REQUIRED — KEEP | Next /customers route; search and management UI; imports actions, repository and customer helpers. |
| `app/globals.css` | 19,977 | REQUIRED — KEEP | Global web styles imported by app/layout.tsx and read by UI regression tests. |
| `app/layout.tsx` | 1,519 | REQUIRED — KEEP | Next root layout/metadata; navigation, shared CSS and hamnova-icon public asset. |
| `app/page.tsx` | 5,321 | REQUIRED — KEEP | Next root dashboard route; repository/domain/customer helpers and reminder UI. |
| `app/reminder-list.tsx` | 6,039 | REQUIRED — KEEP | Dashboard reminder component; repository actions and browser clipboard/share fallbacks. |
| `app/settings/page.tsx` | 7,902 | REQUIRED — KEEP | Next /settings route; validated web backup/restore UI; linked by root navigation. |
| `heliboard-inline-voice/KEYNOVA_DEVELOPMENT_ROADMAP_AR.md` | 18,326 | REQUIRED — KEEP | Owner-added current roadmap and direct voice audit (latest two commits); preserve accepted checkpoint documentation. |
| `heliboard-inline-voice/README.md` | 6,874 | REQUIRED — KEEP | Overlay architecture, source pin, original feature/checkpoint and reproducible build instructions. |
| `heliboard-inline-voice/REPAIR-REVIEW.md` | 6,426 | REQUIRED — KEEP | Historical root-cause/regression/signing review; retained development evidence, not generated build output. |
| `heliboard-inline-voice/VoiceDictationSession.java` | 8,132 | REQUIRED — KEEP | Runtime session/generation/watchdog/backoff manager instantiated by VoiceInputController; copied by apply_overlay.py. |
| `heliboard-inline-voice/VoiceDictationSessionTest.java` | 13,360 | REQUIRED — KEEP | 29 provider/session/silence/lifecycle regressions; copied into test sources and required by CI. |
| `heliboard-inline-voice/VoiceInputController.java` | 17,638 | REQUIRED — KEEP | Runtime IME speech/InputConnection/lifecycle adapter injected into LatinIME by apply_overlay.py. |
| `heliboard-inline-voice/VoiceInputControllerTest.java` | 12,957 | REQUIRED — KEEP | 24 Android-adapter Robolectric regressions; copied into test sources and required by the CI focused gate. |
| `heliboard-inline-voice/VoiceListeningIndicator.java` | 7,414 | REQUIRED — KEEP | Runtime microphone/status/partial/waveform UI instantiated by VoiceInputController; copied by overlay. |
| `heliboard-inline-voice/VoicePermissionActivity.java` | 1,581 | REQUIRED — KEEP | Runtime permission Activity registered by generated main manifest; component entrypoint, not just Java references. |
| `heliboard-inline-voice/VoiceTextBuffer.java` | 3,813 | REQUIRED — KEEP | Runtime append-only text anchor/separator protection; controller and session manager dependencies. |
| `heliboard-inline-voice/VoiceTextBufferTest.java` | 7,203 | REQUIRED — KEEP | 15 realistic editor/spacing/composition regressions; copied into test sources and required by CI. |
| `heliboard-inline-voice/apply_overlay.py` | 16,699 | REQUIRED — KEEP | Primary fail-closed source transform called by both workflows; copies layouts/voice/tests, patches Gradle/manifests/lifecycle, invokes theme/branding overlays. |
| `heliboard-inline-voice/branding/BrandingTest.kt` | 2,146 | REQUIRED — KEEP | Two localized identity/icon/backup compatibility JVM tests copied by apply_branding.py and required by CI. |
| `heliboard-inline-voice/branding/README.md` | 2,450 | REQUIRED — KEEP | Approved icon/identity implementation and reproducible branding notes; preserve source provenance. |
| `heliboard-inline-voice/branding/SIGNING-REPAIR.md` | 3,240 | REQUIRED — KEEP | Permanent signing repair/security evidence; preserve signing operational knowledge. |
| `heliboard-inline-voice/branding/VOICE-ACCEPTANCE.md` | 4,650 | REQUIRED — KEEP | Real-device acceptance protocol; copied into both published APK artifacts by the full workflow. |
| `heliboard-inline-voice/branding/apply_branding.py` | 3,738 | REQUIRED — KEEP | Invoked by apply_overlay.py; recursively copies res tree to both variants, localizes names and patches welcome/About/test sources. |
| `heliboard-inline-voice/branding/prepare_signing_key.py` | 4,282 | REQUIRED — KEEP | CI restores current owner Secrets atomically without replacing an existing key; calls shared credentials/certificate helpers. |
| `heliboard-inline-voice/branding/report_build_failure.py` | 848 | REQUIRED — KEEP | Full build wrapper invokes it on Gradle failure to expose bounded sanitized diagnostics. |
| `heliboard-inline-voice/branding/res/drawable-nodpi/keynova_master.png` | 2,047,161 | REQUIRED — KEEP | Approved 1254x1254 master loaded by launcher drawable XML and patched Compose welcome screen; SHA pinned by validator. |
| `heliboard-inline-voice/branding/res/drawable/keynova_background.xml` | 140 | REQUIRED — KEEP | Adaptive icon background referenced by both v26 launcher resources; recursive resource copy and AAPT resolution. |
| `heliboard-inline-voice/branding/res/drawable/keynova_foreground.xml` | 294 | REQUIRED — KEEP | Adaptive icon foreground referenced by both v26 launcher resources; references approved raster master. |
| `heliboard-inline-voice/branding/res/drawable/keynova_monochrome.xml` | 323 | REQUIRED — KEEP | Adaptive monochrome layer referenced by both v26 launcher resources and BrandingTest. |
| `heliboard-inline-voice/branding/res/mipmap-anydpi-v21/ic_launcher.xml` | 154 | REQUIRED — KEEP | API 21-25 bitmap fallback; distinct ic_launcher resource ID referenced by manifest/upstream resources/BrandingTest. Identical bytes do not make different resource names interchangeable. |
| `heliboard-inline-voice/branding/res/mipmap-anydpi-v21/ic_launcher_round.xml` | 154 | REQUIRED — KEEP | API 21-25 bitmap fallback; distinct ic_launcher_round resource ID referenced by manifest/upstream resources/BrandingTest. Identical bytes do not make different resource names interchangeable. |
| `heliboard-inline-voice/branding/res/mipmap-anydpi-v26/ic_launcher.xml` | 293 | REQUIRED — KEEP | API 26+ adaptive icon; distinct ic_launcher resource ID referenced by manifest/upstream resources/BrandingTest. Identical bytes do not make different resource names interchangeable. |
| `heliboard-inline-voice/branding/res/mipmap-anydpi-v26/ic_launcher_round.xml` | 293 | REQUIRED — KEEP | API 26+ adaptive icon; distinct ic_launcher_round resource ID referenced by manifest/upstream resources/BrandingTest. Identical bytes do not make different resource names interchangeable. |
| `heliboard-inline-voice/branding/signing_credentials.py` | 2,318 | REQUIRED — KEEP | Shared normalized/redacted credential and subprocess handling for restore/Gradle/verification; both workflows/tests. |
| `heliboard-inline-voice/branding/tests/test_ci_diagnostics.py` | 3,678 | REQUIRED — KEEP | Discovered by test_*.py glob; diagnostic API/CLI/sanitization regressions (not necessarily imported by filename). |
| `heliboard-inline-voice/branding/tests/test_signing_credentials.py` | 7,748 | REQUIRED — KEEP | Discovered by unittest glob; LF/CRLF normalization, redaction, real step-boundary and subprocess regressions. |
| `heliboard-inline-voice/branding/tests/test_verify_apks.py` | 13,081 | REQUIRED — KEEP | Discovered by unittest glob; strict certificate/signature/keystore/checksum/variant regressions. |
| `heliboard-inline-voice/branding/verify-emulator.sh` | 1,711 | REQUIRED — KEEP | Reusable verification-only workflow entrypoint; invokes existing launch and complete gallery/emulator tests on retained APKs. |
| `heliboard-inline-voice/branding/verify_apks.py` | 9,745 | REQUIRED — KEEP | Both workflows verify selected keystore identity, APK integrity/schemes/package/checksums independently for both variants. |
| `heliboard-inline-voice/compare_test_results.py` | 2,304 | REQUIRED — KEEP | CI comparison of all JVM failures, skipped/missing tests against unmodified pinned upstream; prevents new failures being hidden. |
| `heliboard-inline-voice/layouts/arabic.json` | 8,576 | REQUIRED — KEEP | Copied to packaged layouts/main/arabic.txt; dynamically loaded by upstream keyboard parser and exercised on Android. |
| `heliboard-inline-voice/layouts/functional_keys.json` | 1,427 | REQUIRED — KEEP | Copied into packaged functional layout; PC modifiers/Tab/symbol controls; parser and on-screen shortcut tests. |
| `heliboard-inline-voice/layouts/qwerty.json` | 2,238 | REQUIRED — KEEP | Copied to packaged layouts/main/qwerty.txt; dynamically loaded by upstream keyboard parser and exercised on Android. |
| `heliboard-inline-voice/smoke-test.sh` | 3,456 | REQUIRED — KEEP | Primary emulator installation/launch/IME/identity gate; called by full build and branding/verify-emulator.sh. |
| `heliboard-inline-voice/themes/README.md` | 5,758 | REQUIRED — KEEP | Gallery architecture, catalog, preview/image/persistence/test documentation; retained maintenance reference. |
| `heliboard-inline-voice/themes/apply_themes.py` | 11,132 | REQUIRED — KEEP | Invoked by primary overlay; glob-copies Kotlin/tests/resources, registers components/keep rules and patches native renderer/settings. |
| `heliboard-inline-voice/themes/assets/compact_themes.json` | 31,024 | REQUIRED — KEEP | 80 entries dynamically loaded by ThemeCatalog.assets.open; source-of-truth JSON is reproduced by generate_catalog.py. |
| `heliboard-inline-voice/themes/debug/AndroidManifest.xml` | 545 | REQUIRED — KEEP | Copied into each variant and extended with voice runner; registers instrumentation/activity instantiated by Android component names. |
| `heliboard-inline-voice/themes/debug/ThemeSmokeActivity.kt` | 3,255 | REQUIRED — KEEP | Variant-glob-copied debug editor Activity; manifest-registered and used by real keyboard/clipboard tests. |
| `heliboard-inline-voice/themes/debug/ThemeSmokeInstrumentation.kt` | 29,787 | REQUIRED — KEEP | Variant-glob-copied instrumentation; invoked by adb component name; reflects KeyboardSwitcher.mLatinIME, protected by generated keep rules. |
| `heliboard-inline-voice/themes/emulator-test.sh` | 4,461 | REQUIRED — KEEP | Both CI paths invoke it for full/persist/rtl; safe fixture, IME selection, failure diagnostics and Android assertions. |
| `heliboard-inline-voice/themes/generate_catalog.py` | 8,946 | REQUIRED — KEEP | Offline curated catalog authoring source; standalone entrypoint exactly regenerates the packaged 80-theme JSON; keep even outside runtime. |
| `heliboard-inline-voice/themes/prepare-emulator-fixture.py` | 5,945 | REQUIRED — KEEP | Emulator scripts use it for safe XML preference preparation; strict transport/atomic writes/user-data protections and 18 regressions. |
| `heliboard-inline-voice/themes/res/values-ar/compact_gallery.xml` | 3,713 | REQUIRED — KEEP | Glob-copied Arabic locale resources selected by Android/RTL; English defaults are not a replacement. |
| `heliboard-inline-voice/themes/res/values/compact_gallery.xml` | 3,114 | REQUIRED — KEEP | Glob-copied English resources resolved through R.string by Compose gallery; filename need not appear in imports. |
| `heliboard-inline-voice/themes/src/ThemeArtwork.kt` | 6,722 | REQUIRED — KEEP | Glob-copied procedural theme artwork referenced by native gallery renderer; runtime class dependencies omit filename suffix. |
| `heliboard-inline-voice/themes/src/ThemeCatalog.kt` | 2,559 | REQUIRED — KEEP | Glob-copied serializable theme definitions, categories and dynamic asset loader; screen/repository/preview and tests. |
| `heliboard-inline-voice/themes/src/ThemeGalleryRepository.kt` | 15,824 | REQUIRED — KEEP | Glob-copied selection/preferences/custom background import/persistence/reset; injected native theme and settings dependencies. |
| `heliboard-inline-voice/themes/src/ThemeGalleryScreen.kt` | 18,780 | REQUIRED — KEEP | Glob-copied gallery Compose entrypoint wired into Settings navigation by apply_themes.py. |
| `heliboard-inline-voice/themes/src/ThemePreviewContext.kt` | 653 | REQUIRED — KEEP | Glob-copied isolated native preview context; used by preview renderer to preserve normal keyboard state. |
| `heliboard-inline-voice/themes/src/ThemePreviewView.kt` | 6,029 | REQUIRED — KEEP | Glob-copied native keyboard preview renderer; gallery screen and instrumentation depend on it. |
| `heliboard-inline-voice/themes/test_prepare_emulator_fixture.py` | 11,015 | REQUIRED — KEEP | Workflow explicitly discovers 18 strict ADB/XML/path/access/atomic/preservation regressions. |
| `heliboard-inline-voice/themes/tests/ModifierLifecycleTest.kt` | 3,180 | REQUIRED — KEEP | Glob-copied 4 required JVM modifier regressions; reflection accesses metaState/metaPressStates in upstream listener. |
| `heliboard-inline-voice/themes/tests/ThemeGalleryTest.kt` | 8,890 | REQUIRED — KEEP | Glob-copied 10 required JVM catalog/persistence/image/renderer regressions. |
| `heliboard-inline-voice/validate_overlay.py` | 10,498 | REQUIRED — KEEP | Fail-closed generated-source/resource/ID/voice/instrumentation checks called by both workflows. |
| `heliboard-inline-voice/voice/REPAIR.md` | 4,522 | REQUIRED — KEEP | Current voice architecture, verified defects and physical-acceptance limits; preserve completed repair evidence. |
| `heliboard-inline-voice/voice/debug/VoiceSmokeInstrumentation.java` | 6,745 | REQUIRED — KEEP | Explicitly copied to both variants, registered in generated manifests and kept by R8; real InputConnection with scripted provider. |
| `heliboard-inline-voice/voice/emulator-test.sh` | 659 | REQUIRED — KEEP | Full build runs both installed voice instrumentations before publishing APKs; strict success markers and failure evidence. |
| `index.html` | 13,650 | REQUIRED — KEEP | Published Pages root entrypoint; pages.yml uploads the repository root; public URL must remain stable. |
| `lib/app-info.ts` | 650 | REQUIRED — KEEP | Web identity/contact constants consumed by layout, contact/settings pages and UI tests. |
| `lib/backup.ts` | 12,280 | REQUIRED — KEEP | Web backup schema/parser/validation consumed by settings and repository; backup and runtime tests. |
| `lib/browser-fallbacks.ts` | 1,239 | REQUIRED — KEEP | Clipboard/share compatibility helpers used by reminders/settings and runtime tests. |
| `lib/customer-management.ts` | 5,100 | REQUIRED — KEEP | Customer edit/deletion validation and dashboard/management calculations; actions/UI and unit tests. |
| `lib/customer-search.ts` | 700 | REQUIRED — KEEP | Customer filtering/query and latest-search coordination; customers route and tests. |
| `lib/data.ts` | 203 | REQUIRED — KEEP | Stable web repository re-export facade consumed by pages/reminders. |
| `lib/domain.ts` | 4,111 | REQUIRED — KEEP | Subscription/payment/reminder business rules; actions, repository, UI and unit tests. |
| `lib/local-date.ts` | 351 | REQUIRED — KEEP | Local-calendar helper used by domain/repository/UI and date regression tests. |
| `lib/repository/indexeddb.ts` | 26,674 | REQUIRED — KEEP | Active web persistence, relationships and atomic backup/restore; app actions/data and repository tests. |
| `lib/types.ts` | 1,030 | REQUIRED — KEEP | Shared web domain types referenced by repository, backup and UI; type-only imports are dependencies. |
| `next-env.d.ts` | 132 | REQUIRED — KEEP | Next-generated but tracked type entrypoint explicitly included by tsconfig.json; required to typecheck. |
| `package-lock.json` | 39,532 | REQUIRED — KEEP | Pinned npm dependency resolution for reproducible web installation; retain generated lockfile. |
| `package.json` | 844 | REQUIRED — KEEP | HAMNOVA scripts/dependency entrypoint; phase4-validation.yml invokes npm test/typecheck/build. |
| `parts-of-speech-quiz-30.html` | 13,650 | REQUIRES FURTHER INVESTIGATION | Byte-identical to index.html but has a separate public URL; no access/bookmark/redirect evidence proves removal safe. |
| `parts-of-speech-university.html` | 15,576 | REQUIRES FURTHER INVESTIGATION | Independent static quiz entrypoint; whole-root Pages deployment can serve it; external URL usage is unknown. |
| `public/branding/hamnova-icon.png` | 1,990,032 | REQUIRED — KEEP | Public URL loaded by Next layout/metadata and inspected by web branding tests; unrelated approved asset. |
| `public/branding/hamnova-logo.png` | 920,512 | REQUIRED — KEEP | Public URL loaded by contact route and inspected by web branding tests; unrelated approved asset. |
| `scripts/run-database-tests.mjs` | 1,734 | REQUIRED — KEEP | npm test:db entrypoint; explicitly loads both migrations, legacy fixture and three SQL suites in a rolled-back schema. |
| `supabase/migrations/20260930000000_create_core_records.sql` | 4,117 | REQUIRED — KEEP | Historical schema still read by schema.test.mjs and the optional test:db runner; not unused despite local-only web runtime. |
| `supabase/migrations/20260930010000_phase3_reminders.sql` | 9,874 | REQUIRED — KEEP | Historical reminder migration still read by schema.test.mjs and test:db; preserve upgrade/test history. |
| `tests/backup.test.ts` | 7,743 | REQUIRED — KEEP | HAMNOVA test entrypoint explicitly listed by npm test; imports application modules or reads source/resources/migrations; preserve unrelated project regression coverage. |
| `tests/customer-management.test.ts` | 5,126 | REQUIRED — KEEP | HAMNOVA test entrypoint explicitly listed by npm test; imports application modules or reads source/resources/migrations; preserve unrelated project regression coverage. |
| `tests/customer-search.test.ts` | 2,348 | REQUIRED — KEEP | HAMNOVA test entrypoint explicitly listed by npm test; imports application modules or reads source/resources/migrations; preserve unrelated project regression coverage. |
| `tests/database-phase2.sql` | 1,814 | REQUIRED — KEEP | Legacy database suite/fixture explicitly read by scripts/run-database-tests.mjs (npm test:db); runtime inactivity does not make this test dependency obsolete. |
| `tests/database-phase3.sql` | 8,547 | REQUIRED — KEEP | Legacy database suite/fixture explicitly read by scripts/run-database-tests.mjs (npm test:db); runtime inactivity does not make this test dependency obsolete. |
| `tests/database.sql` | 3,363 | REQUIRED — KEEP | Legacy database suite/fixture explicitly read by scripts/run-database-tests.mjs (npm test:db); runtime inactivity does not make this test dependency obsolete. |
| `tests/domain.test.ts` | 6,847 | REQUIRED — KEEP | HAMNOVA test entrypoint explicitly listed by npm test; imports application modules or reads source/resources/migrations; preserve unrelated project regression coverage. |
| `tests/fixtures/phase3-legacy-reminders.sql` | 2,312 | REQUIRED — KEEP | Legacy database suite/fixture explicitly read by scripts/run-database-tests.mjs (npm test:db); runtime inactivity does not make this test dependency obsolete. |
| `tests/local-repository.test.ts` | 11,242 | REQUIRED — KEEP | HAMNOVA test entrypoint explicitly listed by npm test; imports application modules or reads source/resources/migrations; preserve unrelated project regression coverage. |
| `tests/runtime-fixes.test.ts` | 5,795 | REQUIRED — KEEP | HAMNOVA test entrypoint explicitly listed by npm test; imports application modules or reads source/resources/migrations; preserve unrelated project regression coverage. |
| `tests/schema.test.mjs` | 6,488 | REQUIRED — KEEP | HAMNOVA test entrypoint explicitly listed by npm test; imports application modules or reads source/resources/migrations; preserve unrelated project regression coverage. |
| `tests/ui.test.mjs` | 15,736 | REQUIRED — KEEP | HAMNOVA test entrypoint explicitly listed by npm test; imports application modules or reads source/resources/migrations; preserve unrelated project regression coverage. |
| `tsconfig.json` | 570 | REQUIRED — KEEP | TypeScript/Next compiler configuration; includes next-env.d.ts and generated .next types. |
