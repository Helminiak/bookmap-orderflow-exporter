# Current state

Repository: Helminiak/bookmap-orderflow-exporter (PUBLIC)
Branch: feature/linux-live-bridge
Objective: optional v0.5 acknowledged Linux bridge in the existing addon.
Implementation commit: f7993f68025c442f526848950d45c527685c044d.
Independent failure/summary tests: 3d83403a946ebf7c4c6bfa0a4d09851b3389d55f.

Local Java build, journal/wire equality, failure tests and Python validator checks pass. Private receiver loopback/stress and known-capture checks pass within documented scope. See docs/live-bridge-v0.5.md and docs/validation/2026-10-08-v0.5-loopback.md.

Do not merge automatically. Await successful PR-head Windows/Ubuntu CI and physical Windows Bookmap → Ubuntu smoke/slowdown validation. Keep raw market data local and proprietary downstream logic private. v0.4 approval was completed before this explicit v0.5 request; the earlier feature hold is superseded.
