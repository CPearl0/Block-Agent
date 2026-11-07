#!/usr/bin/env python3
"""Watch Dig for LLM hang. Prefer in-place hold (same attempt/commandId).
NEVER cancel+/command — that yields invalid_external_resubmission and burns an attempt.
"""
import json, time, datetime, pathlib

root = pathlib.Path('/workspace/blockagent-survival-abc-round3')
log_path = root / 'logs/hang-watch.log'

def log(msg, **extra):
    line = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'msg': msg, **extra}
    with log_path.open('a') as f:
        f.write(json.dumps(line, ensure_ascii=False) + '\n')
    print(json.dumps(line, ensure_ascii=False), flush=True)

def snapshot():
    st = json.loads((root / 'logs/latest-status.json').read_text())
    lines = (root / 'logs/progress.log').read_text().strip().splitlines()
    last = json.loads(lines[-1]) if lines else {}
    return {
        'elapsed': float(last.get('elapsed_sec') or 0),
        'state': st.get('state'),
        'upd': st.get('updatedAtMs'),
        'llm': st.get('llmRequests'),
        'tools': st.get('toolCalls'),
        'segs': st.get('localSegments'),
        'cmdId': st.get('commandId'),
        'active': (root / 'active-attempt').read_text().strip(),
    }

base = snapshot()
log('watch_start', policy='hold_same_command_no_cancel', **base)
target_elapsed = base['elapsed'] + 300
deadline_wall = time.time() + 20 * 60
while time.time() < deadline_wall:
    time.sleep(30)
    try:
        cur = snapshot()
    except Exception as e:
        log('snapshot_error', error=str(e))
        continue
    progressed = (
        cur['upd'] != base['upd']
        or cur['tools'] != base['tools']
        or cur['llm'] != base['llm']
        or cur['state'] not in ('planning', 'generating', 'queued')
    )
    if not (root / 'pids.json').exists() or cur['active'] != base['active']:
        log('attempt_changed_or_ended', **cur)
        break
    if (root / 'monitor-outcome.json').exists():
        log('monitor_outcome_present_skip', **cur)
        break
    if progressed:
        log('progress_resumed_no_action', **cur)
        break
    if cur['elapsed'] >= target_elapsed:
        # Hold: do NOT cancel/recommand (burns attempt via invalid_external_resubmission).
        log('hang_confirmed_hold_no_cancel', **cur, note='await self-clear or attempt terminal; preserve commandId')
        break
    log('still_waiting', **cur, target_elapsed=target_elapsed)
else:
    try:
        cur = snapshot()
    except Exception as e:
        log('wall_deadline_snapshot_error', error=str(e))
        cur = base
    log('wall_deadline_exit', **cur)
    if cur['state'] in ('planning', 'generating', 'queued') and cur['upd'] == base['upd']:
        log('wall_deadline_hung_hold_no_cancel', **cur, note='await self-clear; no cancel+/command')
log('watch_done')
