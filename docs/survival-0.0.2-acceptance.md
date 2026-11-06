# Block-Agent survival diamond acceptance — FAIL

Three attempts exhausted; **0 diamonds in all attempts**. PR #5 must remain draft. No merge or release was performed.

| Attempt | Runtime | Result | Progress and blocker |
| --- | --- | --- | --- |
| 1 | Original reference core/transport overlays on main 6edc7ff | FAIL; last recorded progress about 2314/3000s | Stone pickaxe and 2 raw iron; drowned, lost furnace/iron, rebuilt basic tools. Client exited after X connection to :2 broke; bridge requests were refused. |
| 2 | Product 1e5b4c4; read-only bridge overlays | FAIL; monotonic hard stop at 3000s | Three server-recorded deaths (skeleton, drowned twice); final stone pickaxe + 3 raw iron, no iron ingot/pickaxe. LLM retries and host pauses consumed budget. |
| 3 | Product d07cb85; read-only bridge overlays | FAIL; terminal state at 1240.277s, within 3000s budget | Wood → stone → raw iron → smelted iron → iron pickaxe verified. No deaths. Still at Y=97 with 0 diamond; task failed with `LLM returned invalid JSON output; no game actions executed from this response`. |

## Conditions and evidence

- Fresh random world and fresh agent memory for every Dig. Normal survival, normal difficulty, monsters enabled, operators `[]`, command blocks/RCON disabled. No cheats, grants, teleportation or external gameplay intervention. No Cursor CloudAgent; cloud Codex CLI and gpt-6.1-sol through the requested relay only. Credentials were resolved from environment and not printed.
- Each Dig had a 3000-second Python monotonic budget; Java command elapsed time uses nanoTime. Terminal product failures end an attempt early. No fourth attempt or resubmitted third command was run.
- Attempt 2's initial preflight expired during a host pause; Dig had not been submitted. Repeated preflight on the same untouched empty-inventory world passed 60 seconds before the sole attempt-2 command. Subsequent preflight window increased to 900 seconds; Dig budget unchanged.
- Raw evidence is retained at `/workspace/blockagent-survival-abc/attempt-{1,2,3}/`: progress logs, server/client console logs, preflight, final/latest inventory, timing, validation, world and agent memory. Attempt 3 logs also remain at `logs/`. `monitor-outcome.json` and `live.json` describe attempt 3.
- `ATTEMPT1_HANG.md` contains the follow-up diagnosis. Client log explicitly records broken X connection and runClient exit 1; it does not identify who stopped Xvfb. Do not attribute this to an intrinsic JVM crash without further evidence.
- Attempts 2/3 use a dedicated detached Xvfb :4, saved PID/log, and monitor exit after 90 monotonic seconds of status unavailability. Product core is used directly; the agent no longer rewrites LLM bytecode. Remaining agent instrumentation extends MC network timeouts only; bridge overlays allow read-only observation during native actions.
- Server death announcements and inventory changes establish attempt-2 deaths; `deathResumes` only reached 1 for 3 actual deaths. The sampled death counter can miss a death/respawn during long model waits. This discrepancy remains unresolved.
- Attempt-3 first observed milestones (seconds): wooden pickaxe 346.681; stone pickaxe 642.246; raw iron 1029.756; iron ingot 1079.882; iron pickaxe 1150.056. Final iron pickaxe is undamaged, health/food 20/20, position (10.4944,97,18.3190). No diamond ore mining or diamond pickup verified.
- Invalid model output is reported by the product and captured in final status. The raw rejected response was not retained; its exact syntax defect or upstream cause cannot be established from these logs. No action from that response executed. Recovery from invalid model JSON remains a blocker.
- Host wall timestamps jumped and scheduling pauses occurred (including one server report of ~311 seconds behind). Use monotonic elapsed fields rather than subtracting wall timestamps. Long transport attempts and pauses are confounders, not grounds to claim acceptance passed.

## Product changes and verification

- Existing PR hardening: monotonic command timing, bounded whole-body HTTP delivery and transport retries, version 0.0.2.
- d07cb85: check current prerequisites even without a death receipt; replan or replace lost tools/materials. Native low-air protection holds jump below air supply 200, pauses applicable tools and allows bounded interruption/replanning. This cannot guarantee escape below an overhang and does not alter oxygen or position. Attempt 3 encountered no drowning, so underwater escape has **not** been validated in-game.
- Final Java 21 `./gradlew check build`: PASS (`logs/gradle-final.log`), including low-air policy boundaries and existing LLM fault tests.
- `gradle.properties`: `mod_version=0.0.2`; both README jar references match `blockagent-neoforge-1.21.1-0.0.2.jar`. Jar prefix is `blockagent-`. Artifact and SHA-256 are in `artifacts/`.
- `runtime-manifest.json` records attempt-3 runtime inputs; `final-source-manifest.json` hashes product source and final harness inputs. Earlier manifests retained per attempt. The build uses unmodified product sources, without acceptance bridge overlays.

## Handoff

Keep https://github.com/CPearl0/Block-Agent/pull/5 **draft**. Survival-to-diamond acceptance failed after all three attempts. Remaining work includes reliable descent/mineral exploration, recovery from malformed model responses and complete death tracking. Do not blindly merge or publish 0.0.2. Grok retains responsibility for any later merge/release decision; this run performed neither.
