# Performance and memory evidence

Priority: Bookmap responsiveness in responsive LIVE. This is an engineering checkpoint, not a maximum-capacity certificate. Synthetic results do not measure application slowdown.

## Fresh closeout benchmark

[Sanitized result](validation/2026-10-08-closeout-journal-benchmark.json): Linux x86-64, Intel Core i9-13900K, 32 logical CPUs, 66,018,590,720 bytes system RAM; Temurin JDK 17.0.20.1+1, Gradle 8.10, Python 3.12.14. Background desktop workloads were not isolated. Code baseline d447a69. Bridge disabled; actual exporter callbacks invoked without Bookmap, 1,000 synthetic cycles, target 100k market events/s. The fixture also calls TimeListener per event and retains 60k open synthetic orders.

190,003 records validated including CRC/schema/summary; 60,000 final orders; zero gaps/unknown references/drops/overflows. Compressed bytes 2,783,282; two application file-write operations; writer high-water 3,847. Java child peak RSS 498,925,568 bytes (~475.8 MiB); this includes JVM heap/native overhead, open order map, code and buffers and is NOT bytes per queued event. Wall 3.001235745s, 63,308.26 records/process-wall-second; startup, shutdown and fixture's one-second post-stop sleep are included. Callback mean 206 ns, p95 upper bound 1,024 ns, p99 2,048 ns, max 2.174ms; timing excludes histogram bookkeeping. One trial, not a sustained limit.

Reproduce from a fresh Python process (standard library only):

```sh
gradle --no-daemon test jar writeFixtureClasspath
python tools/benchmark_exporter.py --classpath-file build/fixture-classpath.txt --cycles 1000 --rate 100000 --output benchmark-result.json
```

No raw input is required. Temporary synthetic archives are deleted after validation. Peak RSS uses Linux RUSAGE_CHILDREN for the single Java child; other platforms return null. Output metadata may vary by runtime and scheduling.

## Prior bridge evidence

[Earlier measured loopback](validation/2026-10-08-v0.5-loopback.md): 190,003 records delivered at 5k/50k/100k targets, 250k target hit capacity 100k and INVALID after a validated prefix (not a success). Publisher RSS 349–426 MiB included retained fixture strings; receiver RSS 38.8–42.1 MiB. Do not combine independent rate intervals as one capacity measurement. Current six private Java/Python integration tests pass against the fresh public JAR; private implementation remains unmerged.

[Previously inspected physical smoke aggregate](validation/2026-10-08-closeout-smoke.json): 60s, 118 samples, 4,656 new published/ACKed events, stable receiver/session, no query/send failures, no overflow/drop/discard, sampled pending count ≤2, final seq 1,553,902 caught up. The original SMB report path became unavailable during closeout; aggregates are retained from the earlier inspection, with no fresh hash or revalidation claimed. Historical high-water 100k is compatible with pacing. Max callback 1.5451699s mixes phases; it cannot certify LIVE responsiveness. The separate 57,771-event PASS was owner-reported, not this file's count.

## Capacity model and review

Queues reserve capacity with atomic CAS and ConcurrentLinkedQueue nodes; non-blocking is not wait-free or immune to GC/scheduling. Immutable suppliers and captured IDs allocate per event; JSON may be cached by both consumers and computed twice during a race. Each queue contributes nodes but shares event objects. Book map growth is independent of queue capacity.

Estimated sensitivity ONLY, unmeasured: 400–1,000 retained bytes/event for events/IDs/suppliers/nodes/cached strings gives ~381–954 MiB for 1m events and ~38–95 MiB for 100k. Do not add both as if every object were duplicated. The writer's multiple arrays are roughly 16 MiB before encoding/deflater overhead. Measure with heap/JFR/allocation tools before setting a hard budget (issue #10). Large or non-Latin IDs may increase retention.

LIVE callback work includes map mutation, immutable copies, CAS and atomic histogram operations. No socket/GZIP/file waits in responsive LIVE. HISTORY writer waiting is potentially unbounded; historical bridge capacity/drain can wait 10s; stop is bounded lifecycle waiting. Configuring responsiveLiveJournal=false explicitly permits LIVE blocking. JSON/GZIP and OS storage service rate, GC, retained book size, ID cardinality, producer burst shape and downstream ACK throughput are independent bottlenecks.

No phase-separated heavy ES, bridge-off/on slowdown, disk exhaustion, retained heap saturation or broad callback concurrency experiment is certified. See issues #7/#9/#10 and [risk register](KNOWN_ISSUES_AND_RISKS.md).
