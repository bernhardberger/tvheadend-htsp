# Bounded maintenance outcome

P25-H1 started at `d2a3ade1e8865e60b32d95439d2797e9948b2fcc` with a clean
HTSP checkout. Authoritative inspection confirmed P24-S1 and P24-A3 succeeded
before execution and P25-H1 owned HTSP. Their completed results establish
published SDK consumer fixtures and Player screenshot/standalone CI coverage;
those historical cross-repository leads are not unfinished HTSP work.

## Ranked shortlist

Locations below refer to the starting commit.

| Rank | Candidate and evidence | Benefit, risk and verification | Decision |
| --- | --- | --- | --- |
| 1 | `src/main/kotlin/at/bernhardberger/tvheadend/htsp/wire/HtspCodec.kt:76-79`: `isMuxPkt` and `tsPayload` are internal helpers whose only callers are assertions in `HtspCodecTest.kt:55-56` and `HtspGoldenCorpusTest.kt:96-105` under `src/test/kotlin/at/bernhardberger/tvheadend/htsp/`. Repository search confirms no production or consumer-contract calls and no tracked public ABI entries. | Remove two unused production entry points, including a fallback branch exercised by no production path. Low risk; execute codec, golden corpus and binary ownership tests, then build/check including ABI validation. | Confirmed, retained. |
| 2 | `src/main/kotlin/at/bernhardberger/tvheadend/htsp/wire/HtspCodec.kt:308-341,398-427`: outbound encoding builds and copies an intermediate complete byte array per field. | Potential allocation reduction; practical request-workload benefit unmeasured. Medium risk to lengths, ordering and nested serialization. Would require repeated representative outbound request allocation/time measurements plus golden-byte and seeded round-trip checks. | Not retained: no measured workload need; not evidence of inbound playback cost. Root buffering has a real framing/atomic-write purpose. |
| 3 | `src/main/kotlin/at/bernhardberger/tvheadend/htsp/connection/HtspSubscriptionEventBuffer.kt:82-126,188-204`: eager index removal appears to make lazy stale-node checks and `queued` state redundant. | Potentially simpler membership invariant and abandonment. Medium risk to eviction/order/resource retention. Would require mixed offer/poll/evict/abandon regression coverage and representative resource measurements for any performance claim. | Hypothesis, not retained in this bounded slice. Preserve the eviction index and its reflection-based retention regression: output-only tests cannot establish bounded retained references. |
| 4 | `gradle.properties:1-2`, `build.gradle.kts:82-84,308-310`, `.github/workflows/ci.yml:26-33`: existing configuration/build caches and filtered JUnit execution already support focused verification; CI uses checkout-local staging and consumer paths. | No demonstrated DX repair benefit. New infrastructure would add maintenance. Reproduce existing build and focused test tasks before considering changes. | Rejected: current tasks work; historical Player sibling-workspace failure is not an HTSP defect. |

## Retained protection

No test case was deleted. The round-trip test now asserts `rawPayload` bytes
directly. The golden test still asserts the method, sequence, payload bytes,
identity with the decoded field array, exact re-encoding, and null raw payload
for an ordinary message. Removed helper assertions merely repeated the method
comparison or payload identity already asserted. The unused helper fallback
was not a compatibility contract.

Do not generalize this finding to the raw/typed model boundary:
`src/main/kotlin/at/bernhardberger/tvheadend/htsp/messages/HtspServerMessageDispatch.kt:21-42`
distinguishes standalone field decoding from transport-owned payload decoding
and checks array identity before transferring ownership. Its overloads and the
raw payload representation are purposeful and remain unchanged. Public request
conveniences likewise remain a supported typed API, not dead pass-through code.

## Evidence and limits

On this Linux host, using the repository wrapper and its configured JDK 21
toolchain, `./gradlew build check` passed at baseline in 6.047 seconds wall time;
tests were up-to-date, not newly executed. The existing command
`./gradlew test --tests '*HtspCodecTest' --tests '*HtspGoldenCorpusTest' --tests '*HtspBinaryTest' --rerun`
then executed successfully before (1.757 seconds) and after (6.667 seconds,
including recompilation) the change. These are single diagnostic observations,
not comparable performance samples or evidence of a speedup. The first timing
attempt failed before Gradle because `/usr/bin/time` was unavailable; Bash's
existing `time` keyword sufficed without installing tooling.

Gradle was serialized through the existing shared host lock
`/tmp/tvheadend-player-gradle-$(id -u)/gradle.lock`. Executed test runs unset
`TVHEADEND_HTSP_NEAR_LIVE_TEST`, `TVHEADEND_HTSP_SPEED_REPLY_TEST` and the four
`TVHEADEND_HTSP_LIVE_*` endpoint/credential variables. No live server or device
verification is authorized or claimed.

The benefit is two fewer internal methods to maintain, not a runtime or DX
performance claim. Review pairing is not applicable under AGENTS.md's
low-impact exception: only test-called internal methods and redundant helper
assertions changed, with no protocol behavior, lifecycle, public API, build or
publication logic changes. No Opus dispatch or quota decision was needed.
No library release or consumer adoption is necessary for this internal-only
maintenance change; published 0.7.0 tags and bytes remain untouched. Final
build/check, exact-commit CI and remote delivery evidence belong to the immutable
P25-H1 result rather than a self-referential commit record here.
