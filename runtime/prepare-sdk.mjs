import {readFile, mkdir, mkdtemp, writeFile, rm} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {resolve, join} from 'node:path';

const destination = process.argv[2];
if (!destination) throw new Error('Usage: node prepare-sdk.mjs <SDK-output-directory>');
const lock = JSON.parse(await readFile(new URL('./node-sdk.json', import.meta.url), 'utf8'));
const scratch = process.env['TMPDIR'] || resolve('.codex-scratch');
await mkdir(scratch, {recursive: true});
const temporary = await mkdtemp(join(scratch, 'electromux-sdk-'));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
try {
  const response = await fetch(lock.url);
  if (!response.ok) throw new Error(`SDK download failed: ${response.status}`);
  const archive = Buffer.from(await response.arrayBuffer());
  if (sha(archive) !== lock.archiveSha256) throw new Error('SDK archive checksum mismatch');
  const path = join(temporary, 'sdk.zip'); await writeFile(path, archive);
  const output = resolve(destination); await mkdir(output, {recursive: true});
  execFileSync('unzip', ['-q', '-o', path, 'include/*', 'bin/arm64-v8a/*', '-d', output]);
  if (sha(await readFile(join(output, 'bin/arm64-v8a/libnode.so'))) !== lock.arm64LibrarySha256)
    throw new Error('ARM64 library checksum mismatch');
  console.log(`Verified ${lock.release} ARM64 SDK: ${output}`);
} finally { await rm(temporary, {recursive: true, force: true}); }
