# Native shutdown integrity failure: release acceptance blocked

Source under test: `03319fdaae8792eecf681af7fc5805e708ea30c4`, software 0.5.0 / UI revision v0.5a. Installed JAR SHA256: `8de9756f17d451dfc02106f95f6346bf9ee418e30d0c46f9ece72d2d877ec362`. The owner authorized production reliability development after these checks. No release approval or merge follows from earlier green CI.

Actual Windows Bookmap inspection confirmed Configuration, Live status and Information render, both bridge ports and capacity are visible without overlap, and navigation works. Native scaling percentage was not established; 125%/150% acceptance remains open. The Information preview-title dash has an encoding artifact to correct separately.

The market connection was live. HISTORY identifies callbacks supplied during addon startup catch-up; it does not establish that the platform is replaying a file. A 60-second Windows startup bridge smoke acknowledged 2,782,584 new events without observed receiver sequence gaps, duplicates or reversals. This is not a REALTIME interval or full-session certification.

One finalized capture independently passed supported Python 3.12 acquisition validation: 10,049,622 records and 10,674 final orders, including schema, IDs, price/tick relationship, deterministic MBO state, sequence/time/lifecycle, gzip CRC/ISIZE and summary checks. Its compressed SHA256 is `626e1bfaf1a0432692620bbed3497630e35e4ad727ff9b8763343dd5b0a97f4e`. Original licensed inputs remain private.

A subsequent stopped capture failed: 1,898,020 physical records, with final STOP sequence 1,898,021 following sequence 1,898,019. Sequence 1,898,020 is missing. A separate EOF scan passed gzip CRC/ISIZE. The summary's healthy writer and count 1,898,021 do not certify logical integrity. Shutdown cause remains to be reproduced with a deterministic regression, not assumed from this observation.

The subsequent receiver was HEALTHY before stopping, with 1,798,275 accepted records and no gaps/duplicates/reversals; it was still catching up. Final publisher summary reported INVALID/outbound-buffer-overflow with retained backlog. ACK means receiver in-memory validation, not durable receiver persistence or complete delivery. Archive, bridge and receiver acceptance must remain distinct. Both diagnostic receiver and exporter were stopped; Bookmap and its market connection stayed running.

Default Python 3.14 produced an unexpected enumerate/callability TypeError in validation; the unchanged validator succeeded under supported Python 3.12 for the earlier capture and correctly rejected the subsequent sequence gap. The 3.14 discrepancy is unresolved. Roughly 0.84-second GUI control round trips included transport, a fixed post-input delay and screenshot creation, so they are not callback stop timings.

Next: deterministic stop/producer-boundary reproduction, terminal ordering and summary reconciliation, then independent bridge backlog/shutdown tests. Do not restart the owner's active Bookmap or change broker/execution state for convenience. Product acceptance additionally requires matched workloads, native scaling, REALTIME transition, soak, recovery, compatibility and independent review. See issues #8–#10 and the master handoff.
