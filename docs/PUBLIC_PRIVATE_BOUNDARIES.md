# Public/private boundary

PUBLIC: Bookmap API listeners, raw canonical schema, generic MBO validation, compression/buffering, generic bridge protocol/health, synthetic tests/benchmarks, build tooling, sanitized metadata and engineering documentation.

PRIVATE: entry-quality criteria/features/labels/thresholds, private research datasets, decision/scoring/inference/execution logic, provider credentials and licensed BMF/raw capture archives. PRIVATE orderflow-entry-engine is a separate repository. Generic integration contracts may be documented here; do not copy private research source or outcomes.

The integration boundary is canonical events plus explicit alias/pips/session/provenance and integrity state. Downstream consumers must treat INVALID/DISCONNECTED/STOPPED/incomplete history as unsuitable for a current healthy input. Data availability/licensing is not granted by this repository. Hashes and aggregate counts are evidence, not redistribution of market history.

The existing governance PR #1 remains independent and unmerged. This closeout adds its own task-authorized AGENTS/handoff documentation without merging governance work. .gitignore is defense in depth, not a substitute for reviewing every staged file, generated artifact and issue body. Samples must be synthetic, credential-free and clearly identified. A smaller file is not automatically non-confidential.
