import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Duplex, PassThrough} from 'node:stream';
import {ElectronChannel} from '../src/electron-channel.js';
import {FrameDecoder} from '../src/protocol.js';

function frame(value: unknown): Buffer {
  const body = Buffer.from(JSON.stringify(value)), header = Buffer.alloc(4);
  header.writeUInt32BE(body.length); return Buffer.concat([header, body]);
}
function pair(dispatch: ConstructorParameters<typeof ElectronChannel>[1], deadline = 1000) {
  const inbound = new PassThrough(), outbound = new PassThrough();
  const stream = Duplex.from({readable: inbound, writable: outbound});
  const frames: Record<string, unknown>[] = [];
  const decoder = new FrameDecoder();
  outbound.on('data', chunk => decoder.push(chunk, body => frames.push(JSON.parse(body.toString()))));
  const channel = new ElectronChannel(stream, dispatch, deadline);
  return {channel, frames, send(value: unknown) { inbound.write(frame(value)); }};
}
async function settle() { await new Promise(resolve => setImmediate(resolve)); }
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
