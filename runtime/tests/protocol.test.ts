import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {decodeRequest, FrameDecoder, encodeFrame} from '../src/protocol.js';
import {handle} from '../src/handlers.js';

test('fragmented and coalesced frames preserve order', () => {
  const packet = (id: number) => {const body = Buffer.from(JSON.stringify({id, method: 'ping'}));
    const header = Buffer.alloc(4); header.writeUInt32BE(body.length); return Buffer.concat([header, body]);};
  const decoder = new FrameDecoder(), received: number[] = [];
  const bytes = Buffer.concat([packet(1), packet(2)]);
  for (const byte of bytes) decoder.push(Buffer.from([byte]), body => received.push(decodeRequest(body).id));
  decoder.finish(); assert.deepEqual(received, [1, 2]);
});
test('rejects oversized, truncated, malformed UTF8 and unapproved methods', () => {
  const decoder = new FrameDecoder();
  assert.throws(() => decoder.push(Buffer.from([0, 1, 0, 1]), () => {}));
  const partial = new FrameDecoder(); partial.push(Buffer.from([0]), () => {});
  assert.throws(() => partial.finish());
  for (const text of ['{"id":1,"method":"eval"}', '{"id":1,"method":"ping","code":"bad"}', '{"id":-1,"method":"ping"}'])
    assert.throws(() => decodeRequest(Buffer.from(text)));
  assert.throws(() => decodeRequest(Buffer.from([0xff])));
  assert.throws(() => encodeFrame({id: 1, result: {text: 'x'.repeat(65536)}}));
});
test('typed handlers provide ping and bounded-root filesystem proof', async () => {
  const root = await mkdtemp(join(tmpdir(), 'electromux-proof-'));
  try {
    const ping = await handle({id: 1, method: 'ping'}, root);
    assert.ok('result' in ping && typeof ping.result['node'] === 'string');
    const file = await handle({id: 2, method: 'file.proof'}, root);
    assert.ok('result' in file && file.result['content'] === 'Electromux embedded Node filesystem proof\n');
  } finally { await rm(root, {recursive: true}); }
});
