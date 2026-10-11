import {spawn, type ChildProcess} from 'node:child_process';
import {Readable} from 'node:stream';

type ChildResult = {
  pid: number; code: number | null; signal: string | null;
  stdout: string; stderr: string; readiness: string; cancelled: boolean; groupGone: boolean;
};
export type ChildSpec = {executable: string; args: string[]; cwd: string; env: NodeJS.ProcessEnv};

/** Fixed proof uses a private process group, bounded output/deadline, no replay. */
export function observeChild(spec: ChildSpec, cancel: boolean, signal?: AbortSignal): Promise<ChildResult> {
  if (signal?.aborted) return Promise.reject(new Error('Child owner closed'));
  return new Promise((resolve, reject) => {
    let child: ChildProcess;
    try { child = spawn(spec.executable, spec.args, {
      cwd: spec.cwd, env: spec.env, detached: true, stdio: ['ignore', 'pipe', 'pipe', 'pipe']
    }); } catch (error) { reject(error); return; }
    const pid = child.pid;
    let stdout = '', stderr = '', readiness = '', failure: Error | undefined;
    let cancelled = false;
    const killGroup = (signal: NodeJS.Signals): void => {
      if (!pid) return;
      try { process.kill(-pid, signal); }
      catch (error) {
        if (!(error instanceof Error && 'code' in error && error.code === 'ESRCH'))
          failure ||= error instanceof Error ? error : new Error('Group cancellation failed');
      }
    };
    const fail = (error: Error): void => { failure ||= error; killGroup('SIGKILL'); };
    const abort = (): void => fail(new Error('Child owner closed'));
    signal?.addEventListener('abort', abort, {once: true});
    if (signal?.aborted) abort();
    const deadline = setTimeout(() => fail(new Error('Child proof deadline exceeded')), 3000);
    const collect = (stream: Readable | null | undefined, lane: 'stdout' | 'stderr' | 'readiness'): void => {
      if (!stream) { fail(new Error(`Missing ${lane} pipe`)); return; }
      stream.on('error', fail);
      stream.on('data', (bytes: Buffer) => {
        const previous = lane === 'stdout' ? stdout : lane === 'stderr' ? stderr : readiness;
        if (Buffer.byteLength(previous) + bytes.length > 8192) { fail(new Error('Child output limit exceeded')); return; }
        const text = previous + bytes.toString('utf8');
        if (lane === 'stdout') stdout = text;
        else if (lane === 'stderr') stderr = text;
        else {
          readiness = text;
          if (cancel && !cancelled && text.includes('\n')) {
            if (text !== `ready:${pid}\n`) { fail(new Error('Unexpected child FD3 readiness')); return; }
            cancelled = true; killGroup('SIGTERM');
          }
        }
      });
    };
    collect(child.stdout, 'stdout'); collect(child.stderr, 'stderr');
    const extra = child.stdio[3];
    collect(extra instanceof Readable ? extra : null, 'readiness');
    child.on('error', fail);
    child.on('close', (code, exitSignal) => {
      clearTimeout(deadline);
      signal?.removeEventListener('abort', abort);
      void (async () => {
        let groupGone = false;
        if (pid) {
          for (let attempt = 0; attempt < 20; attempt++) {
            try { process.kill(-pid, 0); }
            catch (error) {
              if (error instanceof Error && 'code' in error && error.code === 'ESRCH') groupGone = true;
              else failure ||= error instanceof Error ? error : new Error('Group inspection failed');
              break;
            }
            await new Promise(done => setTimeout(done, 10));
          }
          // Never leave a known owned group running after the proof.
          if (!groupGone) killGroup('SIGKILL');
        }
        if (failure || !pid) { reject(failure || new Error('Child failed to spawn')); return; }
        if (readiness !== `ready:${pid}\n`) { reject(new Error('Child FD3 readiness missing')); return; }
        resolve({pid, code, signal: exitSignal, stdout, stderr, readiness, cancelled, groupGone});
      })().catch(reject);
    });
  });
}

/** No renderer-supplied executable, arguments, environment or paths. */
export async function runChildProof(cancel: boolean, signal?: AbortSignal): Promise<ChildResult> {
  const prefix = '/data/data/com.termux/files/usr';
  const home = '/data/data/com.termux/files/home';
  const script = cancel ?
    `${prefix}/bin/sleep 30 &\nchild=$!\nprintf 'descendant=%s\\n' "$child"\nprintf 'ready:%s\\n' "$$" >&3\nwait "$child"` :
    `printf 'uid=%s\\nHOME=%s\\nPREFIX=%s\\nTERM=%s\\ncwd=%s\\n' "$(${prefix}/bin/id -u)" "$HOME" "$PREFIX" "$TERM" "$PWD"\nprintf 'stderr-proof\\n' >&2\nprintf 'ready:%s\\n' "$$" >&3\nexit 7`;
  return observeChild({executable: `${prefix}/bin/bash`, args: ['--noprofile', '--norc', '-c', script], cwd: home,
    env: {HOME: home, PREFIX: prefix, TMPDIR: `${prefix}/tmp`, TERM: 'xterm-256color', LANG: 'C.UTF-8',
      PATH: `${prefix}/bin:${prefix}/bin/applets:/system/bin`,
      LD_PRELOAD: `${prefix}/lib/libtermux-exec.so`}}, cancel, signal);
}
