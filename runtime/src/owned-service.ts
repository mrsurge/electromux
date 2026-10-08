import {spawn, type ChildProcess} from 'node:child_process';
import {Readable} from 'node:stream';
import type {ChildSpec} from './child-proof.js';
import {OutputPump, type OutputSink} from './output-pump.js';

export type ServicePolicy = {
  readinessMs: number | null; // null is an explicitly cancellable build wait.
  stopGraceMs: number;
  output: OutputSink;
};

export type ServiceState = {state: 'idle' | 'starting' | 'ready' | 'exited'; pid: number | null;
  code: number | null; signal: string | null; error: string | null};

/** Long-lived owned group. Consumer supplies semantic readiness, never the renderer. */
export class OwnedService {
  private child: ChildProcess | undefined;
  private state: ServiceState = {state: 'idle', pid: null, code: null, signal: null, error: null};
  private closed = false;
  private settled: Promise<void> = Promise.resolve();
  private readonly policy: ServicePolicy;
  private output: OutputPump;
  constructor(private readonly changed: (state: ServiceState) => void,
      policy: Partial<ServicePolicy> = {}) {
    this.policy = {readinessMs: 3000, stopGraceMs: 250, output: async () => {}, ...policy};
    for (const limit of [this.policy.readinessMs, this.policy.stopGraceMs]) {
      if (limit !== null && (!Number.isSafeInteger(limit) || limit < 1 || limit > 2147483647))
        throw new Error('Invalid native service policy');
    }
    this.output = new OutputPump(this.policy.output);
  }
  outputTail(lane: 'stdout' | 'stderr'): string { return this.output.tail(lane); }
  snapshot(): ServiceState { return {...this.state}; }
  async start(spec: ChildSpec, readyToken: (pid: number) => string): Promise<ServiceState> {
    if (this.closed || this.child) throw new Error('Owned service unavailable');
    this.output = new OutputPump(this.policy.output);
    const child = spawn(spec.executable, [...spec.args], {cwd: spec.cwd, env: {...spec.env},
      detached: true, stdio: ['ignore', 'pipe', 'pipe', 'pipe']});
    this.child = child;
    this.state = {state: 'starting', pid: child.pid || null, code: null, signal: null, error: null};
    this.changed(this.snapshot());
    let ready = false, readiness = '';
    let failure: Error | undefined;
    let resolveReady!: () => void, rejectReady!: (error: Error) => void;
    const barrier = new Promise<void>((resolve, reject) => { resolveReady = resolve; rejectReady = reject; });
    const fail = (error: Error): void => {
      failure ||= error; rejectReady(error); this.kill('SIGKILL');
    };
    const timer = this.policy.readinessMs === null ? undefined :
      setTimeout(() => fail(new Error('Owned service readiness deadline exceeded')), this.policy.readinessMs);
    const observe = (stream: Readable | null, lane: string): void => {
      if (!stream) { fail(new Error(`Missing service ${lane}`)); return; }
      stream.on('error', fail);
      stream.on('data', (bytes: Buffer) => {
        if (lane === 'stdout' || lane === 'stderr') {
          void this.output.push(lane, bytes).catch(fail); return;
        }
        if (ready) { fail(new Error('Duplicate service readiness')); return; }
        if (Buffer.byteLength(readiness) + bytes.length > 65536) { fail(new Error('Service readiness frame exceeded')); return; }
        readiness += bytes.toString('utf8');
        if (!readiness.includes('\n')) return;
        if (!child.pid || readiness !== readyToken(child.pid)) { fail(new Error('Invalid service readiness')); return; }
        ready = true; if (timer) clearTimeout(timer);
        this.state = {...this.state, state: 'ready'};
        this.changed(this.snapshot()); resolveReady();
      });
    };
    this.settled = new Promise<void>(resolve => {
      child.once('close', (code, signal) => { void (async () => {
        if (timer) clearTimeout(timer);
        // Reap any remaining descendants even if the leader exited normally.
        this.kill('SIGKILL');
        try { await this.output.drain(); }
        catch (error) { failure ||= error instanceof Error ? error : new Error('Output drain failed'); }
        this.child = undefined;
        this.state = {...this.state, state: 'exited', code, signal, error: failure?.message || null};
        this.changed(this.snapshot());
        if (!ready) rejectReady(failure || new Error('Owned service exited before readiness'));
        resolve();
      })(); });
    });
    child.once('error', fail);
    observe(child.stdout, 'stdout'); observe(child.stderr, 'stderr');
    observe(child.stdio[3] instanceof Readable ? child.stdio[3] : null, 'readiness');
    try { await barrier; return this.snapshot(); }
    catch (error) { await this.settled; throw error; }
  }
  private kill(signal: NodeJS.Signals): void {
    const pid = this.child?.pid;
    if (!pid) return;
    try { process.kill(-pid, signal); }
    catch (error) {
      if (!(error instanceof Error && 'code' in error && error.code === 'ESRCH')) {
        this.state = {...this.state, error: error instanceof Error ? error.message : 'Group termination failed'};
      }
    }
  }
  async stop(): Promise<ServiceState> {
    if (!this.child) return this.snapshot();
    this.kill('SIGTERM');
    const escalation = setTimeout(() => this.kill('SIGKILL'), this.policy.stopGraceMs);
    try { await this.settled; return this.snapshot(); }
    finally { clearTimeout(escalation); }
  }
  async dispose(): Promise<void> { this.closed = true; await this.stop(); }
}
