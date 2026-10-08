import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Writable} from 'node:stream';
import {OwnedService, type ServiceState} from '../src/owned-service.js';
import {ConsumerHost, type Emit} from '../src/consumer-host.js';
import {FrameWriter} from '../src/frame-writer.js';

const spec = {executable: '/bin/sh', args: ['-c', 'printf "ready:%s\\n" "$$" >&3; exec sleep 30'],
  cwd: process.cwd(), env: {PATH: '/usr/bin:/bin'}};
test('owned service returns at readiness, survives calls, stops and reports exit', async () => {
  const events: ServiceState[] = [];
  const service = new OwnedService(state => events.push(state));
  try {
    const ready = await service.start(spec, pid => `ready:${pid}\n`);
    assert.equal(ready.state, 'ready'); assert.ok(ready.pid);
    await assert.rejects(service.start(spec, pid => `ready:${pid}\n`), /unavailable/);
    assert.equal(service.snapshot().state, 'ready');
    const exited = await service.stop();
    assert.equal(exited.state, 'exited'); assert.equal(exited.signal, 'SIGTERM');
    assert.deepEqual(events.map(event => event.state), ['starting', 'ready', 'exited']);
    assert.throws(() => process.kill(ready.pid!, 0), {code: 'ESRCH'});
  } finally { await service.dispose(); }
  await assert.rejects(service.start(spec, pid => String(pid)), /unavailable/);
});
test('service startup errors are returned, with no implicit restart', async () => {
  const service = new OwnedService(() => {});
  await assert.rejects(service.start({...spec, executable: '/missing-electromux'}, pid => String(pid)), /ENOENT/);
  assert.equal(service.snapshot().state, 'exited'); await service.dispose();
});
test('natural exit emits after readiness without a follow-up request', async () => {
  const events: ServiceState[] = [];
  let exited!: () => void;
  const done = new Promise<void>(resolve => { exited = resolve; });
  const service = new OwnedService(state => { events.push(state); if (state.state === 'exited') exited(); });
  await service.start({...spec, args: ['-c', 'printf "ready:%s\\n" "$$" >&3; sleep 0.05; exit 7']}, pid => `ready:${pid}\n`);
  await done;
  assert.equal(service.snapshot().code, 7);
  assert.deepEqual(events.map(event => event.state), ['starting', 'ready', 'exited']);
  await service.dispose();
});
test('disposal during startup rejects readiness and joins the child', async () => {
  const service = new OwnedService(() => {});
  const start = service.start({...spec, args: ['-c', 'exec sleep 30']}, pid => `ready:${pid}\n`);
  const rejected = assert.rejects(start, /before readiness/);
  const pid = service.snapshot().pid;
  await service.dispose(); await rejected;
  assert.ok(pid); assert.throws(() => process.kill(pid, 0), {code: 'ESRCH'});
});
test('consumer declares unsolicited events and rejects stale emitters', async () => {
  let emit!: Emit;
  const seen: string[] = [];
  const host = new ConsumerHost(async (_signal, sink) => {
    emit = sink; return {methods: [], events: ['state'], async dispatch() { return {}; }, async dispose() {}};
  }, async name => { seen.push(name); });
  await host.ready(); await emit('state', {ready: true});
  await assert.rejects(emit('other', {}), /Undeclared/);
  await host.dispose(); await assert.rejects(emit('state', {}), /unavailable/);
  assert.deepEqual(seen, ['state']);
});
test('writer bounds queued frames and slow write completion', async () => {
  const blocked = new Writable({write(_chunk, _encoding, _callback) {}});
  blocked.on('error', () => {});
  const writer = new FrameWriter(blocked, 20);
  const requests = Array.from({length: 17}, (_, id) => writer.send({id, result: {}}));
  const results = await Promise.allSettled(requests);
  assert.equal(results.filter(result => result.status === 'rejected').length, 17);
  assert.ok(blocked.destroyed);
  const slow = new Writable({write(_chunk, _encoding, _callback) {}});
  slow.on('error', () => {});
  await assert.rejects(new FrameWriter(slow, 20).send({id: 1, result: {}}), /deadline/);
});
