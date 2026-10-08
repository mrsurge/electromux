import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseCompatibilityManifest} from '../src/electron-manifest.js';
import {ElectronCompatibility, type NativeEffect} from '../src/electron-compat.js';

const declaration = {schemaVersion: 1, windows: 'main-only', chrome: {style: 'gnome', titleBar: true, menuBar: true}};
function setup(disabledMenuItems = {}) {
  const effects: NativeEffect[] = [], errors: Error[] = [];
  const owner = new ElectronCompatibility(parseCompatibilityManifest({...declaration, disabledMenuItems}), {
    async apply(effect) { effects.push(effect); }, supportsRole(role) { return role === 'copy'; }, report(error) { errors.push(error); },
  });
  return {owner, effects, errors};
}
test('manifest is strict, immutable, and cannot grant capabilities', () => {
  const manifest = parseCompatibilityManifest(declaration);
  assert.ok(Object.isFrozen(manifest.chrome));
  assert.throws(() => parseCompatibilityManifest({...declaration, permissions: ['exec']}), /Unknown/);
  assert.throws(() => parseCompatibilityManifest({...declaration, windows: 'stacked'}), /Unsupported/);
  assert.throws(() => parseCompatibilityManifest({...declaration, chrome: {...declaration.chrome, menuBar: 'true'}}), /Invalid/);
  assert.throws(() => parseCompatibilityManifest({...declaration, disabledMenuItems: {About: ''}}), /Invalid/);
});
test('create precedes load; additional windows fail explicitly', async () => {
  const {owner, effects} = setup();
  const window = owner.createWindow({title: 'Calculator'});
  await window.loadURL('file:///calculator/index.html');
  assert.deepEqual(effects.map(effect => effect.kind), ['window.create', 'window.load']);
  assert.throws(() => owner.createWindow(), /additional BrowserWindow/);
});
test('callbacks stay in Node; unsupported and manifest-disabled actions cannot dispatch', async () => {
  const {owner, effects} = setup({'0/2': 'Additional windows deferred'});
  const window = owner.createWindow(); let clicked = 0;
  owner.setApplicationMenu([{label: 'Edit', submenu: [
    {label: 'Action', click(_item, focused) { assert.equal(focused, window); clicked++; }},
    {role: 'toggleDevTools'}, {label: 'About', click() { owner.createWindow(); }}, {role: 'copy'},
  ]}]);
  await owner.flush();
  const menu = effects.find(effect => effect.kind === 'menu.set'); assert.ok(menu?.kind === 'menu.set');
  assert.equal(JSON.stringify(menu).includes('click'), false);
  await owner.selectMenuItem('1:0/0'); assert.equal(clicked, 1);
  await assert.rejects(owner.selectMenuItem('1:0/1'), /disabled/);
  await assert.rejects(owner.selectMenuItem('1:0/2'), /disabled/);
  await owner.selectMenuItem('1:0/3'); assert.equal(effects.at(-1)?.kind, 'role');
  owner.setApplicationMenu([]); await assert.rejects(owner.selectMenuItem('1:0/0'), /Stale/);
});
test('hidden/disabled parent prevents child dispatch; empty leaves are disabled', async () => {
  const {owner, effects} = setup(); owner.createWindow();
  owner.setApplicationMenu([{visible: false, submenu: [{role: 'copy'}]}, {label: 'Empty'}]);
  await owner.flush(); await assert.rejects(owner.selectMenuItem('1:0/0'), /disabled/);
  const menu = effects.find(effect => effect.kind === 'menu.set'); assert.ok(menu?.kind === 'menu.set');
  assert.equal(menu.items[1]?.enabled, false);
});
test('native failure is retained and observed, never a successful no-op', async () => {
  let reports = 0;
  const owner = new ElectronCompatibility(parseCompatibilityManifest(declaration), {
    async apply() { throw new Error('native unavailable'); }, supportsRole() { return true; }, report() { reports++; },
  });
  const window = owner.createWindow(); await assert.rejects(window.loadFile('index.html'), /native unavailable/);
  await assert.rejects(owner.flush(), /native unavailable/); assert.equal(reports, 1);
});
test('close cancellation retains window; acknowledged close emits closed', async () => {
  const {owner} = setup(); const window = owner.createWindow();
  const cancel = (event: {preventDefault(): void}) => event.preventDefault();
  window.on('close', cancel); window.close(); await owner.flush(); assert.equal(window.isDestroyed(), false);
  window.off('close', cancel); let closed = false; window.on('closed', () => { closed = true; });
  window.close(); await owner.flush(); assert.ok(closed); assert.equal(owner.getFocusedWindow(), undefined);
  assert.throws(() => window.loadFile('index.html'), /destroyed/);
});
