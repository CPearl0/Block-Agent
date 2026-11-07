"""Archive a failed full attempt, clear its MC processes, and launch the repaired product runtime."""
import json,pathlib,os,signal,time,shutil,subprocess,hashlib
r=pathlib.Path('/workspace/blockagent-survival-abc-round3');repo=pathlib.Path('/workspace/repos/Block-Agent')
out=json.loads((r/'monitor-outcome.json').read_text());assert not out['success'],'Refuse retry after PASS'
# Gate before ANY process or world mutation, including when an already-running
# supervisor loads this script after a quarantined monitor result.
assert out.get('state') not in ('invalid_external_resubmission', 'retryable_identity_change') and out.get('counts_toward_three', True) and out.get('automatic_retry_allowed', True), 'Quarantined acceptance: no automatic retry or attempt charge'
n=int((r/'active-attempt').read_text()) if (r/'active-attempt').exists() else 1
assert n<3,'Three-attempt cap'
pids=json.loads((r/'pids.json').read_text())
for pid in pids.values():
 try:os.killpg(pid,signal.SIGTERM)
 except ProcessLookupError:pass
start=time.monotonic()
while time.monotonic()-start<15:
 if not any(pathlib.Path('/proc',str(p)).exists() for p in pids.values()):break
 time.sleep(1)
for pid in pids.values():
 try:os.killpg(pid,signal.SIGKILL)
 except ProcessLookupError:pass
# Gradle may leave the client in its daemon's process group. Target MC only.
for p in pathlib.Path('/proc').iterdir():
 if not p.name.isdigit():continue
 try:
  if (p/'exe').resolve().name!='java':continue
  cmd=(p/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')
  if '-Dblockagent.bridge.port=3081' in cmd or (p/'cwd').resolve()==r/'server':os.kill(int(p.name),signal.SIGTERM)
 except (OSError,PermissionError):pass
archive=r/f'attempt-{n}';archive.mkdir(exist_ok=True)
for name in ['monitor-outcome.json','timing.json','validation.json','STATUS.md','runtime-manifest.json','live.json','product-head.txt']:
 p=r/name
 if p.exists():shutil.move(str(p),str(archive/name))
for p,name in [(r/'logs','logs'),(r/'server/world','world'),(repo/'run/client/blockagent/memory','memory')]:
 if p.exists():shutil.move(str(p),str(archive/name))
(r/'logs').mkdir()
# Only read-only bridge instrumentation remains; core comes directly from the committed product.
p=r/'client-harness.gradle';s=p.read_text();s=s.replace("!it.absolutePath.endsWith('/src/main/java/io/github/cpearl0/blockagent/core/AgentController.java') && ",'').replace("!it.absolutePath.endsWith('/src/main/java/io/github/cpearl0/blockagent/core/LlmClient.java') && ",'');p.write_text(s)
shutil.rmtree(r/'overlay/core',ignore_errors=True)
(r/'active-attempt').write_text(str(n+1))
# Next JVM logs decision returns as well as inputs; no return values are changed.
if (r/'agent/evidence-next.jar').exists():shutil.copy2(r/'agent/evidence-next.jar',r/'agent/evidence.jar')
files=list((r/'overlay').rglob('*.java'))+[r/'agent/stabilize.jar',r/'agent/evidence.jar',r/'agent/EvidenceTap.java',r/'client-harness.gradle',r/'server/server.properties',r/'client-config.json',r/'command.json']+list((repo/'src/main/java').rglob('*.java'))
(r/'runtime-manifest.json').write_text(json.dumps({str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},indent=2))
(r/'product-head.txt').write_text(subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True))
subprocess.run(['python3',str(r/'launch.py')],check=True)
print('Started repaired product attempt',n+1)
