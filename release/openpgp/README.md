# Public OpenPGP verification material

This directory defines the public-only verification contract for the planned
first Maven Central release. The tracked public key and primary fingerprint are
the verification identity for release signatures. Candidate creation remains
release-only.

The owner created a dedicated Maven/OpenPGP key on the isolated signing host.
The Android APK PKCS#12 key is a separate credential and must never be reused.
The approved public UID is exactly
`Bernhard Berger <bernhard.berger@gmail.com>`. The key has no expiry. Its
primary key must itself be signing-capable.

The directory contains these two tracked regular files:

- `public-key.asc`: one ASCII-armored public key block containing no secret or
  private key packets, including no secret primary or secret subkey packet;
- `primary-fingerprint.txt`: the full 40-hex uppercase primary fingerprint,
  followed by one newline.

Placeholders, short key IDs, subkey fingerprints, multiple primary keys,
ambiguous UIDs, invalid, never-valid, disabled, revoked, or expired key or UID
states, expiring keys, and private packets are rejected. The armor must begin
with the public block header, end with the matching footer and newline, and
contain no prefix, suffix, or additional armor block. Verification imports only
the tracked public material into a new
temporary keyring. A detached signature is accepted only when machine-readable
`VALIDSIG` evidence names the tracked fingerprint as both signer and primary
fingerprint.

The private key and its passphrase remain on the isolated owner-controlled
signing host. The passphrase is entered only through the ordinary interactive
gpg-agent and pinentry flow. It must not enter source, arguments, environment
variables, files, Gradle properties, logs, reports, CI, or this engineering
host.

The public key is published at `keyserver.ubuntu.com`, a keyserver
currently supported by Maven Central, and is retrievable by its full
fingerprint. The tracked export matches the retrieved public key. The owner also
verifies the public export, full fingerprint, exact UID, no-expiry state,
primary signing capability, and absence of secret packets before accepting the
tracked public-only change. Key generation, public export, keyserver
publication, and approval are owner operations outside the release tooling.
