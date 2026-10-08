# Public Bookmap Exporter — ChatGPT GitHub Review Index

Status: **Governance proposal under draft PR #13**; no native review or release approval implied.

## Primary review entrypoints
- [Codex A GitHub operating directive](../../ops/agents/CODEX_A_OPERATING_DIRECTIVE.md)
- [Agent A checkpoint and receiver-warning protocol](../AGENT_A_GITHUB_CHECKPOINT_AND_REVIEW.md)
- [Existing exporter draft PR #6](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/6)
- [Known issues and risks](../KNOWN_ISSUES_AND_RISKS.md)

## Required next engineering review
Owner observed v0.5a bridge enabled without Ubuntu receiver: queue fills and bridge goes INVALID with insufficient proactive operator warning. UI direction is partially approved pending a user-actionable missing-receiver warning, distinct bridge/archive status and native testing. Codex A owns implementation and must publish source-SHA-bound evidence and a new review-JAR SHA256.

## Latest agent checkpoint
No new SHA-bound AGENT_A_*_REVIEW.md has been produced by this governance PR. The coder must publish the actual review index on its **implementation branch**, and update its convenience index there. Review is pending until independent ChatGPT examines exact code and evidence.

## How to request review
Ask ChatGPT: “Review the current Bookmap exporter PR and this GitHub review index through my connected GitHub account. Inspect the exact diffs, changed source, tests, native acceptance evidence, human-factors failure/recovery states and the public engineering protocol. Give engineering defects and suggestions with file/line/SHA and label what is untested. Do not treat test PASS as native or Marketplace acceptance.”

Do not post private ES entry-quality research, unlicensed captures or credentials here.
