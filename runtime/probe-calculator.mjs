import {spawnSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const pin = '1bd90d3adb449a94bdff3dec04d01884fe335546';
const root = process.argv[2];
if (!root || process.argv.length !== 3) throw new Error('Usage: node probe-calculator.mjs <upstream-git-checkout>');
const source = resolve(root);
function git(args) {
  const result = spawnSync('git', ['-C', source, ...args], {encoding: 'utf8'});
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`Source verification failed: git ${args.join(' ')} ${result.stderr}`);
  return result.stdout.trim();
}
if (git(['rev-parse', 'HEAD']) !== pin) throw new Error(`Calculator must be pinned at ${pin}`);
git(['diff', '--exit-code', 'HEAD', '--', 'app']);
const app = resolve(source, 'app');
const metadata = JSON.parse(readFileSync(resolve(app, 'package.json'), 'utf8'));
if (metadata.name !== 'electron-calculator' || metadata.version !== '1.1.4') throw new Error('Unexpected source metadata');
const runtime = fileURLToPath(new URL('.', import.meta.url));
const result = spawnSync(process.execPath, ['--import', 'tsx', '--test', 'tests/electron-main.test.ts'], {
  cwd: runtime, stdio: 'inherit', env: {...process.env, ELECTROMUX_CALCULATOR_APP: app},
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
