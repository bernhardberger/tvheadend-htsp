# Releasing

`0.1.0` is the first provisional major-zero release. Release notes for `0.1.0`
define only the initial provisional baseline. They do not promise compatibility
or support. Major zero communicates that status; the Maven version does not need
an alpha suffix.

Candidate preparation and local staging do not establish external publication or
availability. Publication and availability are independently verified external
state. External publication, signing, tagging, and release creation require
separate owner authorization.

## Prepared release-channel boundary

The repository now defines a preparatory, fail-closed release channel. This is
tooling and policy only. It is not release, signing, publication, distribution,
or release-readiness evidence.

`tools/check-published-jvm-compatibility` accepts only the
`0.1.0-SNAPSHOT` staging topology and the `0.1.0` release topology. Every
other version is rejected. Both forms
retain the exact five originals and the POM, module metadata, JVM 17 class,
dependency, GPLv3, predecessor, and no-Android/native checks. Release originals
use exact unsuffixed filenames and do not depend on snapshot metadata.

In release mode, exact-SHA CI first runs the complete verifier and
recreates and compares the checkout-local staging bytes. It then packages those
same five originals without rebuilding. The deterministic candidate TAR records
the exact Maven paths, sizes, SHA-256 values, source commit and tree, and CI run
provenance in canonical JSON. After its final exclusive write and readback, the
candidate tool logs exactly `CANDIDATE_TAR_SHA256=<lowercase-sha256>`. This
public digest identifies the inner TAR, not the service archive created by the
CI artifact store. Snapshot CI skips candidate creation and upload. It logs no
candidate digest.
The CI job keeps read-only repository permission, does not persist checkout
credentials, and contains no key, secret, signing, or publication step.

Candidate and signed-bundle readers use exact inventories. They reject unsafe or
ambiguous paths, links, special or sparse members, duplicates, case collisions,
executable or unsafe modes, oversized members, noncanonical metadata, path
escapes, stale bytes, and preexisting output. They read approved members
directly and do not generically extract untrusted TAR or ZIP input.

## Isolated owner signing

`tools/sign-central-bundle` is for a trusted clean checkout at the candidate's
exact commit on the isolated owner-controlled signing host. It validates the
required public `--candidate-sha256` value against the candidate bytes before
parsing the candidate or invoking GPG, then validates the candidate and
checkout. It never runs Gradle, rebuilds,
generates source, mutates Git, contacts a network service, or publishes. It
selects the full tracked primary fingerprint and relies only on interactive
gpg-agent and pinentry. It signs each of the five originals with detached ASCII
armor, checks machine-readable `VALIDSIG` output against the same primary
fingerprint, and proves that original bytes did not change.

The signed evidence preserves the candidate TAR and manifest, source commit and
tree, CI provenance, five originals, signatures, MD5, SHA-1, internal SHA-256,
the signer-tool SHA-256, and the Portal ZIP SHA-256. Each original records its
size and SHA-256 before signing, after signing, when returned, and in the Portal
ZIP. The deterministic Maven-layout Portal ZIP contains only each original, its
detached signature, and the original's MD5 and SHA-1 sidecars. Checksum sidecars
are not signed, and `.asc` files receive no checksum sidecars.
The signer writes the verified evidence TAR and the exact Portal ZIP as separate
exclusive outputs, while also preserving the Portal ZIP inside the evidence TAR.
The signer writes the evidence TAR first. Only after that succeeds does it write
the directly uploadable Portal ZIP. It then reopens both outputs without
following links, compares them with the verified in-memory bytes, and repeats
the signed-bundle and direct-ZIP verification. Any write, readback, identity,
byte, digest, or verification failure removes both outputs.

Candidate and signed-bundle verification require the same public CI digest:

```text
./tools/check-release-candidate --verify-candidate CANDIDATE_TAR --candidate-sha256 CI_SHA256
./tools/sign-central-bundle --candidate CANDIDATE_TAR --candidate-sha256 CI_SHA256 --output build/release/htsp-0.1.0-signed-bundle.tar
./tools/check-release-candidate --verify-signed-bundle build/release/htsp-0.1.0-signed-bundle.tar --portal-zip build/release/htsp-0.1.0-central.zip --candidate-sha256 CI_SHA256
```

The signed evidence candidate record must contain that same digest. Returned
bundle verification compares the caller-provided CI digest with the embedded
candidate TAR before accepting its manifest, originals, or signatures. It also
requires the separate direct Portal ZIP and proves that its final bytes are
identical to the canonical ZIP preserved in the evidence TAR and its manifest.

The first release uses owner-manual Central Portal UI upload and the owner makes
the Publish or Drop decision. There is no Portal token, upload client, or
cross-host orchestrator. Candidate transfer and signed-bundle transfer are also
owner operations. The release uses the `v0.1.0` tag vocabulary. A
GitHub Release may independently use its optional pre-release marker as a later
Gate C action, only after Maven Central resolves the exact immutable coordinate;
that marker does not change the ordinary Maven version or tag vocabulary. No
tag or GitHub Release is created by this preparation.

The repository now tracks the reviewed public-only key export and exact primary
fingerprint. Setup verifies both in an isolated temporary keyring. This is
verification material and setup evidence only. It does not provide or prove
access to the private key or passphrase, signing, candidate creation,
publication, distribution, or release readiness.

[`../release/openpgp/README.md`](../release/openpgp/README.md) defines the public
verification contract. The private key and passphrase remain on the isolated
owner-controlled signing host. Candidate creation, signing, transfer,
publication, and every other Gate C action still require separate owner
authorization. Tracked public-key setup and non-Gradle policy verification remain
valid without pretending that signing or publication occurred.
