import {type Duplex} from 'node:stream';
import {FrameDecoder, decodeRequest, type Parameters, type Reply} from './protocol.js';
import {FrameWriter} from './frame-writer.js';
import {type NativeEffect} from './electron-compat.js';

class NativeEffectFailure extends Error {}

/** Opt-in Electron driver. The accepted serial ConsumerHost protocol is unchanged. */
export class ElectronChannel {
  private readonly writer: FrameWriter;
  private readonly decoder = new FrameDecoder();
  private sequence = 0;
  private busy = false;
  private closed = false;
  private partial: ReturnType<typeof setTimeout> | undefined;
  private readonly retiredAcks = new Set<number>();
  private pending: {id: number; resolve(): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout>} | undefined;
  constructor(private readonly stream: Duplex,
      private readonly dispatch: (method: string, params?: Parameters) => Promise<Record<string, unknown>>,
      private readonly deadline = 5000,
      private readonly events: readonly string[] = [],
      private readonly retainEffectFailures = false) {
    this.writer = new FrameWriter(stream, deadline);
    stream.on('data', (chunk: Buffer) => {
      try {
        if (!Buffer.isBuffer(chunk)) throw new Error('Nonbinary Electron channel');
        if (this.partial) clearTimeout(this.partial);
        this.decoder.push(chunk, body => this.consume(body));
        try { this.decoder.finish(); }
        catch { this.partial = setTimeout(() => this.close(new Error('Partial Electron frame deadline exceeded')), deadline); }
      } catch (error) { this.close(error instanceof Error ? error : new Error(String(error))); }
    });
    stream.once('error', error => this.close(error));
    stream.once('end', () => {
      try { this.decoder.finish(); this.close(new Error('Electron channel ended')); }
      catch { this.close(new Error('Truncated Electron frame')); }
    });
    stream.once('close', () => this.close(new Error('Electron channel closed')));
  }
  async ready(): Promise<void> {
    await this.writer.send({event: 'runtime.ready', data: {version: 1, node: process.version}});
  }
  /** Opt-in consumer state shares the bounded writer, not the effect ACK lane. */
  publish(name: string, data: Record<string, unknown>): Promise<void> {
    if (this.closed || name === 'electron.effect' || name === 'runtime.ready' || !this.events.includes(name))
      return Promise.reject(new Error('Undeclared or unavailable consumer event'));
    return this.writer.send({event: name, data});
  }
  async apply(effect: NativeEffect): Promise<void> {
    if (this.closed || this.pending) throw new Error('Electron effect lane unavailable');
    const id = ++this.sequence;
    if (!Number.isSafeInteger(id) || id > 2147483647) throw new Error('Electron effect IDs exhausted');
    const completion = new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        const error = new NativeEffectFailure('Native effect acknowledgement deadline exceeded; no replay');
        if (!this.retainEffectFailures || this.retiredAcks.size >= 16) { this.close(error); return; }
        this.pending = undefined;
        this.retiredAcks.add(id);
        reject(error);
      }, this.deadline);
      this.pending = {id, resolve, reject, timer};
    });
    // Install the waiter before emitting. Observe it even when the writer fails.
    const sent = this.writer.send({event: 'electron.effect', data: {effectId: id, effect}});
    try { await Promise.all([sent, completion]); }
    catch (error) {
      // A valid native refusal/detachment is not failure of a persistent backend.
      if (!this.retainEffectFailures || !(error instanceof NativeEffectFailure))
        this.close(error instanceof Error ? error : new Error(String(error)));
      throw error;
    }
  }
  private consume(body: Buffer): void {
    const value: unknown = JSON.parse(new TextDecoder('utf-8', {fatal: true}).decode(body));
    if (value && typeof value === 'object' && !Array.isArray(value) && Object.hasOwn(value, 'ack')) {
      const ack = value as Record<string, unknown>;
      if (Object.keys(ack).some(key => !['ack', 'error'].includes(key)) ||
          !Number.isInteger(ack.ack) || (Object.hasOwn(ack, 'error') &&
          (typeof ack.error !== 'string' || !ack.error.length || ack.error.length > 512))) throw new Error('Invalid native acknowledgement');
      const pending = this.pending;
      if (typeof ack.ack === 'number' && this.retiredAcks.delete(ack.ack)) return;
      if (!pending || pending.id !== ack.ack) throw new Error('Stale native acknowledgement');
      clearTimeout(pending.timer); this.pending = undefined;
      if (typeof ack.error === 'string') pending.reject(new NativeEffectFailure(ack.error)); else pending.resolve();
      return;
    }
    const request = decodeRequest(body);
    if (this.busy) {
      void this.writer.send({id: request.id, error: 'Electron command lane busy; no replay'}).catch(error => this.close(error));
      return;
    }
    this.busy = true;
    // Do not await here: the reader must keep consuming native acknowledgements.
    void (async () => {
      let reply: Reply;
      try { reply = {id: request.id, result: await this.dispatch(request.method, request.params)}; }
      catch (error) { reply = {id: request.id, error: error instanceof Error ? error.message : 'Electron command failed'}; }
      if (!this.closed) await this.writer.send(reply);
    })().catch(error => this.close(error)).finally(() => { this.busy = false; });
  }
  close(error = new Error('Electron owner closed')): void {
    if (this.closed) return;
    this.closed = true;
    this.retiredAcks.clear();
    if (this.partial) clearTimeout(this.partial);
    if (this.pending) { clearTimeout(this.pending.timer); this.pending.reject(error); this.pending = undefined; }
    this.stream.destroy();
  }
}
