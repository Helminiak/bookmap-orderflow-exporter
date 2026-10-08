> Current correction: initial tab candidate fb77a37 was rejected in Bookmap. See [host/shutdown repair](BOOKMAP_HOST_REPAIR_2026-10-08.md) for reproduced cause, 18 Java tests and corrected candidate; native acceptance remains pending. Earlier implementation/acceptance statements below are historical.

> Latest authorized UI: one Orderflow exporter host with Configuration/Live status tabs; Configuration selected first, scrollable contents and fixed action buttons. Earlier two-panel/rollback descriptions below are historical anchors. See [UI follow-up](UI_TAB_FOLLOWUP_2026-10-08.md) for current build, 15 Java tests and native morning acceptance still pending.

# Testing and troubleshooting

Tools: JDK 17 (source/target 17), Gradle 8.10 (no wrapper checked in), Python 3.12 standard library for public validator/tests/benchmark. Bookmap API core/simplified 7.6.0.20 pinned in gradle.properties. Runtime Java 17+; owner's Bookmap Java 25 file was identified, but Java 25 is not the full CI matrix. JeroMQ 0.6.0 bundled; API classes excluded. Maven dependencies require network on first build. Linux private receiver uses Python/pyzmq and its own instructions.

```sh
gradle --no-daemon clean test jar writeFixtureClasspath smokeTestBundle
python -m unittest discover -s tests -v
python -m py_compile tools/validate_export.py tools/benchmark_exporter.py
python tools/validate_export.py CAPTURE.ndjson.gz --summary CAPTURE.summary.json
```

Output JAR: build/libs/bookmap-orderflow-exporter-v0.5.jar. ZIP: build/distributions/orderflow-v0.5-windows-smoke-test.zip. Install one addon only; close Bookmap before replacing JAR. Restart the private receiver before beginning a new publisher session. Settings use Bookmap's API save/reload and start a new archive. Config fields retain original fonts/sizes/values; only network row height adapts. Status panel is unchanged. Use `Smoke-Test.bat`, with its PS1 and JAR together, rather than PS1 double-click.

Current Java suite: 13 tests covering canonical journal/wire equivalence, independent overflow failures, health isolation/timeouts, same-port reload, same-owner retained delivery, historical ACK pacing, and localized UI geometry. Public Python suite: 15 tests covering schema/keys/nonfinite/seq/lifecycle/time/book/gzip/summary/v0.5 drops. Windows CI executes synthetic PASS/FAIL/INCONCLUSIVE, ACK stall and Java/default-path launcher checks. Six private Java/Python integration tests are optional and require authorized private checkout; they are not bundled publicly.

UI geometry scales fonts 1/1.25/1.5 at widths 400/560/800 and checks preferred control sizes/bounds/gaps/scrolling. Windows CI uses native Swing LAF; Linux uses Metal. Linux subsection images were rendered/inspected. Actual Windows Bookmap display-DPI rendering is not verified; issue #7 provides acceptance. Do not describe font scaling as an actual desktop-DPI screenshot.

| Symptom | Action |
|---|---|
| INVALID/outbound overflow | Stop inference; start fresh receiver then exporter START. Investigate capacity/load; don't simply clear INVALID or treat prefix as complete. |
| Historical timeout | Have receiver running before enable/apply. Inspect health/firewall/ACK processing; restart for a fresh session. |
| Cannot bind | One instance per port pair; use distinct pairs per alias, close old Bookmap, inspect failed shutdown risk. |
| Java prompt | Automatic search checks Bookmap/common installs; explicit -JavaPath to Java 17+ if missing. No credentials/admin action needed. |
| PS1 flashes | Run BAT, which retains window/output. |
| No health reply | Check bridge enabled, bind/interface, configured ports and private-LAN firewall; discovery cannot learn a disabled listener. |
| INCONCLUSIVE | No meaningful ACK progress; repeat with events flowing. |
| CRC/missing STOP/summary mismatch | Preserve original, require full EOF validation; do not approve an incomplete repaired prefix. |
| Unreadable fields at DPI | Measure/render actual Bookmap, record DPI/window/LAF/JAR hash and issue #7. |

No SpotBugs/Checkstyle/static analyzer was configured at audit; javac, Python syntax, diff whitespace and publication/link audits are the available checks. JDK deprecated API/Gradle warnings exist and are maintenance work, not hidden test failures. Historical failed CI: 37718803627 wrong v0.1 JAR path after v0.2; fixed be7485e. 37736159209 passed launcher assertions but retained expected child exit 1; fixed 7135b59. Private 37741134947 exposed old connection teardown race; fixed 646eeea. Relevant current checks pass; reproduce old failures only by checking the affected commit in an isolated checkout.
