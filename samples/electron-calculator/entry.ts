import {Socket} from 'node:net';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';
import {createRequire} from 'node:module';
import {ElectronChannel} from '../../runtime/src/electron-channel.js';
import {createElectronMain} from '../../runtime/src/electron-main.js';
import {loadElectronEntry} from '../../runtime/src/electron-loader.js';
import {parseCompatibilityManifest} from '../../runtime/src/electron-manifest.js';

const fd = Number(process.argv[2]), root = process.argv[3];
if (!Number.isInteger(fd) || fd < 3 || !root?.startsWith('/')) throw new Error('Invalid native Electron startup');
const appRoot = join(root, 'electron_app');
const metadata = JSON.parse(readFileSync(join(appRoot, 'package.json'), 'utf8')) as {name: string; version: string};
const manifest = parseCompatibilityManifest(JSON.parse(readFileSync(join(appRoot, 'electromux.json'), 'utf8')));
let started = false;
const channel = new ElectronChannel(new Socket({fd, readable: true, writable: true}), async (method, params) => {
  if (method === 'electron.start') {
    if (started) throw new Error('Electron application already started'); started = true;
    const require = createRequire(join(appRoot, 'package.json'));
    loadElectronEntry(join(appRoot, 'app.js'), appRoot, new Map<string, unknown>([
      ['electron', host.electron], ['electron-log', require('electron-log/node')],
      ['@electron/remote/main', host.remoteMain], ['electron-context-menu', host.contextMenu],
    ]), {electromux: '0.0.1'});
    host.markReady(); await new Promise(resolve => setImmediate(resolve)); await host.owner.flush();
    return {started: true};
  }
  if (method === 'electron.menu.select' && started && typeof params?.itemId === 'string') {
    await host.owner.selectMenuItem(params.itemId); return {selected: true};
  }
  throw new Error('Unsupported Electron command');
}, 5000);
const host = createElectronMain(manifest, {apply: effect => channel.apply(effect),
  supportsRole: role => ['reload', 'forceReload', 'quit'].includes(role),
  report: error => console.error('Electron native failure:', error)},
  {...metadata, versions: {electromux: '0.0.1'}}, message => console.warn(message));
await channel.ready();
