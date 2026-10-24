# DR100 bounded bridge startup restoration — 2026-10-24

Historical source `03319fda` used bounded `offerHistorical` at startup and
`finishHistorical` at the phase transition. Commit `2e4e619` removed those call sites;
`8a06c39` inherits that behavior. Protocol `orderflow-live-v0.1`, default bind/ports,
HELLO/WELCOME/START framing and the existing pacing implementation are unchanged.
The historical 2,782,584-ACK/60-second result covered startup HISTORY on a live
market connection, not full file Replay or durable receiver delivery.

The installed `8a06c39` native incident overflowed the 100,000-event bridge before
publishing an event despite starting the receiver first. Its local 1,493,177-record
archive independently passed validation. Local archive success is separate from
bridge delivery. See engineering-control Issue #18 comment 6380465527.

This correction reuses existing bounded ACK pacing during HISTORY and explicit
Bookmap file Replay, including after REALTIME_START; drains history at transition;
and drains replay/history through reserved STOP before close. Provider/unknown
REALTIME callbacks remain non-waiting. No buffer, protocol or integrity-check change.
Lifecycle serialization, terminal reserve, summary reconciliation and UI fixes remain.

`ReplayBridgeStartupTest` invokes actual exporter callbacks with a delayed receiver,
four-event retention and paced ACKs across both phases. It fails on the old source
and passes with restoration: 403 ordered events including STOP, ACK reconciliation,
complete delivery, finalized archive, zero journal/bridge overflows and gzip EOF.
Shutdown controls now distinguish intentionally waiting HISTORY from responsive
provider LIVE. Missing receiver uses the existing ten-second bound and invalidates
the bridge while preserving local acquisition; no unbounded wait is introduced.

Remaining acceptance: exact candidate artifact provenance, native Windows Replay
HEALTHY/event flow, independent captured-data validation and clean shutdown. No
production release, merge, live order routing or durable receiver-storage claim.
