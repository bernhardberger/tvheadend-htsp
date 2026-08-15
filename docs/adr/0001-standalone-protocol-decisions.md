# ADR 0001: standalone HTSP decisions

Status: accepted before standalone extraction.

- **D1 — identity.** Repository name `tvheadend-htsp`, coordinate
  `at.bernhardberger.tvheadend:htsp`, unchanged package root
  `at.bernhardberger.tvheadend.htsp`, independently maintained and unofficial.
- **D2 — package taxonomy.** Exactly five shallow packages: `wire`, `requests`,
  `messages`, `connection`, and `jsonapi`; no flat-root or deeper compatibility
  packages.
- **D3 — canonical execution.** `HtspConnection.execute` accepts only a finite
  catalog `HtspRequest`; generated extensions are convenience delegates.
- **D4 — mapping.** Exceptions thrown by a consumer transform propagate and are
  never converted to `ServerError`.
- **D5 — errors.** Keep the compressed public failure categories; no new public
  cases are introduced for diagnostics.
- **D6 — probe ownership.** Client probe policy is not protocol-library API;
  only mechanically necessary protocol behavior remains here.
- **D7 — governance.** Fail closed on the exact five-package set and preserve
  protocol boundary, KDoc, generated drift, outcomes, and attribution checks.

The following are recorded but deliberately not implemented here: a session
façade, a public wire-value AST, a capability profile, a mux-stream API, and a
Gradle 9 migration of the source monorepo. Publication configuration, local
Maven staging, external-coordinate consumer compilation, signing, release, and
monorepo consumption are separate later stages, not deferred design permission.
