# Survival acceptance identity and LLM wait policy

These are reviewable snapshots of the external round3 harness, rooted at
`/workspace/blockagent-survival-abc-round3`. Round2 reference copies use their own root.
Do not launch these scripts against an existing Dig. Editing files does not hot-patch
an already-running monitor or supervisor. The next invocation uses the new policy.
The retry entry guard also rejects legacy `invalid_external_resubmission` outcomes
before any process, world, or attempt mutation, even from an old supervisor.

`hang-watch.py` retains `hold_same_command_no_cancel`: no cancel or command submission.
An unproven identity transition is quarantined, even when instruction text matches.
It does not count as FAIL or authorize an automatic retry. We intentionally do not
adopt a new command ID without authenticated continuation evidence.

New monitor outcome / final STATUS fields:

- `state: retryable_identity_change`
- `acceptance: quarantined`
- `counts_toward_three: false`
- `retryable: true`
- `automatic_retry_allowed: false`
- `cancel_sent: false`
- `expectedCommandId`, `observedCommandId`, and original `elapsed_sec`

The supervisor writes RESULT as QUARANTINED, not FAIL, and stops scheduling retries.
No acceptance clock reset or automatic extra attempt is allowed. Archived historical
results remain evidence; this patch does not retroactively turn attempt2 into a PASS.
Existing followup owns the next round. Runtime STATUS/RESULT of the current Dig are
not overwritten by installation of these files.

LLM decisions retain identical input on retries. Each request has a 45 second maximum;
the whole decision has a 120 second maximum (or a smaller configured retry budget).
Exhaustion surfaces an IOException while retaining the original command token.
Transport cancellation and shutdown run asynchronously so JDK transport cleanup
cannot hold the worker or shared cancellation scheduler. The observed long retry gap
motivates this protection; no thread dump proves the exact JDK blocking site.
Local fixtures cover IOException followed by stalled bodies, bounded retries,
identical-input recovery, and cancellation. Survival acceptance remains outstanding;
PR #5 must remain draft, with no merge or release.
