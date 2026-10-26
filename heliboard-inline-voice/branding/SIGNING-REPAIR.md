# Permanent signing verification repair

Base: `6306e77cd42b028d2dabadcf0cf4387b72a512d0`. The previous CI restored the
owner's permanent keystore successfully, but signature verification used raw
secret text after restore and Gradle had normalized boundary whitespace. Exact
workflow reproduction showed successful restoration followed by a silent
keytool failure for LF/CRLF secrets. Keytool diagnostics were swallowed by the
certificate-export/hash pipeline. The previous certificate regex also ignored
malformed records when a valid record was present.

The shared credential policy now normalizes password/alias in every signing
subprocess, including Gradle, without changing repository secrets. Keytool
exports DER to a separate temporary file; its exit status is checked before
hashing. Both APK variants independently pass apksigner integrity verification,
strict signer/certificate parsing, comparison to the actual keystore and the
existing approved public certificate pin, package/version/bilingual branding
checks, and SHA-256 recording. v1 is required for API 21; v2 or v3 satisfies the
modern platform policy. Optional v3/v4 absence does not fail a valid v1/v2 APK.
Repeated scheme references to one certificate are accepted; extra identities,
malformed/missing metadata and all nonzero tool exits fail.

Keystore recovery uses the existing owner-provided Secrets and private alias;
there are no default passwords, key aliases or replacement-key generation.
Recovery validates the private-key entry and approved fingerprint before an
atomic create, and never overwrites an existing key. Diagnostic reports redact
raw and normalized credentials and contain no private-key material.

The original 44 Python tests are retained. Five additional tests cover missing
credentials, CR/LF normalization, diagnostic redaction, command exit status,
and full restore/verify workflow steps in separate processes with four boundary
whitespace scenarios. Keytool tests use real disposable PKCS12 files; simulated
APK-tool responses in local tests do not certify production APK bytes.

The original upstream JVM comparison and all 26 project JVM regression gates
remain enabled. APK publication now follows successful Android full/persist/RTL,
bilingual typing, shortcut, rotation and side-by-side checks. Artifacts include
the signed APK, checksum, public signing report and the real microphone
acceptance protocol. Real microphone-to-text acceptance is not established by
JVM tests; `VOICE-ACCEPTANCE.md` must be completed before a final release claim.

Application source/resources, package IDs, themes, layouts, voice controller,
preferences and Android instrumentation assertions are unchanged. A full build
is required solely because the latest source APKs were not retained after the
previous failed gate. One push selects one full build; no duplicate dispatch.

Local verification also used the official Android build-tools 37.0.0 archive
verified against Google's repository checksum: two disposable signed resource
APK fixtures passed real apksigner/aapt and checksum checks with CRLF credentials;
a modified signed APK was rejected. These fixture results validate the tooling,
not the yet-to-be-built KEYNOVA application binaries.
