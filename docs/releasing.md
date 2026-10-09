# Releasing

One authorized owner completes preparation, the tag workflow and verification
in one task. An ordinary release through this existing path needs no planner,
model approval or separate convergence package. This does not authorize a tag
or publication: retain exact maintainer authorization and the checks below.
Authorization must cover the release operations and exact targets, including tag
push, credential use, signing and publication. When the task already covers these,
no repeated per-operation approval is needed. Ordinary repository commit/push
authority does not cover release operations. The tagged GitHub Actions workflow
on repository `main`, exact tag `v*`, is the only publication path; preparation or
checks alone grant no tag, credential or publication authority.

## Version and release types

The configured version is `X.Y.Z` or a pre-release `X.Y.Z-alpha.N`,
`X.Y.Z-beta.N` or `X.Y.Z-rc.N` (`N` starts at 1). Its tag is exactly `v` plus
the version. `-SNAPSHOT` versions only stage locally and are never published.
Maven and Gradle order these as alpha < beta < rc < release.

| Version | GitHub release |
|---|---|
| `0.Y.Z` | Prerelease; the major-zero line stays provisional |
| `X.Y.Z-alpha.N`, `-beta.N`, `-rc.N` | Prerelease |
| `X.Y.Z` with `X >= 1` | Full release, marked latest |

Publishing a stable release always marks it latest on GitHub, so the release
lane assumes stable releases are cut in ascending order from `main`. Maven
Central has no pre-release concept: a published `-alpha`, `-beta` or `-rc`
version is a permanent release that `latest.release` and open version ranges
can resolve until the final version exists.

Release notes state intentional source, binary, or behavioral changes. A
pre-release or major-zero release makes no compatibility promise; the policy
for stable releases is in [`versioning.md`](versioning.md).

Local staging does not establish external publication or availability.
Publication and availability are independently verified external state. This
repository links a release only after that verification succeeds.

## One-time GitHub setup

The `central` GitHub Environment contains exactly these release secrets:

- `MAVEN_GPG_PRIVATE_KEY`: the ASCII-armored dedicated Maven secret-key export;
- `MAVEN_GPG_PASSPHRASE`: its passphrase, with no newline;
- `CENTRAL_PORTAL_TOKEN`: the pre-base64-encoded Central `username:password`
  token used as a Bearer value.

The Environment does not encode an approval gate. Only the secret-bearing
publish step receives these values. They must never appear in source, command
arguments, artifacts, logs, reports, or generated output. The Maven key is
separate from the Android APK PKCS#12 key.

Reviewed exact-tag GitHub Actions and repository `main` are the release trust
boundary. A malicious approved workflow, GitHub compromise, or repository-
administration compromise could use or exfiltrate the key and Central token.
That residual risk is accepted for this release path.

## Automatic tag sequence

Pushing the exact tag for the configured release version starts the workflow.
The published `v0.2.0` release used the previous 22-asset GitHub layout and
remains immutable. Releases after `v0.2.0` use this sequence:

1. checks out the complete tag history without persisting credentials;
2. validates the Gradle wrapper;
3. builds, tests, and stages `build/local-maven` once, then verifies those staged
   bytes, runs the staged consumer contract, and checks the release setup without
   rebuilding the library;
4. records one SHA-256 manifest for the exact five Maven originals;
5. signs those originals with primary fingerprint
   `EAB02E488E7B944EAA6D65814BF0412FD2A3B741` and verifies every signature with
   the tracked public key;
6. creates the mandatory MD5 and SHA-1 sidecars and a byte-deterministic exact
   20-member Maven-layout Central ZIP;
7. submits the ZIP once with `publishingType=AUTOMATIC`, waits for `PUBLISHED`,
   and resolves and compares all 20 published Central members;
8. validates the two GitHub assets (Central ZIP and manifest) and the matching
   CHANGELOG notes with deterministic Maven Central links before any GitHub
   mutation;
9. creates or resumes a draft GitHub release of the type above, uploads only
   missing or mismatched expected draft assets, verifies all names, sizes, and
   SHA-256 digests, and only then publishes it.

The recurring path needs only the exact tag push. It has no workstation,
transfer-host, browser-upload, Portal-click, or separate approval step.

## Failure and immutability

Any tag, version, coordinate, fingerprint, staging, signature, sidecar, ZIP,
Central state, resolved-byte, or release-note mismatch stops the workflow before
the GitHub release. The Central upload is not retried. An ambiguous response
requires deployment-state investigation rather than a blind rerun.

If all five Central originals already exist and match, a rerun skips upload,
downloads the exact 20 published members, and verifies and reuses those bytes for
the GitHub release. Recovery does not read the private key or passphrase.
Missing Central members, partial presence, or any mismatch fails. A GitHub
failure after some draft assets were stored leaves the release as a draft; a
rerun retains exact assets and completes the missing uploads before publishing.
Wrong expected draft assets are replaced, but unexpected or duplicate names
fail closed. A rerun after a lost final publish response accepts the published
release only when its tag, title, notes, release type, and both asset
names, sizes, and SHA-256 digests already match. Published release bytes are
immutable and must never be replaced. Setup checks and local or CI staging are
not publication, distribution, Java 17 runtime, support, or release-readiness
evidence.

The 20 signed and checksummed Maven members remain in the Central ZIP and are
verified against Central. They are not duplicated as individual GitHub assets.

[`../release/openpgp/README.md`](../release/openpgp/README.md) defines the key and
signature-verification contract.
