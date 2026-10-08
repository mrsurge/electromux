import {test} from 'node:test';
import assert from 'node:assert/strict';
import {OutputPump} from '../src/output-pump.js';
import {OwnedService} from '../src/owned-service.js';

test('streaming output exceeds lifetime proof cap without retained accumulation', async () => {
  let received = 0;
  const pump = new OutputPump(async (_lane, bytes) => { received += bytes.length; });
  for (let i = 0; i < 100; i++) await pump.push('stdout', Buffer.alloc(4096, 120));
  await pump.drain();
  assert.equal(received, 409600); assert.equal(Buffer.byteLength(pump.tail('stdout')), 2048);
  assert.equal(pump.tail('stderr'), '');
});
test('slow output queue/deadline fails rather than accumulating', async () => {
  const blocked = new OutputPump(async () => new Promise<void>(() => {}), 20);
  const results = await Promise.allSettled(Array.from({length: 17}, () => blocked.push('stderr', Buffer.from('x'))));
  assert.equal(results.filter(result => result.status === 'rejected').length, 17);
  await assert.rejects(blocked.drain(), /queue/);
  const slow = new OutputPump(async () => new Promise<void>(() => {}), 20);
  await assert.rejects(slow.push('stdout', Buffer.from('x')), /deadline/);
});
test('service consumes more than 8KiB and resets the output lane for explicit restart', async () => {
  let received = 0;
  const service = new OwnedService(() => {}, {output: async (_lane, bytes) => { received += bytes.length; }});
  const spec = {executable: '/bin/sh', cwd: process.cwd(), env: {PATH: '/usr/bin:/bin'},
    args: ['-c', 'printf "ready:%s\\n" "$$" >&3; printf "%12000s" x; exec sleep 30']};
  try {
    for (let i = 0; i < 2; i++) {
      await service.start(spec, pid => `ready:${pid}\n`);
      const deadline = Date.now() + 1000;
      while (received < (i + 1) * 12000) {
        assert.ok(Date.now() < deadline); await new Promise(resolve => setTimeout(resolve, 5));
      }
      assert.equal(service.snapshot().state, 'ready');
      assert.equal(Buffer.byteLength(service.outputTail('stdout')), 2048);
      await service.stop();
    }
  } finally { await service.dispose(); }
});
test('native policy can select indefinite readiness and cancel before hello', async () => {
  const service = new OwnedService(() => {}, {readinessMs: null, stopGraceMs: 20});
  const start = service.start({executable: '/bin/sh', cwd: process.cwd(), env: {PATH: '/usr/bin:/bin'},
    args: ['-c', 'exec sleep 30']}, pid => `ready:${pid}\n`);
  const rejected = assert.rejects(start, /before readiness/);
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(service.snapshot().state, 'starting');
  await service.dispose(); await rejected;
});
