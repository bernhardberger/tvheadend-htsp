# Contributing

Start with `AGENTS.md` and the [documentation index](docs/README.md).

- Public suspending server round trips return typed outcomes. Cancellation
  propagates as cancellation.
- Update the API dump for every intentional public API change. ABI
  compatibility is fail-closed: an unexplained dump difference must fail.
- Production code stays in exactly five shallow packages below
  `at.bernhardberger.tvheadend.htsp`: `connection`, `jsonapi`, `messages`,
  `requests`, and `wire`. Architecture tests enforce this boundary.
- The HTSP protocol surface is maintained by hand. A method or wire-field
  change ships with a focused regression test.
- Run focused tests and the commands in `.github/workflows/ci.yml` before
  submitting a change.
