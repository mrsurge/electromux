import {test} from 'node:test';
import assert from 'node:assert/strict';
import {observeChild} from '../src/child-proof.js';
import {handle} from '../src/handlers.js';

test('child exit/stdout/stderr and extra-FD readiness are independently retained', async () => {
  const result = await observeChild({executable: '/bin/sh', args: ['-c',
    'printf "stdout-proof\\n"; printf "stderr-proof\\n" >&2; printf "ready:%s\\n" "$$" >&3; exit 7'],
    cwd: process.cwd(), env: {PATH: '/usr/bin:/bin'}}, false);
  assert.equal(result.code, 7); assert.equal(result.signal, null);
  assert.equal(result.stdout, 'stdout-proof\n'); assert.equal(result.stderr, 'stderr-proof\n');
  assert.equal(result.readiness, `ready:${result.pid}\n`); assert.equal(result.cancelled, false);
});
test('readiness-triggered cancellation terminates the owned child group', async () => {
  const result = await observeChild({executable: '/bin/sh', args: ['-c',
    'sleep 30 & child=$!; printf "descendant=%s\\n" "$child"; printf "ready:%s\\n" "$$" >&3; wait "$child"'],
    cwd: process.cwd(), env: {PATH: '/usr/bin:/bin'}}, true);
  assert.equal(result.cancelled, true); assert.equal(result.signal, 'SIGTERM');
  assert.match(result.stdout, /^descendant=\d+\n$/);
});
test('spawn errors propagate and standalone handler cannot execute Termux children', async () => {
  await assert.rejects(observeChild({executable: '/does/not/exist', args: [], cwd: process.cwd(), env: {}}, false), /ENOENT/);
  await assert.rejects(handle({id: 1, method: 'child.proof'}, process.cwd()), /not authorized/);
});
test('child without readiness cannot hang the request indefinitely', async () => {
  await assert.rejects(observeChild({executable: '/bin/sh', args: ['-c', 'exec sleep 30'],
    cwd: process.cwd(), env: {PATH: '/usr/bin:/bin'}}, false), /deadline exceeded/);
});
test('excess child output closes the bounded proof instead of accumulating', async () => {
  await assert.rejects(observeChild({executable: '/bin/sh', args: ['-c', 'printf "%9000s" x'],
    cwd: process.cwd(), env: {PATH: '/usr/bin:/bin'}}, false), /output limit exceeded/);
});
