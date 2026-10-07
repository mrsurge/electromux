const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const Bridge = require('../android/host/src/main/assets/electromux-bridge.js');

(async () => {
  let pending;
  const bridge = Bridge.create({query: request => {pending = request;},
    methods: ['ping'], events: ['sample.updated'], timeoutMs: 30});
  let seen = 0;
  const off = bridge.on('sample.updated', () => { seen++; });
  let promise = bridge.request('ping', {value: 'hello'});
  assert.deepEqual(JSON.parse(pending.request), {id: 1, method: 'ping', params: {value: 'hello'}});
  pending.onSuccess(JSON.stringify({id: 1, result: {ok: true, value: {pong: true}},
    events: [{name: 'sample.updated', payload: {}}]}));
  assert.deepEqual(await promise, {pong: true});
  assert.equal(seen, 1);
  off();
  promise = bridge.request('ping');
  pending.onSuccess(JSON.stringify({id: 2, result: {ok: true, value: {}},
    events: [{name: 'sample.updated', payload: {}}]}));
  await promise;
  assert.equal(seen, 1);
  promise = bridge.request('ping');
  pending.onSuccess(JSON.stringify({id: 99, result: {ok: true, value: {}}}));
  await assert.rejects(promise, /Invalid bridge response/);
  promise = bridge.request('ping');
  pending.onSuccess(JSON.stringify({id: 4, result: {ok: false, error: {message: 'Failed', code: 'TEST'}}}));
  await assert.rejects(promise, error => error.code === 'TEST');
  await assert.rejects(bridge.request('unsupported'));
  promise = bridge.request('ping');
  pending.onSuccess(JSON.stringify({id: 5, result: {ok: true, value: {}},
    events: [{name: 'unregistered', payload: {}}]}));
  await assert.rejects(promise, /Invalid bridge events/);
  promise = bridge.request('ping');
  const late = pending;
  await assert.rejects(promise, /timed out/);
  late.onSuccess(JSON.stringify({id: 6, result: {ok: true, value: {}}}));
  promise = bridge.request('ping');
  bridge.dispose();
  await assert.rejects(promise, /disposed/);
  await assert.rejects(bridge.request('ping'));
  const bounded = Bridge.create({query: request => {pending = request;}, methods: ['ping'], maxPending: 1});
  promise = bounded.request('ping');
  await assert.rejects(bounded.request('ping'), /queue full/);
  pending.onFailure(429, 'Queue full');
  await assert.rejects(promise, /429/);
  await assert.rejects(bounded.request('ping', {value: 'x'.repeat(4096)}), /too large/);
  bounded.dispose();

  const documentId = 'renderer_document_123456';
  const asynchronous = Bridge.create({query: request => {pending = request;},
    methods: ['ping'], events: ['state'], documentId});
  const received = [];
  asynchronous.on('state', value => { received.push(value); });
  const event = JSON.stringify({documentId, name: 'state', payload: {ready: true}});
  assert.equal(asynchronous.receiveEvent(event), true);
  assert.deepEqual(received, [{ready: true}]);
  assert.equal(asynchronous.receiveEvent(JSON.stringify({documentId: 'other_document_123456', name: 'state', payload: {}})), false);
  assert.equal(asynchronous.receiveEvent(JSON.stringify({documentId, name: 'other', payload: {}})), false);
  assert.equal(asynchronous.receiveEvent('{invalid'), false);
  assert.equal(asynchronous.receiveEvent('x'.repeat(65537)), false);
  promise = asynchronous.request('ping');
  assert.equal(JSON.parse(pending.request).documentId, documentId);
  pending.onSuccess(JSON.stringify({id: 1, result: {ok: true, value: {}}}));
  await promise;
  asynchronous.dispose();
  assert.equal(asynchronous.receiveEvent(event), false);
  assert.equal(received.length, 1);
  const replacement = Bridge.create({query() {}, methods: ['ping'], events: ['state'],
    documentId: 'replacement_document_123456'});
  assert.equal(replacement.receiveEvent(event), false);
  replacement.dispose();

  const status = {textContent: ''}, result = {textContent: ''};
  const buttons = ['connect', 'ping'].map(method => ({dataset: {method}, disabled: false,
    addEventListener(_, callback) { this.click = callback; }}));
  const pageListeners = {};
  const pageWindow = {ElectromuxBridge: Bridge, crypto: {getRandomValues: values => values.fill(1)},
    cefriumQuery: request => {pending = request;}, addEventListener(name, listener) {pageListeners[name] = listener;}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,
    '../android/sample/src/main/assets/sample.js'), 'utf8'), {
    document: {getElementById: id => id === 'status' ? status : result, querySelectorAll: () => buttons},
    window: pageWindow,
  });
  promise = buttons[0].click();
  assert.ok(buttons.every(button => button.disabled));
  buttons[1].click();
  assert.deepEqual(JSON.parse(pending.request), {id: 1, method: 'connect', params: {}, documentId: '01'.repeat(16)});
  pending.onSuccess(JSON.stringify({id: 1, result: {ok: true, value: {state: 'stopped'}}}));
  await promise;
  assert.equal(status.textContent, 'Completed connect');
  promise = buttons[1].click();
  pending.onFailure(403, 'Untrusted request');
  await promise;
  assert.match(status.textContent, /rejected \(403\)/);
  assert.ok(buttons.every(button => !button.disabled));
  assert.equal(pageWindow.__electromuxReceiveEvent(JSON.stringify({documentId: '01'.repeat(16),
    name: 'sample.state', payload: {id: 7, state: 'ready'}})), true);
  assert.match(result.textContent, /Native event: sample.state \(7, ready\)/);
  const staleReceiver = pageWindow.__electromuxReceiveEvent;
  pageListeners.pagehide();
  assert.equal(pageWindow.__electromuxReceiveEvent, undefined);
  assert.equal(staleReceiver(JSON.stringify({documentId: '01'.repeat(16), name: 'sample.state', payload: {}})), false);
  console.log('Bridge response/event/disposal/deadline and sample single-flight checks passed');
})().catch(error => {console.error(error); process.exitCode = 1;});
