import {handle} from './handlers.js';
import type {ConsumerFactory} from './consumer-host.js';
import {OwnedChildSupervisor} from './child-supervisor.js';
import {OwnedService} from './owned-service.js';

/** Registry is compiled into the host, never supplied by a renderer. */
export function proofFactory(root: string, mode: string): ConsumerFactory {
  if (mode !== 'standalone' && mode !== 'termux') throw new Error('Unknown native consumer');
  const termux = mode === 'termux';
  return async (signal, emit) => {
    const children = new OwnedChildSupervisor();
    const service = new OwnedService(state => {
      if (!signal.aborted) void emit('child.state', state).catch(() => { void service.dispose(); });
    }, {readinessMs: null, stopGraceMs: 1000,
      output: async (lane, bytes) => {
        if (!signal.aborted) await emit('child.output', {lane, text: bytes.toString('utf8')});
      }});
    const abort = (): void => { void children.dispose(); void service.dispose(); };
    signal.addEventListener('abort', abort, {once: true});
    if (signal.aborted) abort();
    return {
    events: ['child.state', 'child.output'],
    methods: termux ? ['ping', 'file.proof', 'child.proof', 'child.cancelProof', 'child.start', 'child.status', 'child.stop'] : ['ping', 'file.proof'],
    async dispatch(method) {
      if (method === 'child.status') return service.snapshot();
      if (method === 'child.stop') return service.stop();
      if (method === 'child.start') {
        if (!termux || process.platform !== 'android') throw new Error('Termux service proof not authorized');
        const prefix = '/data/data/com.termux/files/usr', home = '/data/data/com.termux/files/home';
        return service.start({executable: `${prefix}/bin/bash`, cwd: home,
          args: ['--noprofile', '--norc', '-c', `printf 'ready:%s\\n' "$$" >&3; printf 'service-output-proof\\n'; exec ${prefix}/bin/sleep 30`],
          env: {HOME: home, PREFIX: prefix, TMPDIR: `${prefix}/tmp`, TERM: 'xterm-256color',
            PATH: `${prefix}/bin:/system/bin`, LD_PRELOAD: `${prefix}/lib/libtermux-exec.so`}}, pid => `ready:${pid}\n`);
      }
      const request = {id: 1, method: method as Parameters<typeof handle>[0]['method']};
      const reply = method.startsWith('child.') ?
        await children.run(childSignal => handle(request, root, termux, childSignal)) :
        await handle(request, root, termux, signal);
      if ('error' in reply) throw new Error(reply.error);
      return reply.result;
    },
    async dispose() { signal.removeEventListener('abort', abort); await Promise.all([children.dispose(), service.dispose()]); }
    };
  };
}
