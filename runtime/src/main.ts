import {Socket} from 'node:net';
import {once} from 'node:events';
import {isAbsolute} from 'node:path';
import {decodeRequest, encodeFrame, FrameDecoder, type Frame, type Request} from './protocol.js';
import {handle} from './handlers.js';

const fd = Number(process.argv[2]);
const root = process.argv[3];
if (!Number.isInteger(fd) || fd < 3 || !root || !isAbsolute(root)) throw new Error('Invalid native startup declaration');
const channel = new Socket({fd, readable: true, writable: true});
channel.on('error', error => { console.error('Electromux channel failed:', error.message); });
async function send(frame: Frame): Promise<void> {
  if (!channel.write(encodeFrame(frame))) await once(channel, 'drain');
}
await send({event: 'runtime.ready', data: {version: 1, node: process.version}});
const decoder = new FrameDecoder();
// One bounded native request lane; overload is rejected, never queued/replayed.
let partialDeadline: ReturnType<typeof setTimeout> | undefined;
try {
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
      let reply: Frame;
      try { reply = await handle(request, root); }
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
  // No process.exit(): this engine runs inside an Android service process.
}
