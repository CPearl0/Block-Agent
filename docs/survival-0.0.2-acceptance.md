# Block-Agent survival Dig, round 2 — FAIL

All three authorized attempts ended with **0 diamonds**. PR #5 stays **draft**. No fourth attempt, command resubmission, `gh pr ready`, merge, squash or GitHub Release was performed. No Cursor CloudAgent or delegated agent was used.

| Attempt | Product | Monotonic elapsed | Result and observed progress |
| --- | --- | ---: | --- |
| 1 | `2a7f3db` | 2353.107 s | FAIL: replanning limit reached after repeated segments with no new evidence/progress. Wood → stone → raw iron → smelted iron → iron pickaxe verified. After obtaining the iron pickaxe at Y=53, descended to Y=22; 0 diamonds, no deaths. One rejected LLM output recovered on the second attempt. |
| 2 | `2a7f3db` | 800.442 s | FAIL: same replanning/no-progress limit. Wooden pickaxe verified at 120.347 s; blocked by snow/terrain access, never descended from Y=74. No stone pickaxe, 0 diamonds, no deaths. |
| 3 | `63cdcda` | 1623.452 s | FAIL: same replanning/no-progress limit. Explored from an icy mountain spawn to Y=70 and eventually observed oak logs, but could not reach/harvest them. Final inventory: 1 dirt, no wood or tools, 0 diamonds, no deaths. One rejected LLM output recovered on the second attempt. Small survey geometry reached the model context. |

Terminal product failure ended each attempt before its 3000-second monotonic ceiling. A failed task was not restarted in the same world. Final health/food were 20/20 in every attempt. The main survival-to-diamond objective did **not** pass.

## Diagnosis of the previous round

Read-only inputs: `/workspace/blockagent-survival-abc/RESULT.md`, `ATTEMPT1_HANG.md`, and `attempt-{1,2,3}/` final outcomes/statuses/logs.

- Previous attempt 1 lost the X/:2 connection, followed by client exit and bridge refusal. This proves display/client loss, not an intrinsic JVM crash or who killed X. Death also lost furnace/iron.
- Previous attempt 2 exhausted its 3000-second budget after repeated deaths, ending with stone tools/raw iron. The sampled death-resume count missed some actual server-recorded deaths.
- Previous attempt 3 reached an iron pickaxe at Y≈97, then an invalid model JSON exception escaped the controller's program-repair loop and terminated the entire command. The raw rejected output was not retained, so its exact defect remains unknown.

## Minimal product changes

1. `2a7f3db`: reject and retry invalid response envelopes/model JSON before returning any decision to the interpreter. Transport and invalid-output failures share a maximum of four attempts per decision; the original command cancellation/deadline remains active. Retain the original decision input, do not rewrite malformed executable JSON or replay game actions. HTTP errors, including authentication failures, remain immediate failures. Persistent bad output still fails after the bounded limit.
2. `2a7f3db`: underground-ore guidance prioritizes actual descent after obtaining the required tool, safe observed staircase clearance, local continuation loops, tool durability and exploration at diamond depth instead of repeated surface searches.
3. `63cdcda`: round-2 decision logs exposed a concrete information loss: survey history retained block kinds but discarded the grid needed for the next route decision. Preserve complete local geometry only for surveys of at most 64 cells that fit a 1200-byte projected outcome. Preserve collision/fluid/support facts; drop the whole geometry before entry trimming, never a deceptively partial grid. Existing global evidence/entry budgets remain enforced. Clarify integer coordinates from `headBlock.position` and three forward-column clearance cells for a one-step descent.

Existing death/prerequisite and low-air controls were retained. No attempt in this round had a server-recorded death, so death recovery, the earlier undercount, and underwater escape were **not validated** here.

## What the new evidence proves, and what remains blocked

- Real invalid-output recovery in attempts 1 and 3: `LLM invalid-output retry: attempt=1/4`, followed by valid `LLM metrics` with `transportAttempts=2`, further game actions and later terminal failures for a different reason. Exact rejected syntax/envelope content is still unavailable; do not infer its cause from the retry label.
- Attempt 1 first observed milestones: wooden pickaxe 776.215 s; stone pickaxe 876.487 s; raw iron 1074.054 s; iron ingot 1573.385 s; iron pickaxe 1593.429 s. With the iron pickaxe, observed Y decreased from 53 to 22. This improves descent over the prior Y≈97 stall, but never reached an effective diamond search depth or acquired diamond.
- Attempts 2/3 expose unresolved terrain access and planning quality: repeated `navigation_no_local_route`, repeated surveys/searches without world changes, and exhaustion of the default nine planning passes (initial + eight replans). Attempt 3 also tried unavailable dirt placement, unsupported placement, and `dig_at` on rock without a tool capable of harvesting drops (`No actual inventory tool can harvest this block's drops`). Its final repair included `add requires exactly two operands`. These are capability/program/route failures, not proof that wood or diamonds are globally absent.
- The small-map change was exercised in attempt 3, but that attempt never reached tools/iron/descent. Its effect on the full iron-to-diamond stage is therefore unproven. Do not claim the final product passed that stage based on attempt 1's earlier commit.
- LLM transport retries were observed 9/1/5 times in attempts 1/2/3. Host wall timestamps jumped dramatically, and server logs record scheduling stalls (including ~137 seconds behind in attempt 3). Audio-device failures also flooded client logs, without terminating the client. These are throughput confounders; none changes the FAIL result or warrants attributing a specific host cause without evidence.

## Conditions and reproducibility

- Minecraft 1.21.1 / NeoForge 21.1.233, Java 21 (`JAVA_HOME=/opt/temurin-21`). Fresh random world and fresh agent memory per attempt. Survival, normal difficulty, monsters enabled, operators `[]`, force-gamemode enabled, command blocks and RCON disabled. No item grants, cheats, teleportation, server commands or external gameplay intervention.
- Model: `gpt-6.1-sol` through `https://eeee.hello-happy.world/v1`, using `CODEX_RELAY_API_KEY` through environment substitution. No key printed or embedded in artifacts.
- Each command used the same survival-to-diamond instruction and a 3000-second `time.monotonic()` budget. Product timing uses `System.nanoTime()`. Wall dates are traceability only. Preflight required 60 seconds of connected, empty-inventory, normal-difficulty observations before the sole command submission for each attempt.
- Detached Xvfb :4 per launch. The reused bridge overlays only permit observation during native actions. `stabilize.jar` only extends MC network timeouts; it does not modify product LLM/core behavior. Attempts 2/3 additionally use `EvidenceTap.java`/`evidence.jar` to print LlmClient decision input, without changing return values, transport or gameplay. Attempt 1 had no decision tap. No gameplay actions were issued by the monitor other than the initial instruction and terminal/deadline cancellation.
- Runtime manifests identify inputs and hashes, and `product-head.txt` identifies the loaded source commit. Product source was changed while attempt 2 remained in its already loaded JVM; only attempt 3 loaded `63cdcda`. Harness versions are retained per attempt. No core bytecode replacement was used apart from the explicitly read-only input tap in attempts 2/3.

Evidence root: `/workspace/blockagent-survival-abc-round2/`:

- `attempt-{1,2,3}/`: preflight, full progress observations/statuses, initial/final inventory, client/server logs, outcomes, timing, validation, runtime manifests, worlds and agent memory.
- `summary.json`: milestones, minimum heights, final inventory/position, retries and death evidence for each attempt.
- `logs/gradle-final.log`, `geometry-final-check.log`: final unmodified-product checks. `attempt-1/logs/gradle-check-build.log`: initial patch checks.
- `agent/EvidenceTap.java`, `overlay/`, `client-harness.gradle`, `launch.py`, `run-attempt.py`, `monitor.py`, `retry.py`, `supervise.py`: harness sources. The supervisor stopped at attempt 3. Acceptance JVMs/display were stopped after final evidence collection.
- `artifacts/`: final 0.0.2 jars and SHA-256; `final-source-manifest.json`: final product/harness hashes.

## Verification and handoff

Java 21 `./gradlew check build`: **PASS**, 37 unit tests, 0 failures/errors. Tests cover malformed model/envelope recovery, non-object output, bounded persistent malformed output, transport/deadline/cancellation/authentication behavior, exact small survey layout/hazard preservation, whole-map omission under tight budgets, and existing evidence-budget/survival policies. The final check restored unmodified product compilation after harness use; cached/up-to-date tasks are identified in the Gradle log. This is local validation, not GitHub Actions.

`mod_version=0.0.2`; both README references remain `blockagent-neoforge-1.21.1-0.0.2.jar`.

Keep https://github.com/CPearl0/Block-Agent/pull/5 **draft**. The invalid-output termination blocker is improved and observed recovery works, but reliable terrain access, efficient local-program repair/descent and diamond acquisition remain unresolved. No merge, release or ready transition. Grok retains the merge/release decision after a later successful acceptance. ATM9 was outside this task and untouched.
