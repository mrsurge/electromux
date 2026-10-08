import {Socket} from 'node:net';
import {isAbsolute} from 'node:path';
import {decodeRequest, FrameDecoder, type Request} from './protocol.js';
import {FrameWriter} from './frame-writer.js';
import {ConsumerHost} from './consumer-host.js';
import {proofFactory} from './proof-consumer.js';

const fd = Number(process.argv[2]);
const root = process.argv[3];
const mode = process.argv[4] || 'standalone';
if (!Number.isInteger(fd) || fd < 3 || !root || !isAbsolute(root)) throw new Error('Invalid native startup declaration');
const channel = new Socket({fd, readable: true, writable: true});
const writer = new FrameWriter(channel);
const send = writer.send.bind(writer);
const host = new ConsumerHost(proofFactory(root, mode), async (event, data) => { await send({event, data}); });
channel.once('close', () => { void host.dispose().catch(error => console.error('Consumer cleanup failed:', error)); });
channel.on('error', error => { console.error('Electromux channel failed:', error.message); });
const decoder = new FrameDecoder();
// One bounded native request lane; overload is rejected, never queued/replayed.
let partialDeadline: ReturnType<typeof setTimeout> | undefined;
try {
  await host.ready();
  await send({event: 'runtime.ready', data: {version: 1, node: process.version}});
  for await (const chunk of channel) {
    if (!Buffer.isBuffer(chunk)) throw new Error('Nonbinary transport');
    if (partialDeadline) clearTimeout(partialDeadline);
    const requests: Request[] = [];
    decoder.push(chunk, body => {
      if (requests.length) throw new Error('Concurrent request rejected');
      requests.push(decodeRequest(body));
    });
    const request = requests[0];
    if (request) {
      let reply: import('./protocol.js').Reply;
      try { reply = {id: request.id, result: await host.dispatch(request.method)}; }
      catch (error) { reply = {id: request.id, error: error instanceof Error ? error.message : 'Handler failed'}; }
      await send(reply);
      await send({event: 'sample.updated', data: {requestId: request.id, method: request.method}});
    }
    // finish() only tests whether a partial frame is retained.
    try { decoder.finish(); }
    catch { partialDeadline = setTimeout(() => channel.destroy(new Error('Partial frame deadline exceeded')), 5000); }
  }
  decoder.finish();
} finally {
  if (partialDeadline) clearTimeout(partialDeadline);
  channel.destroy();
  await host.dispose();
  // No process.exit(): this engine runs inside an Android service process.
}
