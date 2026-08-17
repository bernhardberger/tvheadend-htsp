# Contributing

Start with `AGENTS.md` and the [documentation index](docs/README.md).

- Public suspending server round trips return typed outcomes. Cancellation
  propagates as cancellation.
- Update the API dump for every intentional public API change. ABI
  compatibility is fail-closed: an unexplained dump difference must fail.
- Production code stays in exactly five shallow packages below
  `at.bernhardberger.tvheadend.htsp`: `connection`, `jsonapi`, `messages`,
  `requests`, and `wire`. Run `tools/check-htsp-protocol-boundary` after package
  or dependency changes.
- Keep `htsp_surface.py`, the generators, generated Kotlin, `htsp_spec.json`,
  and `HTSP_METHOD_MATRIX.md` synchronized. A method or wire-field change must
  update its catalog and generated evidence together.
- Run focused tests and the commands in `.github/workflows/ci.yml` before
  submitting a change.
