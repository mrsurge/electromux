import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, writeFile, rm, readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createRequire} from 'node:module';
import {runInNewContext} from 'node:vm';
import {createElectronMain} from '../src/electron-main.js';
import {loadElectronEntry, createMetadataPreload} from '../src/electron-loader.js';
import {parseCompatibilityManifest} from '../src/electron-manifest.js';
import {type NativeEffect} from '../src/electron-compat.js';

function setup() {
  const effects: NativeEffect[] = [], diagnostics: string[] = [];
  const manifest = parseCompatibilityManifest({schemaVersion: 1, windows: 'main-only',
    chrome: {style: 'gnome', titleBar: true, menuBar: true},
    disabledMenuItems: {'0/4': 'Relaunch deferred', '2/4': 'Detached DevTools deferred',
      '3/1': 'Additional windows deferred', '3/3': 'Additional windows deferred',
      '3/4': 'Additional windows deferred', '3/5': 'Additional windows deferred'},
    deferredFeatures: {contextMenu: 'Third-party context menus deferred in this proof'}});
  const host = createElectronMain(manifest, {async apply(effect) { effects.push(effect); },
    supportsRole(role) { return ['copy', 'reload', 'quit'].includes(role); }, report(error) { throw error; }},
    {name: 'Electron Calculator', version: '1.1.4', versions: {electromux: '0.0.1'}}, message => diagnostics.push(message));
  return {host, effects, diagnostics};
}
test('calculator consumer manifest admits only the declared compatibility policy', async () => {
  const manifest = parseCompatibilityManifest(JSON.parse(await readFile(
    new URL('../../samples/electron-calculator/electromux.json', import.meta.url), 'utf8')));
  assert.equal(manifest.windows, 'main-only');
  assert.equal(Object.keys(manifest.disabledMenuItems).length, 6);
  assert.ok(manifest.deferredFeatures.contextMenu);
});
test('readiness is explicit and container roles do not disable actionable children', async () => {
  const {host, effects} = setup(); let started = false;
  const pending = host.electron.app.whenReady().then(() => {
    started = true; new host.electron.BrowserWindow({title: 'Test'});
    host.electron.Menu.setApplicationMenu(host.electron.Menu.buildFromTemplate([
      {role: 'editMenu', submenu: [{role: 'copy'}]},
    ]));
  });
  await Promise.resolve(); assert.equal(started, false);
  host.markReady(); await pending; await host.owner.flush();
  const menu = effects.find(effect => effect.kind === 'menu.set'); assert.ok(menu?.kind === 'menu.set');
  assert.equal(menu.items[0]?.submenu[0]?.enabled, true);
  await host.owner.selectMenuItem('1:0/0'); assert.equal(effects.at(-1)?.kind, 'role');
  assert.throws(() => host.markReady(), /already ready/);
});
test('scoped require resolves electron without changing process-global require or versions', async () => {
  const root = await mkdtemp(join(tmpdir(), 'electromux-loader-'));
  const original = process.versions.electron;
  try {
    await writeFile(join(root, 'entry.cjs'), "module.exports={app:require('electron').app.getVersion(),version:process.versions.electromux};");
    const {host} = setup();
    const result = loadElectronEntry(join(root, 'entry.cjs'), root, new Map([['electron', host.electron]]), {electromux: 'proof'});
    assert.deepEqual(result, {app: '1.1.4', version: 'proof'});
    assert.equal(process.versions.electron, original);
    await writeFile(join(root, 'escape.cjs'), "module.exports=require('../outside.cjs');");
    // Unknown packages/modules fail; no empty catch-all shim.
    await writeFile(join(root, 'unknown.cjs'), "require('electromux-nonexistent-module');");
    assert.throws(() => loadElectronEntry(join(root, 'unknown.cjs'), root, new Map(), {}), /Cannot find module/);
  } finally { await rm(root, {recursive: true}); }
});
test('original-style preload gets synchronous metadata without exposing Node', () => {
  let listener: (() => void) | undefined;
  const element = {innerText: ''};
  const script = createMetadataPreload("const remote=require('@electron/remote'); const version=remote.app.getVersion(); window.addEventListener('DOMContentLoaded',()=>{document.getElementById('v').innerText=version;});",
    {version: '1.1.4', versions: {node: '24'}});
  const context = {window: {addEventListener(_name: string, callback: () => void) { listener = callback; }},
    document: {getElementById() { return element; }}};
  runInNewContext(script, context); listener?.(); assert.equal(element.innerText, '1.1.4');
  assert.equal('require' in context, false); assert.equal('process' in context, false);
  assert.throws(() => runInNewContext(createMetadataPreload("require('fs')", {version: '1', versions: {}}), {}), /Unsupported/);
});
test('pinned original calculator main/preload executes without application source edits',
  {skip: !process.env.ELECTROMUX_CALCULATOR_APP}, async () => {
    const root = process.env.ELECTROMUX_CALCULATOR_APP!;
    const {host, effects, diagnostics} = setup();
    // Use the package's real supported Node implementation, not a no-op logger.
    const nativeRequire = createRequire(join(root, 'package.json'));
    const log = nativeRequire('electron-log/node') as {transports: {file: {resolvePathFn: () => string}}};
    const scratch = await mkdtemp(join(tmpdir(), 'electromux-calculator-log-'));
    log.transports.file.resolvePathFn = () => join(scratch, 'main.log');
    try {
      loadElectronEntry(join(root, 'app.js'), root, new Map<string, unknown>([
        ['electron', host.electron], ['electron-log', log], ['electron-context-menu', host.contextMenu],
        ['@electron/remote/main', host.remoteMain],
      ]), {electromux: '0.0.1'});
      host.markReady(); await new Promise(resolve => setImmediate(resolve)); await host.owner.flush();
      assert.deepEqual(effects.map(effect => effect.kind), ['window.create', 'menu.set', 'theme', 'window.load']);
      const menu = effects.find(effect => effect.kind === 'menu.set'); assert.ok(menu?.kind === 'menu.set');
      assert.equal(menu.items[0]?.submenu[0]?.enabled, true);
      assert.equal(menu.items[3]?.submenu[5]?.enabled, false);
      await host.owner.selectMenuItem('1:0/0'); assert.equal(effects.at(-1)?.kind, 'webContents');
      await assert.rejects(host.owner.selectMenuItem('1:3/5'), /disabled/);
      assert.ok(diagnostics.some(line => line.startsWith('Deferred context menu')));
      const listeners: (() => void)[] = [];
      const element = {innerText: ''};
      runInNewContext(createMetadataPreload(await readFile(join(root, 'preload.js'), 'utf8'),
        {version: '1.1.4', versions: {node: '24'}}), {
        window: {addEventListener(_name: string, callback: () => void) { listeners.push(callback); }},
        document: {getElementById(id: string) { return id === 'calc-version' ? element : null; }},
      });
      listeners.forEach(listener => listener()); assert.equal(element.innerText, '1.1.4');
    } finally { await rm(scratch, {recursive: true}); }
  });
