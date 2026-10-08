import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {Duplex} from 'node:stream';
import {FrameDecoder} from '../src/protocol.js';

test('built runtime handles sequential FD requests and emits events without stdout protocol', {timeout: 10000}, async () => {
  const root = await mkdtemp(join(tmpdir(), 'electromux-process-'));
  const child = spawn(process.execPath, ['dist/main.mjs', '3', root], {stdio: ['ignore', 'pipe', 'pipe', 'pipe']});
  const channel = child.stdio[3];
  assert.ok(channel instanceof Duplex);
  const frames: Record<string, unknown>[] = [];
  let stdout = '', stderr = '';
  child.stdout?.on('data', chunk => { stdout += String(chunk); });
  child.stderr?.on('data', chunk => { stderr += String(chunk); });
  const decoder = new FrameDecoder();
  channel.on('data', (chunk: Buffer) => decoder.push(chunk, body => {
    const frame: unknown = JSON.parse(body.toString());
    assert.ok(typeof frame === 'object' && frame !== null);
    frames.push(frame as Record<string, unknown>);
  }));
  async function waitFor(predicate: () => boolean): Promise<void> {
    const deadline = Date.now() + 5000;
    while (!predicate()) {
      assert.ok(Date.now() < deadline, `IPC timeout: ${stderr}`);
      await new Promise(resolve => setTimeout(resolve, 5));
    }
  }
  try {
    await waitFor(() => frames.some(frame => frame['event'] === 'runtime.ready'));
    for (let id = 1; id <= 30; id++) {
      const body = Buffer.from(JSON.stringify({id, method: id === 30 ? 'file.proof' : 'ping'}));
      const header = Buffer.alloc(4); header.writeUInt32BE(body.length);
      channel.write(Buffer.concat([header, body]));
      await waitFor(() => frames.some(frame => frame['id'] === id));
    }
    await waitFor(() => frames.filter(frame => frame['event'] === 'sample.updated').length === 30);
    const exit = once(child, 'exit'); channel.end();
    const [code] = await exit;
    assert.equal(code, 0, stderr); assert.equal(stdout, '');
    decoder.finish();
  } finally { child.kill(); await rm(root, {recursive: true}); }
});
