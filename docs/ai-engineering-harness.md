# Local instruction maintenance and delivery

`AGENTS.md` is the always-visible repository entry point. It retains domain
invariants and routes workflow detail to local documents. `.opencode/opencode.json`
owns project configuration; `.opencode/agents/*.md` own role settings and bounded
contracts. Local skills route unresolved Kotlin questions to focused references.
There are currently no local command prompts. Preserve skill attribution in
`.opencode/skills/UPSTREAM.md` and vendored reference provenance.

## Instruction changes

Inspect only active surfaces relevant to the outcome. Keep skill triggers tied to
the actual design or review question, not the presence of Kotlin keywords. Use
only the applicable procedure steps and references; API advice cannot authorize
ABI changes, and coroutine advice must preserve transport-owned lifetimes.

For low-impact Markdown cleanup, run affected existing checks (review routing:
`./test-review-routing.sh`), inspect the final diff and compare role model, effort,
permission, budget, delegation and depth settings exactly against the starting
revision. Preserve reviewer evidence/verdict contracts and planner evidence access.
Do not add prompt-wording tests or run the full product build just for prose.
Code and publication changes retain the gates in `AGENTS.md` and `docs/releasing.md`.

Use fresh local loader diagnostics where affected: `opencode debug agent <name>`
and `opencode debug skill`. Inspect only relevant output; resolved global config
may contain sensitive provider details. Fresh loading proves saved definitions
parse, not that an existing running backend or session adopted them. Report any
unavailable checks precisely. Do not restart an active backend as part of cleanup;
cached sessions may retain previous bodies until a separately authorized restart.

## Outcome and delivery

One primary owns implementation, checks, ordinary recovery and authorized delivery.
Do not create administrative successor tasks for ordinary in-scope corrections.
Preserve existing work attribution and coordinate actual conflicting edits,
Git/build actions and target identity. Repository/resource overlap alone does not
gate centrally admitted work or create a lease/lock requirement. This does not
relax the writable child's separate same-worktree restriction.

Explicit task authority must cover operations and targets. Standing authorized
repository delivery can include ordinary follow-up commits without a numeric
budget or delivery amendment: `optional` permits zero or more, and legacy
`exactly_one` requires at least one. Preserve original and applicable amendment-
checkpoint ancestry, published tags and all gates. This grants no unrelated
commit, remote, credential, release, server or device operation.

Before committing inspect `git status -sb`, the full scoped diff and
`git log --oneline -10`. Stage only intended paths, use the repository commit style,
and deliver only to the authorized remote/branch with a non-force push. Verify the
remote branch resolves to the exact full delivered HEAD and report scope, checks
and limitations. Do not amend or rewrite history without explicit authority.

For centrally admitted work, use the supplied coordinator submission command only
after required checks and Git postconditions pass. Changed-history success must
include exact final delivered HEAD with `--commit`; omit it only for unchanged
optional history. Preserve the admitted blocked/failed reporting rules and do not
claim completion before submission succeeds. Standalone work reports directly to
its maintainer and does not require central workspace files or a coordinator.
