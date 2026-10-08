import {readFile, writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import type {Request, Reply} from './protocol.js';
import {runChildProof} from './child-proof.js';

/** Sample policy: no arbitrary eval, paths, argv or TE2 imports. */
export async function handle(request: Request, root: string, termux = false, signal?: AbortSignal): Promise<Reply> {
  if (request.method === 'child.proof' || request.method === 'child.cancelProof') {
    if (!termux || process.platform !== 'android') throw new Error('Termux child proof not authorized');
    return {id: request.id, result: await runChildProof(request.method === 'child.cancelProof', signal)};
  }
  if (request.method === 'ping') return {id: request.id, result: {
    node: process.version, mobile: process.versions['mobile'] || 'host-test',
    pid: process.pid, platform: process.platform, arch: process.arch,
    tmpdir: process.env['TMPDIR'] || '', cwd: process.cwd()
  }};
  const path = join(root, 'filesystem-proof.txt');
  await writeFile(path, 'Electromux embedded Node filesystem proof\n', {mode: 0o600});
  return {id: request.id, result: {content: await readFile(path, 'utf8')}};
}
