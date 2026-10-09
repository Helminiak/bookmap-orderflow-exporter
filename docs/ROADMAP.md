# Roadmap and next milestone

1. Complete native Windows Bookmap UI/DPI and matched bridge-off/on heavy-load acceptance (issue #7). Verify all network inputs/Apply at 100/125/150%, receiver integrity, finalized archive and phase-separated latency. Do not infer slowdown from mixed callback histograms.
2. Fix exceptional lifecycle cleanup with failure-injection tests (issue #8). Prove writer/summary failures do not leave bound sockets or conceal the original error.
3. Verify callback threading and diagnostic/protocol correctness (issue #9). Confirm seq/book ownership, coherent snapshots, accurate counters and full JSON/command rejection.
4. Establish retained heap/GC/overload/storage budgets (issue #10), and network isolation/per-alias port rules (issue #11). Keep defaults stable until evidence justifies changes.
5. Complete initial-book/repeated-BMF provenance and recovery acceptance (issue #12); then review private offline candidates separately without public strategy disclosure.

Next v0.5 milestone: current-head Windows/Ubuntu build, validators and relevant integration all green; native UI inspected; sustained-load/slowdown target agreed and measured; exceptional shutdown and integrity/security blockers triaged or fixed; no raw data/secrets; reproducible artifacts and version approval by owner. No release tag or merge is implied by this checkpoint. The user has now authorized production reliability work including durable recovery. Keep it scoped to acquisition/transport; strategy research remains private.

## Production acceptance register — current milestone

| Gate | Evidence / state |
| --- | --- |
| Shutdown sequence boundary | Controlled RED on old source / GREEN candidate; late state mutation and interrupted drain tested |
| Archive independent of offline receiver | Synthetic nonwaiting HISTORY/REALTIME test passes |
| Terminal transport | One reserved STOP slot; complete ACK and explicit unconfirmed range tested |
| Cross-language integrity/reconnect | Six Java/Python tests pass; current CI head must match final candidate |
| Reproducible packaging | Two clean builds match; source identity/dirty flag in manifest; Windows/Ubuntu repeat-build CI added |
| Native BMF catch-up → REALTIME / sustained LIVE / full-session soak | Pending safe replay/idle Bookmap window; active workflow must not be interrupted |
| Callback/throughput/resource budgets | Synthetic diagnostic only; characterize native peak, p99 <100us, 2x sustained / 4x burst target; ordinary customer hardware pending |
| Durable resume / outage retention | RAM-only ACK and finite capacity explicit; disk-backed replay/resume design/review/implementation pending |
| Slow/full disk / process kill / rollover / multiple aliases / absent MBO | Some synthetic errors covered; complete fault matrix pending |
| Native DPI / installation / upgrades / rollback | Prior baseline inspected; final exact build 100/125/150% and fresh-machine matrix pending |
| Independent review | Fresh Codex static re-review of 2e4e619 found no new high/critical flaw; ChatGPT and final-artifact sign-off pending |
| Marketplace | Official onboarding/API/license references checked; questions prepared, no contact/agreement/submission |

Next engineering block: design a bounded disk-backed transport spool with session/ACK checkpoint recovery, keeping its I/O entirely in workers and retaining explicit exhaustion/range diagnostics. Review exact contract and failure matrix before changing protocol. Native acceptance requires a safe testing window; it does not block synthetic implementation and documentation.
