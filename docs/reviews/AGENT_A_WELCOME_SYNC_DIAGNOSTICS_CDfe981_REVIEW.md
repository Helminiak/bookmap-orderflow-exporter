# Synchronized WELCOME delivery diagnostics — REVIEW PENDING

Exact test-source commit: `cdfe9817b5a3cfba8ed8a563966ba32bf36852ff` (`test: diagnose synchronized WELCOME handshake stages`), based on owner-authorized baseline `6f649b7c0ce8490957fbee6f66d796293e85c169` on PR19. The baseline's independent review is [comment 6094533727](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/19#issuecomment-6094533727); its reproduced Ubuntu CI run is [38029783011](https://github.com/Helminiak/bookmap-orderflow-exporter/actions/runs/38029783011).

## Baseline failure

At baseline `6f649b7`, Windows CI passed and Ubuntu CI failed in `SyntheticBridgePeerTest.concurrentPublisherStartupAndFirstHelloRemainObservable()` at four simultaneous publishers. Worker `stress-4-0` accepted the local HELLO send at about 73 ms, timed out waiting for WELCOME at about 2,075 ms, and reported publisher `WAITING_RECEIVER` / receiver `none`, queue depth 1, no events published, and `invalid=false`. The exact failure and log are preserved in the linked independent review. This is a positive synchronized reproduction of the missing-WELCOME symptom; it does not by itself prove an exporter production defect.

## Bounded test-only diagnostics

Only `src/test/java/com/limacharlie/orderflow/SyntheticBridgePeerTest.java` changed in source commit `cdfe981`; production Java code, protocol behavior, receive deadlines, retry behavior, and assertions were not weakened. The synchronized matrix runs three independent rounds at 4, 8, and 16 publishers (up to 84 handshakes). Each level allocates distinct loopback ports within the test JVM, waits for publisher health readiness, queues the first START, constructs all peers, then releases HELLO sends through one barrier. Each WELCOME receive retains one two-second deadline; failed handshakes are not retried. Worker futures, barriers, and cleanup waits are bounded.

The diagnostic peer polls its DEALER and ZeroMQ monitor together. It records local HELLO-send acceptance, monitor events when observed by the test poller, DEALER reply receipt, and publisher `state` / `receiver` transitions sampled while waiting. `CONNECTED` with the worker identity establishes that `LiveBridge` processed HELLO and reached the state update immediately before its WELCOME send branch. It does not directly expose whether the internal ROUTER send call succeeded; adding that hook would require prohibited production-source instrumentation. The separate controlled ROUTER success test timestamps ROUTER HELLO receipt, WELCOME emission, and DEALER WELCOME receipt. Existing no-route and withheld-WELCOME controls remain intact.

Health readiness is observed through the existing test helper after `LiveBridge` has bound the data ROUTER and health REP endpoint. Ports are selected sequentially and unique within each matrix level; the OS releases the probe socket before the publisher binds, so an external process could still create a port-selection race. The test records health-ready and barrier-release monotonic timestamps in any worker failure.

## Validation and interpretation

On the final test source, `gradle --no-daemon clean test jar writeFixtureClasspath smokeTestBundle` passed under Gradle 8.10 and Temurin JDK 17.0.20.1+1: 60 Java tests, zero failures, errors, or skips; package and smoke tasks passed. The focused matrix passed during the diagnostic cycle, and the final full-suite run also passed. `python3 -m unittest discover -s tests -v` passed all 15 tests under Python 3.14.4. The specified local JDK 17.0.20.1+8 path was absent; no toolchain was installed or system configuration changed.

The local matrix completed all 84 handshakes without reproducing the failure. This does not negate the exact Ubuntu CI failure at `6f649b7`; CI resource/scheduler behavior may differ, and monitoring adds some test-side observation overhead. The production root cause remains unproved. No runtime correction is authorized or proposed. Exact-head Windows/Ubuntu CI for the new source and independent GPT-6 review remain pending. Stop at that review gate; no Bookmap install/restart, live capture, merge, deployment, or release was performed.
