import {EventEmitter} from 'node:events';
import {ElectronCompatibility, type NativeAdapter, type WindowOptions, type MenuTemplate} from './electron-compat.js';
import {type CompatibilityManifest} from './electron-manifest.js';

export type AppMetadata = {name: string; version: string; versions: Readonly<Record<string, string>>};

/** Explicit lifecycle/metadata subset, with no changes to the existing ConsumerHost API. */
export function createElectronMain(manifest: CompatibilityManifest, adapter: NativeAdapter,
    metadata: AppMetadata, diagnostic: (message: string) => void) {
  const owner = new ElectronCompatibility(manifest, adapter);
  let resolveReady!: () => void;
  let ready = false;
  const readiness = new Promise<void>(resolve => { resolveReady = resolve; });
  const app = Object.assign(new EventEmitter(), {
    isPackaged: true,
    getName: () => metadata.name,
    getVersion: () => metadata.version,
    isReady: () => ready,
    whenReady: () => readiness,
    quit() { void owner.enqueue({kind: 'app.quit'}).catch(() => {}); },
    relaunch() { throw new Error('Electromux app.relaunch is not supported in this proof'); },
    commandLine: {
      appendSwitch(name: string, value?: string) {
        // Electron main runs after Android Chromium initialization. Never imply
        // these switches were applied or reinterpret them as security grants.
        diagnostic(`Deferred Chromium switch: ${name}${value === undefined ? '' : `=${value}`}`);
      },
    },
  });
  class BrowserWindow {
    constructor(options?: WindowOptions) {
      const window = owner.createWindow(options);
      app.emit('browser-window-created', {}, window);
      return window;
    }
    static getFocusedWindow() { return owner.getFocusedWindow(); }
    static getAllWindows() { const window = owner.getFocusedWindow(); return window ? [window] : []; }
  }
  class Menu {
    private constructor(readonly template: readonly MenuTemplate[]) {}
    static buildFromTemplate(template: readonly MenuTemplate[]) { return new Menu(template); }
    static setApplicationMenu(menu: Menu | null) { owner.setApplicationMenu(menu?.template ?? []); }
  }
  let themeSource: 'system' | 'light' | 'dark' = 'system';
  const nativeTheme = {
    get themeSource() { return themeSource; },
    set themeSource(value: 'system' | 'light' | 'dark') {
      if (!['system', 'light', 'dark'].includes(value)) throw new Error('Unsupported themeSource');
      themeSource = value; void owner.enqueue({kind: 'theme', value}).catch(() => {});
    },
  };
  let remoteInitialized = false;
  const remoteMain = {
    initialize() { remoteInitialized = true; },
    enable(contents: unknown) {
      if (!remoteInitialized || contents !== owner.getFocusedWindow()?.webContents) throw new Error('Unknown remote metadata target');
    },
  };
  function contextMenu() {
    const reason = manifest.deferredFeatures.contextMenu;
    if (!reason) throw new Error('electron-context-menu requires explicit deferredFeatures.contextMenu policy');
    diagnostic(`Deferred context menu: ${reason}`);
    return () => { /* No menu listeners were registered under declared deferral. */ };
  }
  return {
    owner, electron: {app, BrowserWindow, Menu, nativeTheme}, remoteMain, contextMenu,
    markReady() { if (ready) throw new Error('Electron app already ready'); ready = true; resolveReady(); app.emit('ready'); },
  };
}
