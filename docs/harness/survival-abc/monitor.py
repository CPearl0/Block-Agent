#!/usr/bin/env python3
import json, time, datetime, urllib.request, pathlib, traceback
root = pathlib.Path('/workspace/blockagent-survival-abc-round3')
bridge = json.load(open('/workspace/repos/Block-Agent/run/client/blockagent/bridge.json'))

def req(path, data=None):
    r = urllib.request.Request(
        bridge['url'] + path,
        data=None if data is None else json.dumps(data).encode(),
        headers={'Authorization': 'Bearer ' + bridge['token'], 'Content-Type': 'application/json'},
    )
    return json.load(urllib.request.urlopen(r, timeout=20))

def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def log(obj):
    with (root / 'logs/progress.log').open('a') as f:
        f.write(json.dumps(obj, ensure_ascii=False) + '\n')

def write_status(text):
    (root / 'STATUS.md').write_text(text)

HARD = 3000
POLL = 10

initial = req('/tool', {'name': 'observe', 'args': {}})
(root / 'logs/initial-inventory.json').write_text(json.dumps(initial, indent=2))
assert initial.get('ok'), f'Preflight observe failed: {initial}'
diff = (initial.get('data') or {}).get('difficulty')
assert diff == 'normal', f'Expected normal difficulty, got {diff!r}'
inv0 = (initial.get('data') or {}).get('inventory', []) or []
assert inv0 == [], f'Preflight inventory must be empty, got {inv0}'

command = json.load(open(root / 'command.json'))
start = time.monotonic()
wall_start = time.time()
stamp = now()
(root / 'timing.json').write_text(json.dumps({'task_start': stamp, 'start_epoch': wall_start, 'deadline_epoch': wall_start + HARD, 'start_monotonic': start}, indent=2))
log({'at': stamp, 'event': 'POST /command', 'response': req('/command', command)})
expected_command_id = req('/status').get('commandId')
assert expected_command_id, 'Command identity must be observable'
(root / 'command-identity.json').write_text(json.dumps({'expectedCommandId': expected_command_id}))

terminal = {'completed', 'failed', 'error', 'stopped', 'cancelled', 'idle', 'blocked', 'closed'}
milestones = {
    'wooden_pickaxe': False,
    'stone_pickaxe': False,
    'raw_iron': False,
    'iron_ingot': False,
    'iron_pickaxe': False,
}
outcome = None
last_good = time.monotonic()

while True:
    elapsed = time.monotonic() - start
    if elapsed >= HARD:
        try:
            log({'at': now(), 'event': 'hard_stop', 'response': req('/cancel', {})})
        except Exception as e:
            log({'at': now(), 'event': 'hard_stop_cancel_error', 'error': str(e)})
        outcome = {'state': 'hard_stop', 'success': False, 'elapsed_sec': HARD, 'at': now()}
        break
    try:
        status = req('/status')
        last_good = time.monotonic()
        obs = req('/tool', {'name': 'observe', 'args': {}})
        elapsed = time.monotonic() - start
        at = now()
        data = obs.get('data') or {}
        inv = data.get('inventory') or []
        diamonds = sum(i.get('count', 0) for i in inv if i.get('name') == 'minecraft:diamond')
        state = status.get('state') if isinstance(status, dict) else None
        names = {i.get('name'): i.get('count', 0) for i in inv}
        for item, key in [
            ('minecraft:wooden_pickaxe', 'wooden_pickaxe'),
            ('minecraft:stone_pickaxe', 'stone_pickaxe'),
            ('minecraft:raw_iron', 'raw_iron'),
            ('minecraft:iron_ingot', 'iron_ingot'),
            ('minecraft:iron_pickaxe', 'iron_pickaxe'),
        ]:
            if not milestones[key] and names.get(item, 0) >= 1:
                milestones[key] = True
                log({'at': at, 'elapsed_sec': elapsed, 'event': 'milestone', 'milestone': key, 'count': names.get(item)})
        entry = {
            'at': at,
            'elapsed_sec': elapsed,
            'status': status,
            'inventory_observation': obs,
            'diamond_count': diamonds,
            'difficulty': data.get('difficulty'),
            'health': data.get('health'),
            'food': data.get('food'),
        }
        log(entry)
        (root / 'logs/latest-inventory.json').write_text(json.dumps(obs, indent=2))
        (root / 'logs/latest-status.json').write_text(json.dumps(status, indent=2))
        write_status(
            f"# Survival ABC\n\n- elapsed_sec: {elapsed:.1f} / {HARD}\n- state: {state}\n- diamonds: {diamonds}\n"
            f"- difficulty: {data.get('difficulty')}\n- milestones: {milestones}\n- health/food: {data.get('health')}/{data.get('food')}\n- at: {at}\n"
            f"- branch: fix/survival-abc-0.0.2\n- renderedEvidenceBytes: 20480\n- identity_change_policy: quarantine_no_cancel_no_attempt_charge\n"
        )
        if status.get('commandId') != expected_command_id:
            # Identity alone cannot prove same-task continuation. Quarantine acceptance;
            # never cancel the replacement command, reset the clock, or burn an attempt.
            # A future authenticated recovery record may allow adoption; text equality cannot.
            outcome = {'state': 'retryable_identity_change', 'success': False,
                       'acceptance': 'quarantined', 'counts_toward_three': False,
                       'retryable': True, 'automatic_retry_allowed': False,
                       'reason': 'Unproven command identity transition; preserve live task for existing followup',
                       'elapsed_sec': elapsed, 'expectedCommandId': expected_command_id,
                       'observedCommandId': status.get('commandId'),
                       'final_status': status, 'final_obs': obs, 'cancel_sent': False}
            log({'at': at, 'event': 'command_identity_changed', 'outcome': outcome})
            (root / 'logs/final-inventory.json').write_text(json.dumps(obs, indent=2))
            break
        if diamonds >= 1:
            try:
                cancel = req('/cancel', {})
            except Exception as e:
                cancel = {'error': str(e)}
            outcome = {
                'state': 'success',
                'success': True,
                'elapsed_sec': elapsed,
                'at': at,
                'diamond_obtained_at': at,
                'diamond_count': diamonds,
                'cancel': cancel,
            }
            log({'at': at, 'event': 'diamond_obtained', 'outcome': outcome})
            (root / 'logs/final-inventory.json').write_text(json.dumps(obs, indent=2))
            break
        if state in terminal and elapsed > 10:
            outcome = {
                'state': state,
                'success': False,
                'elapsed_sec': elapsed,
                'at': at,
                'final_status': status,
                'final_obs': obs,
            }
            log({'at': at, 'event': 'terminal_state', 'outcome': outcome})
            (root / 'logs/final-inventory.json').write_text(json.dumps(obs, indent=2))
            break
    except Exception as e:
        log({'at': now(), 'event': 'poll_error', 'error': str(e), 'trace': traceback.format_exc()[-1000:]})
        if time.monotonic() - last_good >= 90:
            outcome = {'success': False, 'state': 'bridge_unavailable', 'elapsed_sec': time.monotonic()-start, 'error': str(e)}
            break
        time.sleep(POLL)
        continue
    time.sleep(POLL)

(root / 'monitor-outcome.json').write_text(json.dumps(outcome, indent=2))
write_status(f"# Survival ABC FINAL\n\n```json\n{json.dumps(outcome, indent=2)}\n```\n")
print(json.dumps(outcome, indent=2))
