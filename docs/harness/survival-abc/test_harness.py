import ast, json, pathlib, tempfile
root = pathlib.Path('/workspace/blockagent-survival-abc-round3')
for base in [root, pathlib.Path('/workspace/blockagent-survival-abc-round2')]:
    for name in ['monitor.py', 'retry.py', 'supervise.py', 'hang-watch.py']:
        compile((base/name).read_text(), str(base/name), 'exec')
# Execute ONLY the identity-mismatch branch in an isolated temporary directory.
module = ast.parse((root/'monitor.py').read_text())
branch = next(n for n in ast.walk(module) if isinstance(n, ast.If) and ast.unparse(n.test) == "status.get('commandId') != expected_command_id")
body = ast.Module(body=branch.body[:-1], type_ignores=[])
with tempfile.TemporaryDirectory() as tmp:
    p = pathlib.Path(tmp); (p/'logs').mkdir()
    def forbidden(*args, **kwargs):
        raise AssertionError('Identity mismatch must not call bridge')
    env = dict(root=p, status={'commandId':'replacement'}, expected_command_id='original',
               elapsed=100, obs={}, at='fixture', req=forbidden, log=lambda value:None, json=json)
    exec(compile(body, '<identity branch>', 'exec'), env)
    out = env['outcome']
    assert out['counts_toward_three'] is False and out['automatic_retry_allowed'] is False
    assert out['cancel_sent'] is False and out['acceptance'] == 'quarantined'
# Execute retry gate in isolation for both new and legacy monitor results.
module = ast.parse((root/'retry.py').read_text())
gate = next(n for n in module.body if isinstance(n, ast.Assert) and 'counts_toward_three' in ast.unparse(n))
for state in ['retryable_identity_change', 'invalid_external_resubmission']:
    try:
        exec(compile(ast.Module(body=[gate], type_ignores=[]), '<retry gate>', 'exec'), {'out': {'state':state}})
    except AssertionError:
        pass
    else:
        raise AssertionError('retry gate failed: '+state)
exec(compile(ast.Module(body=[gate], type_ignores=[]), '<retry gate>', 'exec'), {'out': {'state':'failed'}})
assert 'req(' not in (root/'hang-watch.py').read_text()
print('PASS: round2/round3 syntax; identity quarantine sends no bridge action; legacy/new retry blocked before mutation; ordinary failure gate preserved; hang-watch hold unchanged')
