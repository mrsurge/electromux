import {test} from 'node:test';
import assert from 'node:assert/strict';
import {ConsumerHost} from '../src/consumer-host.js';
import {OwnedChildSupervisor} from '../src/child-supervisor.js';
import {observeChild} from '../src/child-proof.js';

test('consumer retains initialization and rejects undeclared methods; disposal is once', async () => {
  let starts = 0, stops = 0;
  const host = new ConsumerHost(async () => { starts++; return {
    methods: ['ping'], async dispatch() { return {ok: true}; }, async dispose() { stops++; }
  }; });
  await host.ready(); await host.ready();
  await assert.rejects(host.dispatch('eval'), /Undeclared/);
  assert.deepEqual(await host.dispatch('ping'), {ok: true});
  await Promise.all([host.dispose(), host.dispose()]);
  await assert.rejects(host.dispatch('ping'), /unavailable/);
  assert.equal(starts, 1); assert.equal(stops, 1);
});
test('failed initialization is retained without retry', async () => {
  let starts = 0;
  const host = new ConsumerHost(async () => { starts++; throw new Error('startup failed'); });
  await assert.rejects(host.ready(), /startup failed/);
  await assert.rejects(host.ready(), /startup failed/);
  await host.dispose(); assert.equal(starts, 1);
});
test('concurrent mutation is rejected and late result cannot escape closed owner', async () => {
  let finish!: () => void;
  const pending = new Promise<void>(resolve => { finish = resolve; });
  const host = new ConsumerHost(async () => ({methods: ['run'], async dispatch() { await pending; return {}; }, async dispose() {}}));
  await host.ready(); const request = host.dispatch('run');
  await assert.rejects(host.dispatch('run'), /unavailable/);
  await host.dispose(); finish(); await assert.rejects(request, /closed/);
});
test('owned supervisor abort joins its child and prevents subsequent execution', async () => {
  const children = new OwnedChildSupervisor();
  const running = children.run(signal => observeChild({executable: '/bin/sh', args: ['-c', 'exec sleep 30'],
    cwd: process.cwd(), env: {PATH: '/usr/bin:/bin'}}, false, signal));
  const rejected = assert.rejects(running, /owner closed/);
  await new Promise(resolve => setTimeout(resolve, 30));
  await children.dispose(); await rejected;
  await assert.rejects(children.run(async () => 1), /unavailable/);
});
