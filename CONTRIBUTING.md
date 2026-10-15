# Contributing

This is a small library with a single maintainer. Bug reports, protocol fixes
and documentation corrections are welcome. For a larger change, a feature
request is the easiest way to check that it fits before you write the code.

## Build and test

The Gradle wrapper is the only prerequisite; JDK toolchains resolve
automatically.

```bash
./gradlew build check
```

Run the tests that cover your change while you work, and the full command
before you submit. [CI](.github/workflows/ci.yml) is the authoritative gate
and additionally stages the publication and runs the consumer contract. On
hosts that install the shared build launcher, `./tools/verify` runs the same
tasks inside a memory-bounded build slice, and [`AGENTS.md`](AGENTS.md) asks
for it there instead of calling `./gradlew` directly.

## Where to read first

The "Where to look" table in [`AGENTS.md`](AGENTS.md#where-to-look) maps each
kind of change to the document you should read before making it. The
[documentation index](docs/README.md) lists everything else.

## Rules most changes run into

- Production code lives in six shallow packages below
  `at.bernhardberger.tvheadend.htsp`: `connection`, `jsonapi`, `messages`,
  `model`, `requests`, and `wire`. `model` must not depend on `requests`,
  `messages`, or `connection`. Architecture tests enforce this.
- Production code depends only on the Kotlin and JDK standard libraries and
  `kotlinx-coroutines-core`. No Android, Media3, native, or application code.
- Public suspending server round trips return typed outcomes. Cancellation
  propagates as cancellation.
- Error values never carry client secrets, credentials, endpoints, or
  throwables.
- The HTSP protocol surface is maintained by hand. A method or wire-field
  change ships with a focused regression test and cites the pinned upstream
  evidence in `docs/htsp-protocol/`.
- The public ABI is tracked in `api/htsp.api`. After an intentional public
  API change, regenerate the dump with `./gradlew updateKotlinAbi`; never edit
  it by hand. `check` fails when the dump and the code differ.
- Commit subjects follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat(htsp):`, `fix:`, `docs:`, `chore:`). An incompatible change adds `!`
  and a `BREAKING` entry in [`CHANGELOG.md`](CHANGELOG.md).

## Documentation

The [`README.md`](README.md), the guides in `docs/guides/`, and KDoc on public
declarations are the documentation. detekt fails `check` on undocumented public
classes, functions and properties.

Kotlin a reader might copy comes from a fixture under `consumer-contract/` that
compiles against the staged artifact. Put `<!-- source: consumer-contract/...kt -->`
(or `...kt#name` for a region) directly above the code block;
`DocumentationSnippetTest` fails when the block and the fixture differ. The
full rules are under
[Writing documentation](docs/README.md#writing-documentation).

## Reporting problems

- Bugs: use the
  [bug report form](https://github.com/bernhardberger/tvheadend-htsp/issues/new?template=bug_report.yml).
  Redact credentials, hostnames, IP addresses, tickets and recording paths from
  anything you attach.
- Feature requests: use the
  [feature request form](https://github.com/bernhardberger/tvheadend-htsp/issues/new?template=feature_request.yml).
  The library covers what HTSP offers; user interfaces, playback and caching
  belong to applications built on it.
- Vulnerabilities: follow [`SECURITY.md`](SECURITY.md) and report privately,
  never in a public issue.

## License

The library is GPLv3. Contributions are made under the same terms. Keep the
existing copyright and attribution notices intact; [`docs/licensing.md`](docs/licensing.md) explains them and the
project's lineage from tvhstream.
