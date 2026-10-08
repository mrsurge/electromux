export const MAX_FRAME = 65536;
export type Request = {id: number; method: 'ping' | 'file.proof' | 'child.proof' | 'child.cancelProof' | 'child.start' | 'child.status' | 'child.stop'};
export type Reply = {id: number; result: Record<string, unknown>} | {id: number; error: string};
export type Event = {event: string; data: Record<string, unknown>};
export type Frame = Reply | Event;

export function decodeRequest(body: Uint8Array): Request {
  const value: unknown = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(body));
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid request');
  const record = value as Record<string, unknown>;
  if (!Number.isSafeInteger(record.id) || typeof record.id !== 'number' || record.id < 1 || record.id > 2147483647 ||
      !['ping', 'file.proof', 'child.proof', 'child.cancelProof', 'child.start', 'child.status', 'child.stop'].includes(String(record.method)) ||
      Object.keys(record).some(key => key !== 'id' && key !== 'method')) throw new Error('Invalid request');
  return {id: record.id, method: record.method as Request['method']};
}

export function encodeFrame(frame: Frame): Buffer {
  const body = Buffer.from(JSON.stringify(frame));
  if (!body.length || body.length > MAX_FRAME) throw new Error('Frame exceeds limit');
  const header = Buffer.alloc(4); header.writeUInt32BE(body.length);
  return Buffer.concat([header, body]);
}

/** Bounded retained tail, not an unbounded application message queue. */
export class FrameDecoder {
  private tail = Buffer.alloc(0);
  push(chunk: Buffer, consume: (body: Buffer) => void): void {
    let offset = 0;
    while (offset < chunk.length) {
      const needed = this.tail.length < 4 ? 4 - this.tail.length :
        this.tail.readUInt32BE(0) + 4 - this.tail.length;
      const count = Math.min(needed, chunk.length - offset);
      this.tail = Buffer.concat([this.tail, chunk.subarray(offset, offset + count)]);
      offset += count;
      if (this.tail.length >= 4) {
        const length = this.tail.readUInt32BE(0);
        if (!length || length > MAX_FRAME) throw new Error('Invalid frame length');
        if (this.tail.length === length + 4) {
          const body = this.tail.subarray(4); this.tail = Buffer.alloc(0); consume(body);
        }
      }
    }
  }
  finish(): void { if (this.tail.length) throw new Error('Truncated frame'); }
}
