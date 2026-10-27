import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Duplex, PassThrough} from 'node:stream';
import {ElectronChannel} from '../src/electron-channel.js';
import {FrameDecoder} from '../src/protocol.js';

function frame(value: unknown): Buffer {
  const body = Buffer.from(JSON.stringify(value)), header = Buffer.alloc(4);
  header.writeUInt32BE(body.length); return Buffer.concat([header, body]);
}
function pair(dispatch: ConstructorParameters<typeof ElectronChannel>[1], deadline = 1000, events: readonly string[] = [], retainEffectFailures = false) {
  const inbound = new PassThrough(), outbound = new PassThrough();
  const stream = Duplex.from({readable: inbound, writable: outbound});
  const frames: Record<string, unknown>[] = [];
  const decoder = new FrameDecoder();
  outbound.on('data', chunk => decoder.push(chunk, body => frames.push(JSON.parse(body.toString()))));
  const channel = new ElectronChannel(stream, dispatch, deadline, events, retainEffectFailures);
  return {channel, frames, send(value: unknown) { inbound.write(frame(value)); }};
}
async function settle() { await new Promise(resolve => setImmediate(resolve)); }
test('persistent native refusal does not terminate consumer state or command transport', async () => {
  const p = pair(async () => ({alive: true}), 1000, ['state'], true);
  try {
    const effect = p.channel.apply({kind: 'webContents', windowId: 1, action: 'reload'});
    const rejected = assert.rejects(effect, /detached/);
    await settle(); p.send({ack: 1, error: 'Window detached'}); await rejected;
    await p.channel.publish('state', {phase: 'running'});
    p.send({id: 1, method: 'status'}); await settle();
    assert.deepEqual(p.frames.at(-1), {id: 1, result: {alive: true}});
  } finally { p.channel.close(); }
});
test('persistent timeout fences one late ACK without completing a newer effect', async () => {
  const p = pair(async () => ({}), 40, ['state'], true);
  try {
    await assert.rejects(p.channel.apply({kind: 'app.quit'}), /deadline/);
    const next = p.channel.apply({kind: 'app.quit'});
    p.send({ack: 1}); await settle();
    p.send({ack: 2}); await next;
    await p.channel.publish('state', {alive: true});
  } finally { p.channel.close(); }
});
test('declared persistent consumer state coexists with a pending native effect', async () => {
  const p = pair(async () => ({}), 1000, ['state']);
  try {
    const effect = p.channel.apply({kind: 'webContents', windowId: 1, action: 'reload'});
    await p.channel.publish('state', {phase: 'running'});
    await settle();
    assert.equal(p.frames[0]?.event, 'electron.effect');
    assert.deepEqual(p.frames[1], {event: 'state', data: {phase: 'running'}});
    await assert.rejects(p.channel.publish('other', {}), /Undeclared/);
    await assert.rejects(p.channel.publish('electron.effect', {}), /Undeclared/);
    p.send({ack: 1}); await effect;
    await p.channel.publish('state', {phase: 'idle'});
  } finally { p.channel.close(); }
});
test('native ACK remains readable while a command awaits an effect, with no deadlock', async () => {
  let channel!: ElectronChannel;
  const p = pair(async () => { await channel.apply({kind: 'app.quit'}); return {done: true}; }); channel = p.channel;
  try {
    p.send({id: 1, method: 'electron.start'}); await settle();
    assert.equal(p.frames[0]?.event, 'electron.effect'); assert.equal(p.frames.length, 1);
    p.send({id: 2, method: 'electron.start'}); await settle();
    assert.match(String(p.frames[1]?.error), /busy/);
    p.send({ack: 1}); await settle();
    assert.deepEqual(p.frames[2], {id: 1, result: {done: true}});
  } finally { channel.close(); }
});
test('negative ACK is an actual failure and closes the channel', async () => {
  const p = pair(async () => ({}));
  const pending = p.channel.apply({kind: 'app.quit'});
  const rejected = assert.rejects(pending, /Renderer lost/);
  await settle(); p.send({ack: 1, error: 'Renderer lost'}); await rejected;
  await assert.rejects(p.channel.apply({kind: 'app.quit'}), /unavailable/);
});
test('duplicate success ACK is rejected rather than completing another effect', async () => {
  const p = pair(async () => ({}));
  const pending = p.channel.apply({kind: 'app.quit'});
  await settle(); p.send({ack: 1}); await pending;
  p.send({ack: 1}); await settle();
  await assert.rejects(p.channel.apply({kind: 'app.quit'}), /unavailable/);
});
test('effect deadline and disconnect reject retained waiter', async () => {
  const p = pair(async () => ({}), 20);
  await assert.rejects(p.channel.apply({kind: 'app.quit'}), /deadline/);
  const other = pair(async () => ({}));
  const pending = other.channel.apply({kind: 'app.quit'});
  const rejected = assert.rejects(pending, /closed/); other.channel.close(); await rejected;
});
test('unsolicited or malformed ACK fails closed', async () => {
  for (const ack of [{ack: 1}, {ack: 1, error: false}, {ack: 1, extra: true}]) {
    const p = pair(async () => ({})); p.send(ack); await settle();
    await assert.rejects(p.channel.apply({kind: 'app.quit'}), /unavailable/);
  }
});
