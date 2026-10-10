# WELCOME delivery-stage diagnostics — REVIEW PENDING

Exact tested source: `617ed5a0507ab7769efd00ab85c3d1c7758c63f2`, based on `b75d8ec6ab62f6a476139bb77bf8c6cd842b51fa`, on PR19. The current owner instruction resumes one bounded WELCOME investigation. PR19 is the authoritative race branch; stacked PR20 is the separate LAN-selection task and was not changed.

## Finding

The pinned initial failure returned `null` from the test receiver's timed `recvStr()` and then threw at `.contains("WELCOME")`. The later post-health-readiness failure reported publisher `WAITING_RECEIVER` / receiver `none`; this shows the publisher had not recorded a receiver handshake, but does not show whether the client had connected or whether HELLO reached the ROUTER.

This test-only patch adds two synthetic checks:

- A real `LiveBridge` is health-ready while a DEALER sends HELLO to an unused loopback endpoint. `send()` returns true, but no WELCOME arrives; the publisher remains `WAITING_RECEIVER`, receiver `none`, depth zero and valid. This confirms that local send acceptance does not establish remote delivery and reproduces a failure path compatible with the observed publisher state.
- Eight independent publishers start concurrently, each completing two health-ready HELLO/WELCOME/START handshakes. All 16 handshakes passed. This adds scheduler pressure but did not reproduce the intermittent CI failure.

The existing ROUTER stub still covers the other failure stage: it receives HELLO and deliberately withholds WELCOME. It verifies the original 2-second receive limit and single-attempt behavior. Neither controlled scenario proves the cause of the intermittent CI failure.

## Scope and validation

Only `SyntheticBridgePeerTest.java` changed in source commit `617ed5a`. No production Java code, protocol, timeout, retry, callback, queue, journal, schema, defaults or assertions were changed or weakened. No Bookmap, live capture, deployment, merge or release operation was performed.

On the exact source, `gradle --no-daemon clean test jar writeFixtureClasspath smokeTestBundle` passed: 60 Java tests, zero failures, errors or skips; package and bundle smoke tasks passed. `python -m unittest discover -s tests -v` passed all 15 tests. During test development two compile attempts exited 1 (Java 17 `ExecutorService` is not `AutoCloseable`; then checked exceptions from the submitted task were not declared). Both were corrected; the full final-source suite passed.

No new exact-source GitHub CI result is available until the pushed head runs. The real intermittent WELCOME cause remains OPEN. No production correction is proposed from this evidence. Request independent GPT-6 review of the exact test-only source and approval before further scope expansion.
