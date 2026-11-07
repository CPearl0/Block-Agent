from pathlib import Path
import json,time,subprocess
r=Path('/workspace/blockagent-survival-abc-round3')
while True:
 p=r/'monitor-outcome.json'
 if not p.exists():
  time.sleep(5);continue
 out=json.loads(p.read_text());n=int((r/'active-attempt').read_text())
 if out.get('state') in ('invalid_external_resubmission', 'retryable_identity_change') or not out.get('counts_toward_three', True):
  (r/'RESULT.md').write_text('# Round 3 survival Dig — QUARANTINED (retryable, not FAIL)\n\ncounts_toward_three: false\nautomatic_retry_allowed: false\nDig control actions: none\n\n```json\n'+json.dumps(out,indent=2)+'\n```\n')
  break
 (r/'RESULT.md').write_text('# Round 3 survival Dig — '+('PASS' if out['success'] else 'in progress' if n<3 else 'FAIL')+'\n\nLatest attempt: '+str(n)+'\n\n```json\n'+json.dumps(out,indent=2)+'\n```\n\nDetailed final analysis pending. PR #5 remains draft until verified PASS.\n')
 if out['success'] or n>=3:break
 subprocess.run(['python3',str(r/'retry.py')],check=True)
 time.sleep(10)
