> Latest authorized UI: one Orderflow exporter host with Configuration/Live status tabs; Configuration selected first, scrollable contents and fixed action buttons. Earlier two-panel/rollback descriptions below are historical anchors. See [UI follow-up](UI_TAB_FOLLOWUP_2026-10-08.md) for current build, 15 Java tests and native morning acceptance still pending.

> Current state: original two Bookmap panels with localized measured network-row height (d447a69); older tab/enlargement/rollback descriptions below are historical. Use docs/LINUX_BRIDGE.md and HANDOFF_MASTER.md for current recovery/acceptance. A supplied 4,656-event/60s smoke report has now been reviewed; actual Bookmap Windows DPI and sustained slowdown acceptance remain pending.

# Optional live bridge v0.5

One existing Bookmap addon receives each callback once. It copies seq, market_ns, phase, alias, pips and event-specific fields into an immutable `CanonicalEvent`. The journal and publisher independently consume that event. JSON serialization is lazy, on worker threads; a cached canonical string avoids repeat work in the normal case. Concurrent workers may serialize twice, without waiting on each other. No inference or feature engineering is present.

## Transport and contract

JeroMQ 0.6.0 is bundled in the same v0.5 JAR, including its Java-only jnacl dependency. Bookmap API 7.6.0.20 is compile-only. No native ZeroMQ installation is needed on Windows.

Market port **5555/TCP**: Java ROUTER → one Python DEALER. Health port **5556/TCP**: Java REP ← Python REQ. Ports/bind address are configurable per instrument; simultaneous instruments need distinct port pairs. This version has no fanout to multiple independent receivers.

The [ZeroMQ guide's discussion of PUB/SUB failure modes](https://zguide.zeromq.org/docs/chapter5/) explains why PUB alone cannot establish subscriber continuity. This bridge uses an application handshake and cumulative acknowledgements. This is bounded live delivery, not durable messaging.

The DEALER sends one UTF-8 frame: `HELLO <receiver-id> <last-validated-seq>` or `ACK <receiver-id> <last-validated-seq>`. ROUTER prepends routing identity. The first receiver must HELLO at seq 0. The initial WELCOME contains `{type: WELCOME, health: {...}}`, including exporter-session UUID, alias and pips. Receiver IDs persist only for that running receiver process.

Each EVENT envelope contains `type`, `protocol: orderflow-live-v0.1`, `session`, `sent_epoch_ns`, `replay`, and `event`. **The nested event is exactly the journal's `bookmap-orderflow-v0.1` JSON object**, including control lifecycle, nullable IDs, execution markers, anomaly fields and prices. No separate historical/live market contract. `market_ns` is market data, never a transport delay. `sent_epoch_ns` is millisecond-resolution sender wall time; reported lag is approximate and depends on synchronized Windows/Linux clocks.

The receiver ACKs only validated events. Maximum in-flight window: 256 events. Socket send/receive high-water marks: 512 messages. All socket creation, reads, sends and closure happen on the publisher worker. Sends use DONTWAIT. A send failure pauses delivery and retains unacknowledged events for the same receiver to resume with HELLO. Overflow, an invalid ACK or a different receiver identity still invalidates continuity.

## Bounds, failure and recovery

Defaults: bridge disabled; bind `0.0.0.0`; outbound capacity **100,000 unacknowledged events**, including queued + in-flight; journal capacity **1,000,000 events**; compressed disk buffer 4 MiB; checkpoint age 60 seconds. Capacity is an event bound, not an exact byte bound; IDs/payload size and the deterministic open-order map also consume memory.

Callback offers use a CAS capacity reservation and a concurrent queue, without queue locks, waiting, sockets, disk or compression. The JVM/OS can still pause threads (allocation, GC, scheduling); this is not a hard-real-time guarantee.

- No receiver: retain START and subsequent events until the buffer fills. Linux should start before enabling/restarting the exporter.
- Same receiver connection interruption: unacknowledged events stay bounded; HELLO resumes from validated seq and replays retained in-flight events. Exact retained duplicates marked `replay` are counted as retransmits, not new market events. Both a short reconnect and a closed peer during a send are tested; retained events must replay and be ACKed before they are released.
- No ACK/heartbeat for two seconds: DISCONNECTED. The receiver sends ACK heartbeat every 200 ms and queries health about every 500 ms.
- Queue overflow: INVALID, overflow increments, retained unacknowledged events are discarded/countable, later offers count as dropped. Bookmap continues; journal remains independent. The receiver's health query disables its healthy state even if no later seq arrives to reveal a gap.
- New receiver process or publisher restart: INVALID. There is no book-snapshot/resume-from-disk protocol. Restart Linux, then restart/apply the exporter to begin a fresh START and book population. No automatic false HEALTHY reset.
- Bridge bind/config/socket failure: UI reports FAILED/INVALID; journal continues.
- LIVE journal overflow/writer failure: archive INVALID, writer_ok=false, dropped/overflow counters and count mismatch; LIVE callbacks continue, bridge remains independent. Do not use that archive as complete data.
- HISTORY journal: strict backpressure for complete extraction. Turning off “Responsive LIVE journal” deliberately enables historical-style blocking in LIVE and is unsuitable for the responsiveness acceptance run.
- Stop/unload drains the journal on the lifecycle thread, then gives the bridge one second of ACK grace on the stop/unload lifecycle thread and releases its sockets before settings reload (bounded worker join). Market callbacks never run this teardown. Hard shutdown/crash is not lossless. A STOPPED receiver has validated STOP; publisher snapshots in the journal summary may precede the final bridge ACK/grace outcome.

Inference consumers must require HEALTHY and a complete START-derived state; DISCONNECTED, INVALID, WAITING_START and STOPPED are not an active healthy inference feed. MBO anomalies, seq duplicates/gaps, missing replace/cancel references and TIME_REVERSAL all fail closed. Trade callbacks preserve IDs/flags but do not mutate orders: MBO callbacks alone own book state.

## Instrumentation and tests

UI refresh is throttled to 250 ms. Callback histogram uses atomic counters and log2 nanosecond buckets: p50/p95/p99 are **upper bounds**, not exact sampled quantiles. Count/rate includes timestamp and realtime callbacks as well as market events; unique-event and retransmission counters are separate on the bridge. Rates are run averages; peak callback rate uses completed one-second windows. The measured callback duration excludes the histogram bookkeeping itself.

`gradle clean test jar` builds/tests on Windows and Ubuntu. JUnit compares 100 ADD + 20 REPLACE + 30 TRADE + 40 CANCEL + 3 controls across the actual exporter callbacks, finalized gzip journal and wire, field for field. Public Python regression tests cover strict gzip/schema/summary validation. The private receiver tests add exact Java/Python semantic equality, gaps, duplicates, same-process reconnect, restart and tiny-buffer overflow.

Synthetic standalone publisher (not another Bookmap plugin):

```text
java -cp build/libs/bookmap-orderflow-exporter-v0.5.jar com.limacharlie.orderflow.BridgeFixture MARKET_PORT HEALTH_PORT CAPACITY CYCLES RATE MODE REPORT_JSON SYNTHETIC_JOURNAL
```

MODE is healthy/gap/duplicate/overflow. The fixture generates public synthetic events only. Its callback timing measures event construction/offer, while its JSON was prepared outside that timed section. The separate test-class ExporterCallbackFixture invokes the real addon callbacks; `gradle writeFixtureClasspath` supplies its test-only Bookmap API classpath. See local evidence for both measurements. Neither fixture runs the Bookmap application.

## Windows → Ubuntu smoke test

1. Download the Windows CI artifact `bookmap-orderflow-exporter-v0.5-windows-latest` from this PR's successful Plugin Build Test run, or build with JDK 17/Gradle 8.10 (`gradle clean test jar`). Extract `bookmap-orderflow-exporter-v0.5.jar`.
2. Disable/unload the previous exporter in Bookmap and exit Bookmap. Replace its prior JAR with v0.5; keep one addon installed. Restart Bookmap, load the new JAR in the addons manager, and enable **Orderflow Raw Exporter v0.5** for ES.
3. Identify the Windows private LAN IPv4 using `ipconfig`. Set bridge bind to that interface or `0.0.0.0`, market port 5555, health port 5556. Keep responsive LIVE journaling on and both MBO/trade channels on. Leave bridge disabled until Ubuntu is listening.
4. Permit TCP 5555/5556 from the Ubuntu machine on the Private firewall profile. Administrator PowerShell (replace the Ubuntu IP):

```powershell
New-NetFirewallRule -DisplayName "Orderflow v0.5 LAN" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5555,5556 -Profile Private -RemoteAddress 192.168.1.60
```

5. Follow the private receiver installation instructions on Ubuntu and start it against the Windows IPv4 first. This version is a trusted-LAN, unauthenticated/unencrypted connection; do not expose these ports to the Internet.
6. In Bookmap settings, enable Live Linux bridge and Apply/restart the exporter. Confirm CONNECTED, increasing published/ACK seq, zero overflow and small queue depth. Linux should show HEALTHY after START/history catch-up, zero gaps/duplicates, increasing trade counts and plausible open orders.
7. Run the same short replay with bridge disabled/enabled and compare Bookmap elapsed replay time, CPU and UI responsiveness. Record p95/p99/max callback duration, queue high water, source/receiver rates and journal validity. Repeat at accelerated replay rates. Preferred slowdown <3%; 3–5% only if responsive; >5% requires optimization, >10% is unacceptable. No such application slowdown result has been measured here.
8. Stop cleanly and validate the new journal plus summary with `python tools/validate_export.py CAPTURE.ndjson.gz --summary CAPTURE.summary.json`. Preserve real captures locally. If INVALID, stop downstream use and start a fresh exporter/receiver session; increasing capacity alone cannot repair a lost book history.

## Configuration panel and Windows smoke launcher

The follow-up layout puts each labeled setting on its own row, fits the available width, and adds vertical scrolling. Bind, market port, health port and bridge queue remain reachable in a 240-pixel-wide test panel. Apply/restart is outside the scrolling area.

Download/extract the CI artifact `orderflow-v0.5-smoke-test-windows-latest`. Its ZIP contains the updated exporter JAR, `Smoke-Test.bat`, `Smoke-Test.ps1` and README. Replace the old JAR, start Ubuntu's existing receiver, enable/apply the Bookmap bridge, then double-click the batch file on Windows. Java 17+ is required; Windows Python/admin access is not.

The launcher queries the existing health channel only; it never registers a second market receiver, changes Bookmap settings or opens firewall rules. It observes connection/ACK progression, journal health, overflow counters, session stability, backwards counters and stalled ACKs with pending events. It prints PASS/FAIL/INCONCLUSIVE and writes a timestamped diagnostic JSON report without market event payloads. An idle market returns INCONCLUSIVE, not PASS. Confirm Ubuntu's terminal says HEALTHY with zero gaps/duplicates separately; health alone does not identify the physical peer host or verify finalized archive integrity/Bookmap slowdown.

Defaults: automatically find the listener on this Windows machine’s private LAN addresses or localhost, health port 5556, 60 seconds. The BAT prints Windows LAN addresses. If binding to a particular Windows interface, use `Smoke-Test.bat -HostName WINDOWS_LAN_IP`; for custom ports use `-HealthPort PORT`. `-JavaPath "C:\path\to\java.exe"` selects a runtime explicitly. Scripts are tested on Windows CI against valid, invalid, idle, missing-response and stalled-ACK fixtures; Java health query tests use the real publisher and check it does not register a receiver.


### Single hosted panel and bind/reload follow-up

Configuration and Status now live in one Bookmap StrategyPanel, with explicit internal tabs and Configuration selected first. Both views scroll. This avoids relying on the host to display two returned panels. Stop/reload now finishes bounded ACK grace and releases old sockets before the new instance binds; the previous asynchronous five-second shutdown could leave the old port occupied during Apply. Same-port restart is regression-tested.

The Windows BAT prints local private LAN addresses and auto-detects the existing health listener on those addresses or localhost. Linux’s private `tools/linux/Start-Receiver.sh` prints its own default-route LAN IP and probes only the directly attached private subnet (at most /23) for the Bookmap health protocol. Neither probe registers a market receiver. If Bookmap is disabled/unreachable, Linux asks for the Windows IP printed by the BAT; an IP cannot be discovered from a listener that does not answer. Firewall and receiver registration remain explicit setup steps.


### Historical catch-up and empty receiver recovery

Historical callbacks may wait for bridge ACK capacity for up to ten seconds per stalled wait. The historical backlog drains before REALTIME_START, then LIVE callbacks remain non-waiting and overflow still invalidates continuity. Start the Linux receiver before Apply/restart; do not increase capacity to conceal lost events. A missing or stalled historical receiver produces an explicit timeout and requires a fresh START. Waiting affects historical catch-up speed and the REALTIME transition, not the steady-state LIVE enqueue path.

A receiver that has accepted zero events may re-arm for a different healthy publisher session after connecting to an already-invalid session. Once even START has been accepted, publisher-session changes continue to fail closed. Restart that receiver before applying the exporter.

The Windows helper resolves default JAR/report paths after parameter binding. Run the BAT, not the PS1 by double-click; the BAT keeps results visible.


The exporter tabs now request a 640 × 820 panel (600 × 740 configuration viewport) to restore a larger settings view; Bookmap controls the actual available window size. Width tracking, scrolling and the fixed Apply button remain. The BAT checks Bookmap's bundled Java first, then JAVA_HOME, PATH and common vendor folders. Automatic candidates must report Java 17+; a manually supplied -JavaPath remains available.

On 2026-10-08 the owner reported the Linux receiver displayed HEALTHY and the Windows BAT displayed CONNECTED/PASS with 57,771 new events. This establishes a user-reported physical LAN smoke-test success. The saved smoke report has not been reviewed here, and application slowdown/load acceptance is still separate.


### Configuration layout rollback

At the owner's request, the settings UI is restored exactly to the original v0.5 layout from 9318064: separate Exporter configuration and Live exporter status StrategyPanels, original field rows and Apply button. The custom tab container, preferred-size overrides and scroll helpers are removed. Earlier UI layout descriptions above are superseded. Bridge reliability fixes, historical backpressure and automatic Java discovery remain unchanged. Tests specific to the removed tab/scroll layout are removed; transport/exporter regressions still apply.
