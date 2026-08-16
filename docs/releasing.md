# Releasing

`0.1.0` is the first provisional major-zero release. Release notes for `0.1.0`
define only the initial provisional baseline. They do not promise compatibility
or support. Major zero communicates that status; the Maven version does not need
an alpha suffix.

Local staging does not establish external publication or availability.
Publication and availability are independently verified external state. This
repository does not claim that publication has occurred.

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
For the current release that tag is `v0.1.0`. The workflow:

1. checks out the complete tag history without persisting credentials;
2. verifies wrapper provenance and runs the complete standalone verifier;
3. uses the verifier's recreated `build/local-maven` staging without rebuilding;
4. records one SHA-256 manifest for the exact five Maven originals;
5. signs those originals with primary fingerprint
   `EAB02E488E7B944EAA6D65814BF0412FD2A3B741` and verifies every signature with
   the tracked public key;
6. creates the mandatory MD5 and SHA-1 sidecars and an exact 20-member
   Maven-layout Central ZIP;
7. submits the ZIP once with `publishingType=AUTOMATIC`, waits for `PUBLISHED`,
   and resolves and compares all 20 published Central members;
8. validates the exact 22 GitHub assets (the same 20 Maven files, Central ZIP,
   and manifest) and matching CHANGELOG notes before any GitHub mutation;
9. creates or resumes a draft GitHub prerelease, uploads only missing or
   mismatched expected draft assets, verifies all names, sizes, and SHA-256
   digests, and only then publishes the prerelease.

The GitHub prerelease marker does not change the Maven version or tag vocabulary.

The recurring path needs only the exact tag push. It has no workstation,
transfer-host, browser-upload, Portal-click, or separate approval step.

## Failure and immutability

Any tag, version, coordinate, fingerprint, staging, signature, sidecar, ZIP,
Central state, resolved-byte, or release-note mismatch stops the workflow before
the GitHub release. The Central upload is not retried. An ambiguous response
requires deployment-state investigation rather than a blind rerun.

If all five Central originals already exist and match, a rerun skips upload,
downloads the exact 20 published members, and verifies and reuses those bytes for
the GitHub prerelease. Recovery does not read the private key or passphrase.
Missing Central members, partial presence, or any mismatch fails. A GitHub
failure after some draft assets were stored leaves the release as a draft; a
rerun retains exact assets and completes the missing uploads before publishing.
Wrong expected draft assets are replaced, but unexpected or duplicate names
fail closed. A rerun after a lost final publish response accepts the published
prerelease only when its tag, title, notes, prerelease type, and all 22 asset
names, sizes, and SHA-256 digests already match. Published release bytes are
immutable and must never be replaced. Setup checks and local or CI staging are
not publication, distribution, Java 17 runtime, support, or release-readiness
evidence.

[`../release/openpgp/README.md`](../release/openpgp/README.md) defines the key and
signature-verification contract.
