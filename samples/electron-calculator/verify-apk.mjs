import {spawnSync} from 'node:child_process';
import {resolve} from 'node:path';

if (process.argv.length !== 3) throw new Error('Usage: node verify-apk.mjs <calculator.apk>');
const apk = resolve(process.argv[2]);
function unzip(args) {
  const result = spawnSync('unzip', args, {encoding: 'utf8', maxBuffer: 8 * 1024 * 1024});
  if (result.status !== 0) throw new Error(result.stderr || 'APK inspection failed');
  return result.stdout;
}
const entries = new Set(unzip(['-Z1', apk]).trim().split('\n'));
const resources = JSON.parse(unzip(['-p', apk, 'assets/calculator-resources.json']));
if (!Array.isArray(resources) || resources.length > 512 || new Set(resources).size !== resources.length)
  throw new Error('Invalid resource inventory');
const required = ['embedded_node/calculator.mjs', 'calculator/index.html',
  'electron_app/app.js', 'electron_app/preload.js',
  'electron_app/node_modules/electron-log/package.json', 'electron_app/node_modules/electron-log/node.js', ...resources];
for (const path of required) {
  if (typeof path !== 'string' || path.startsWith('/') || path.split('/').some(part => !part || part === '.' || part === '..'))
    throw new Error('Invalid resource path');
  if (!entries.has(`assets/${path}`)) throw new Error(`Declared runtime resource missing from APK: ${path}`);
}
console.log(`Verified ${resources.length} declared domain resources and renderer/runtime entrypoints in APK`);
