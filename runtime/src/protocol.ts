export const MAX_FRAME = 65536;
export type JsonValue = string | number | boolean | null | JsonValue[] | {[key: string]: JsonValue};
export type Parameters = {[key: string]: JsonValue};
export type Request = {id: number; method: string; params?: Parameters};
export type Reply = {id: number; result: Record<string, unknown>} | {id: number; error: string};
export type Event = {event: string; data: Record<string, unknown>};
export type Frame = Reply | Event;

/** Bound DTO shape independently of its byte limit; never forward arbitrary objects. */
export function parameters(value: unknown): Parameters {
  let nodes = 0;
  const copy = (item: unknown, depth: number): JsonValue => {
    if (++nodes > 4096 || depth > 32) throw new Error('Parameter shape exceeded');
    if (item === null || typeof item === 'string' || typeof item === 'boolean') return item;
    if (typeof item === 'number' && Number.isFinite(item)) return item;
    if (Array.isArray(item)) return item.map(child => copy(child, depth + 1));
    if (item && typeof item === 'object' &&
        (Object.getPrototypeOf(item) === Object.prototype || Object.getPrototypeOf(item) === null)) {
      const output: Parameters = Object.create(null) as Parameters;
      for (const [key, child] of Object.entries(item)) output[key] = copy(child, depth + 1);
      return output;
    }
    throw new Error('Invalid JSON parameter');
  };
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Parameters must be an object');
  const result = copy(value, 0) as Parameters;
  if (Buffer.byteLength(JSON.stringify(result)) > MAX_FRAME) throw new Error('Parameters exceed byte limit');
  return result;
}

export function decodeRequest(body: Uint8Array): Request {
  const value: unknown = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(body));
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid request');
  const record = value as Record<string, unknown>;
  if (!Number.isSafeInteger(record.id) || typeof record.id !== 'number' || record.id < 1 || record.id > 2147483647 ||
      typeof record.method !== 'string' || !/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/.test(record.method) ||
      Object.keys(record).some(key => !['id', 'method', 'params'].includes(key))) throw new Error('Invalid request');
  return {id: record.id, method: record.method,
    ...(Object.hasOwn(record, 'params') ? {params: parameters(record.params)} : {})};
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
