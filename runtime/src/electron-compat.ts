import {EventEmitter} from 'node:events';
import {type CompatibilityManifest} from './electron-manifest.js';

export type WindowOptions = {
  title?: string; width?: number; height?: number; resizable?: boolean;
  maximizable?: boolean; useContentSize?: boolean; icon?: string; darkTheme?: boolean;
  webPreferences?: {preload?: string; nodeIntegration?: boolean; nodeIntegrationInWorker?: boolean;
    contextIsolation?: boolean; sandbox?: boolean; experimentalFeatures?: boolean; webviewTag?: boolean; devTools?: boolean};
};
export type MenuTemplate = {
  label?: string; role?: string; type?: 'normal' | 'separator' | 'checkbox'; enabled?: boolean;
  visible?: boolean; checked?: boolean; accelerator?: string; submenu?: readonly MenuTemplate[];
  click?: (item: MenuTemplate, window: MainWindow | undefined, event: Readonly<Record<string, unknown>>) => unknown;
};
export type MenuItemDTO = {
  id: string; label: string; type: string; enabled: boolean; visible: boolean; checked: boolean;
  accelerator: string | null; disabledReason: string | null; submenu: MenuItemDTO[];
};
export type NativeEffect =
  | {kind: 'window.create'; windowId: number; options: WindowOptions}
  | {kind: 'window.load'; windowId: number; source: 'url' | 'file'; value: string}
  | {kind: 'window.close'; windowId: number}
  | {kind: 'menu.set'; revision: number; items: MenuItemDTO[]}
  | {kind: 'role'; windowId: number; role: string}
  | {kind: 'webContents'; windowId: number; action: 'goBack' | 'goForward' | 'reload'}
  | {kind: 'theme'; value: 'system' | 'light' | 'dark'}
  | {kind: 'app.quit'};
export type NativeAdapter = {
  /** Promise resolves only after the real native operation succeeds; no no-op acknowledgements. */
  apply(effect: NativeEffect): Promise<void>;
  supportsRole(role: string): boolean;
  report(error: Error): void;
};

/** One main-window ownership lane. A future Android adapter supplies actual rendering. */
export class ElectronCompatibility {
  private window: MainWindow | undefined;
  private tail: Promise<void> = Promise.resolve();
  private failure: Error | undefined;
  private revision = 0;
  private selections = new Map<string, {template: MenuTemplate; dto: MenuItemDTO}>();
  constructor(readonly manifest: CompatibilityManifest, private readonly adapter: NativeAdapter) {}
  enqueue(effect: NativeEffect): Promise<void> {
    const next = this.tail.then(async () => {
      if (this.failure) throw this.failure;
      await this.adapter.apply(effect);
    });
    // Keep rejection observed even when an Electron void-returning API initiated it.
    this.tail = next.catch(error => {
      if (!this.failure) {
        this.failure = error instanceof Error ? error : new Error(String(error));
        this.adapter.report(this.failure);
      }
    });
    return next;
  }
  async flush(): Promise<void> { await this.tail; if (this.failure) throw this.failure; }
  createWindow(options: WindowOptions = {}): MainWindow {
    if (this.window) throw new Error('Electromux main-only compatibility: additional BrowserWindow unsupported');
    this.window = new MainWindow(this, options);
    return this.window;
  }
  getFocusedWindow(): MainWindow | undefined { return this.window?.isDestroyed() ? undefined : this.window; }
  setApplicationMenu(templates: readonly MenuTemplate[]): void {
    const revision = ++this.revision;
    const selections = new Map<string, {template: MenuTemplate; dto: MenuItemDTO}>();
    let count = 0;
    const build = (list: readonly MenuTemplate[], parent: string, depth: number, parentEnabled: boolean): MenuItemDTO[] => {
      if (depth > 8) throw new Error('Menu nesting limit');
      return list.map((template, index) => {
        if (++count > 256) throw new Error('Menu item limit');
        const path = parent ? `${parent}/${index}` : String(index);
        let reason = this.manifest.disabledMenuItems[path] ?? null;
        // Container roles do not execute; only leaf roles need native support.
        if (!template.submenu && template.role && !this.adapter.supportsRole(template.role)) reason ??= `Unsupported Electron role: ${template.role}`;
        const type = template.type ?? 'normal';
        if (!template.submenu && type !== 'separator' && !template.click && !template.role) reason ??= 'No action declared';
        const enabled = parentEnabled && template.enabled !== false && reason === null;
        const visible = template.visible !== false;
        const dto: MenuItemDTO = {id: `${revision}:${path}`, label: template.label ?? template.role ?? '', type,
          enabled, visible, checked: template.checked === true, accelerator: template.accelerator ?? null,
          disabledReason: reason, submenu: build(template.submenu ?? [], path, depth + 1, enabled && visible)};
        selections.set(dto.id, {template, dto});
        return dto;
      });
    };
    const items = build(templates, '', 0, true);
    this.selections = selections;
    void this.enqueue({kind: 'menu.set', revision, items}).catch(() => {});
  }
  async selectMenuItem(id: string): Promise<void> {
    await this.flush();
    const item = this.selections.get(id);
    if (!item || !item.dto.enabled || !item.dto.visible || item.dto.type === 'separator' || item.dto.submenu.length) {
      throw new Error('Stale, disabled or non-actionable menu selection');
    }
    const window = this.getFocusedWindow();
    if (item.template.click) {
      await item.template.click(item.template, window, Object.freeze({}));
      await this.flush();
    } else if (item.template.role && window) {
      await this.enqueue({kind: 'role', windowId: window.id, role: item.template.role});
    } else throw new Error('Menu action unavailable');
  }
}

export class MainWindow extends EventEmitter {
  readonly id = 1;
  private destroyed = false;
  private closing = false;
  readonly webContents: MainWebContents;
  constructor(private readonly owner: ElectronCompatibility, readonly options: WindowOptions) {
    super();
    this.webContents = new MainWebContents(owner, this);
    void owner.enqueue({kind: 'window.create', windowId: this.id, options}).catch(() => {});
  }
  private requireLive(): void { if (this.destroyed) throw new Error('BrowserWindow destroyed'); }
  loadURL(value: string): Promise<void> {
    this.requireLive(); return this.owner.enqueue({kind: 'window.load', windowId: this.id, source: 'url', value});
  }
  loadFile(value: string): Promise<void> {
    this.requireLive(); return this.owner.enqueue({kind: 'window.load', windowId: this.id, source: 'file', value});
  }
  isDestroyed(): boolean { return this.destroyed; }
  close(): void {
    this.requireLive();
    if (this.closing) return;
    let cancelled = false;
    this.emit('close', {preventDefault() { cancelled = true; }});
    if (cancelled) return;
    this.closing = true;
    void this.owner.enqueue({kind: 'window.close', windowId: this.id}).then(() => {
      this.destroyed = true; this.emit('closed');
    }).catch(() => {});
  }
}

export class MainWebContents extends EventEmitter {
  constructor(private readonly owner: ElectronCompatibility, private readonly window: MainWindow) { super(); }
  isDestroyed(): boolean { return this.window.isDestroyed(); }
  private action(action: 'goBack' | 'goForward' | 'reload'): void {
    if (this.isDestroyed()) throw new Error('webContents destroyed');
    void this.owner.enqueue({kind: 'webContents', windowId: this.window.id, action}).catch(() => {});
  }
  goBack(): void { this.action('goBack'); }
  goForward(): void { this.action('goForward'); }
  reload(): void { this.action('reload'); }
}
