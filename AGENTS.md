# HTSP library engineering guide

This GPLv3 library is an independently maintained descendant of
`Preclikos/tvhstream`. Preserve its attribution, notices, license, and recorded
protocol extraction boundary. Do not describe it as official TVHeadend software
or as wholly original work.

## Before editing

1. Inspect `git status -sb` and recent history. Preserve every existing change.
2. Select only task-matching authority through `docs/README.md`.
3. State scope, assumptions, invariants, owned paths, exclusions, and exact
   verification before editing one bounded slice.
4. Write a failing focused regression test first for runtime, lifecycle,
   protocol, API-policy, or governance behavior.

## Boundaries

- Production declarations use only the five shallow packages `connection`,
  `jsonapi`, `messages`, `requests`, and `wire` below
  `at.bernhardberger.tvheadend.htsp`.
- Protocol code depends only on sibling declarations, Kotlin/JDK facilities,
  and `kotlinx.coroutines`. Preserve `tools/check-htsp-protocol-boundary`.
- Public suspending server round trips return typed outcomes. Cancellation
  propagates as cancellation. Preserve `tools/check-public-api-outcomes`.
- Pinned HTSP v44 evidence and generated outputs live in `docs/htsp-protocol`.
  A method or wire-field change must update its catalog/spec/matrix/generated
  evidence in the same explicitly owned slice.
- The `consumer-contract` is static P3-E1 evidence only. Do not resolve, compile,
  execute, stage, or publish its external coordinate without a later explicit
  publication-policy authorization.
- Checkout-local publication verification is accepted P3-E2 evidence. The
  Gate C0 / P3-E2A release channel adds deterministic no-rebuild candidate and
  signed-bundle policy, but does not authorize signing, transfer, upload,
  publication, tagging, or release.
- Release-channel tools accept only `0.1.0-SNAPSHOT` and `0.1.0`. Snapshot CI
  produces no candidate. The future release
  candidate contains exactly five originals from verified and recreated local
  staging bytes.
- Candidate creation logs the exact inner TAR SHA-256 after final write/readback.
  Signing and candidate or signed-bundle verification require that same public
  CI digest explicitly before trusting manifest or signature evidence.
- The Maven/OpenPGP key is separate from the Android APK PKCS#12 key. Private
  key material and passphrases stay on the isolated owner-controlled signing
  host. Only reviewed public verification material may be tracked.

Do not add Android, Media3, native artifacts, application code, publication,
signing, release, remote, credential, or device operations incidentally.
The owner-manual Central Portal UI flow and any later GitHub pre-release remain
outside repository automation.

## Verification

Run focused checks and `./tools/verify-htsp --non-gradle` locally when Gradle 9
is unavailable. Exact-SHA CI owns default Gradle 9 execution. A local static
pass is not publication, distribution, Java 17 runtime, physical-device, or
release-readiness evidence.
