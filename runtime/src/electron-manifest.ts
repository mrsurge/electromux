export type CompatibilityManifest = {
  schemaVersion: 1;
  windows: 'main-only';
  chrome: {style: 'gnome'; titleBar: boolean; menuBar: boolean};
  disabledMenuItems: Readonly<Record<string, string>>;
  deferredFeatures: Readonly<Record<string, string>>;
};

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected manifest object');
  return value as Record<string, unknown>;
}
function keys(value: Record<string, unknown>, allowed: readonly string[]): void {
  for (const key of Object.keys(value)) if (!allowed.includes(key)) throw new Error(`Unknown manifest field: ${key}`);
}

/** Presentation policy only: never a grant of filesystem, process or renderer authority. */
export function parseCompatibilityManifest(value: unknown): CompatibilityManifest {
  const root = object(value);
  keys(root, ['schemaVersion', 'windows', 'chrome', 'disabledMenuItems', 'deferredFeatures']);
  if (root.schemaVersion !== 1 || root.windows !== 'main-only') throw new Error('Unsupported compatibility schema/windows');
  const chrome = object(root.chrome);
  keys(chrome, ['style', 'titleBar', 'menuBar']);
  if (chrome.style !== 'gnome' || typeof chrome.titleBar !== 'boolean' || typeof chrome.menuBar !== 'boolean') {
    throw new Error('Invalid chrome declaration');
  }
  const disabled = object(root.disabledMenuItems ?? {});
  const items: Record<string, string> = Object.create(null) as Record<string, string>;
  if (Object.keys(disabled).length > 256) throw new Error('Too many disabled menu items');
  for (const [path, reason] of Object.entries(disabled)) {
    if (!/^\d+(\/\d+)*$/.test(path) || typeof reason !== 'string' || !reason.trim() || reason.length > 256) {
      throw new Error('Invalid disabled menu item path/reason');
    }
    items[path] = reason;
  }
  const features = object(root.deferredFeatures ?? {});
  keys(features, ['contextMenu']);
  const deferred: Record<string, string> = Object.create(null) as Record<string, string>;
  for (const [name, reason] of Object.entries(features)) {
    if (typeof reason !== 'string' || !reason.trim() || reason.length > 256) throw new Error('Invalid deferred feature reason');
    deferred[name] = reason;
  }
  return Object.freeze({schemaVersion: 1, windows: 'main-only', chrome: Object.freeze({
    style: 'gnome', titleBar: chrome.titleBar, menuBar: chrome.menuBar,
  }), disabledMenuItems: Object.freeze(items), deferredFeatures: Object.freeze(deferred)});
}
