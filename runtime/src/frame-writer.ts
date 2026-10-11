import type {Writable} from 'node:stream';
import {encodeFrame, type Frame} from './protocol.js';

/** One bounded writer for replies and unsolicited events; failure is terminal. */
export class FrameWriter {
  private pending = 0;
  private tail: Promise<void> = Promise.resolve();
  private failure: Error | undefined;
  constructor(private readonly stream: Writable, private readonly deadline = 5000) {}
  send(frame: Frame): Promise<void> {
    if (this.failure) return Promise.reject(this.failure);
    if (this.pending >= 16) {
      const error = new Error('Outbound frame queue exceeded');
      this.failure = error; this.stream.destroy(error);
      return Promise.reject(error);
    }
    const bytes = encodeFrame(frame);
    this.pending++;
    const operation = this.tail.then(() => {
      if (this.failure) throw this.failure;
      return new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => {
          const error = new Error('Write deadline exceeded');
          this.stream.destroy(error); reject(error);
        }, this.deadline);
        this.stream.write(bytes, error => { clearTimeout(timer); if (error) reject(error); else resolve(); });
      });
    }).catch((error: unknown) => {
      this.failure ||= error instanceof Error ? error : new Error('Frame write failed');
      throw this.failure;
    }).finally(() => { this.pending--; });
    this.tail = operation.catch(() => {});
    return operation;
  }
}
