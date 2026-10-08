# Roadmap and next milestone

1. Complete native Windows Bookmap UI/DPI and matched bridge-off/on heavy-load acceptance (issue #7). Verify all network inputs/Apply at 100/125/150%, receiver integrity, finalized archive and phase-separated latency. Do not infer slowdown from mixed callback histograms.
2. Fix exceptional lifecycle cleanup with failure-injection tests (issue #8). Prove writer/summary failures do not leave bound sockets or conceal the original error.
3. Verify callback threading and diagnostic/protocol correctness (issue #9). Confirm seq/book ownership, coherent snapshots, accurate counters and full JSON/command rejection.
4. Establish retained heap/GC/overload/storage budgets (issue #10), and network isolation/per-alias port rules (issue #11). Keep defaults stable until evidence justifies changes.
5. Complete initial-book/repeated-BMF provenance and recovery acceptance (issue #12); then review private offline candidates separately without public strategy disclosure.

Next v0.5 milestone: current-head Windows/Ubuntu build, validators and relevant integration all green; native UI inspected; sustained-load/slowdown target agreed and measured; exceptional shutdown and integrity/security blockers triaged or fixed; no raw data/secrets; reproducible artifacts and version approval by owner. No release tag or merge is implied by this checkpoint. Durable spool/snapshot recovery, authenticated transport and larger research optimizations are proposals requiring their own scoped tasks.
