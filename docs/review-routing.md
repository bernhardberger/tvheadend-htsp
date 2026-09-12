# Delegation and review routing

Read before child dispatch or reviewer follow-up. `AGENTS.md` decides when review
is required; agent files own exact model, effort, permissions, finite step budgets
and response contracts. This procedure works from a standalone clone.

## Packet and ownership

Supply the outcome, hard requirements, relevant diff or exact readable paths,
evidence, question and stop condition. Inline relevant hard requirements when a
restricted child cannot read their source; do not require external workspace
policy or ledgers. The planner retains access to directly relevant repository
source, tests, call chains and `AGENTS.md` within its existing read permissions.

Children keep their configured effort, permissions, delegation depth and finite
budgets. Choose effort up front for evident difficulty: override the implementer
to Low for explicitly mechanical work or High for difficult implementation;
prior failure at a lower effort is not required. Max is exceptional for a concrete
unresolved hard case. Model and effort assignments otherwise remain in
configuration; retired roles stay retired.

`htsp-implementer` is the one writable child. It may edit and run Gradle inside a
single delegated slice with named paths, tests and gate, but never commits, tags,
publishes or reaches a server. The primary reviews its diff, runs the final gate
and owns commits. Never run it while another writer is editing the same worktree.
A task's narrower delegation envelope still controls whether it can be used.

Resolve routine choices within accepted requirements and writable scope; return
consequential product or authority gaps and missing load-bearing evidence. The
primary owns ordinary recovery and integration through the authorized outcome.

## Independent review

Give the independent Astra primary and Opus second reviewers the same bounded
change and evidence. Keep the second initial packet free of the first reviewer's
verdict and findings. UX-specific roles are not engineering-review substitutes.
The experimental Muse role is not a replacement for required reviewers and does
not add an automatic third review.

Reviewers treat commit identity, ancestry, frozen state and gate status as
caller-provided evidence: their permissions cannot verify these. Supply that
evidence explicitly. Preserve each role's finding evidence and final verdict
contract (`BLOCKING`, `NON_BLOCKING`, `CLEAN`, `INSUFFICIENT_EVIDENCE`); packets
cannot redefine labels, roles, permissions or generic review policy.

## Opus preflight and recovery

Before **every** Opus review or follow-up dispatch, execute from the repository:

```sh
./review-provider-route.sh select eligible
```

Only successful `opus` output permits dispatch. Never source the selector or its
credential file. Unknown or unavailable quota returns `astra` for an independent
Astra fallback. Record the reason and absent Opus coverage.

If Opus hits quota exhaustion despite preflight, abort that exact reviewer through
supported session control and verify it stopped. Do not wait for quota reset,
retry in a loop or spawn repeated Opus replacements. Continue fallback and
independent authorized work. If supported stop control is unavailable, report the
gap rather than claim termination. Explicitly non-substitutable admitted gates
require central reconciliation, not silent waiver.

## Adjudication and verification

The implementing primary adjudicates both reviews and fixes supported findings.
Follow up only unresolved findings or material changes; re-review only a specific
fix whose correctness remains uncertain. Closure packets name prior finding IDs,
the fix delta and directly affected neighboring logic. No review packages,
automatic third reviewer or repeated broad audits. Reuse successful gates for
unchanged relevant state; preserve all explicit admitted authority and gates.

Existing routing check: `./test-review-routing.sh`.
