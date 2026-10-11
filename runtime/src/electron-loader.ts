import {readFileSync, realpathSync} from 'node:fs';
import {createRequire, isBuiltin} from 'node:module';
import {dirname, isAbsolute, relative, resolve, sep} from 'node:path';
import {runInThisContext} from 'node:vm';

/** Scoped CommonJS loader. No process-global Module monkey patch or Node version spoofing. */
export function loadElectronEntry(entry: string, root: string, overrides: ReadonlyMap<string, unknown>,
    versions: Readonly<Record<string, string>>): unknown {
  const boundary = realpathSync(root);
  const cache = new Map<string, {exports: unknown}>();
  const processView = Object.create(process) as NodeJS.Process;
  Object.defineProperty(processView, 'versions', {value: Object.freeze({...process.versions, ...versions})});
  const requireContained = (file: string): string => {
    const canonical = realpathSync(file);
    const child = relative(boundary, canonical);
    if (child === '..' || child.startsWith(`..${sep}`) || isAbsolute(child)) throw new Error('Application module escapes declared root');
    return canonical;
  };
  const load = (file: string): unknown => {
    const canonical = requireContained(file);
    const cached = cache.get(canonical); if (cached) return cached.exports;
    const module: {exports: unknown} = {exports: {}}; cache.set(canonical, module);
    if (canonical.endsWith('.json')) { module.exports = JSON.parse(readFileSync(canonical, 'utf8')); return module.exports; }
    if (!canonical.endsWith('.js') && !canonical.endsWith('.cjs')) throw new Error('Unsupported application module type');
    const nativeRequire = createRequire(canonical);
    const require = (id: string): unknown => {
      if (overrides.has(id)) return overrides.get(id);
      if (isBuiltin(id)) return nativeRequire(id);
      return load(nativeRequire.resolve(id));
    };
    try {
      const wrapper = runInThisContext(`(function(exports,require,module,__filename,__dirname,process){\n${readFileSync(canonical, 'utf8')}\n})`,
        {filename: canonical}) as (exports: unknown, require: (id: string) => unknown, module: {exports: unknown},
          filename: string, directory: string, process: NodeJS.Process) => void;
      wrapper(module.exports, require, module, canonical, dirname(canonical), processView);
      return module.exports;
    } catch (error) { cache.delete(canonical); throw error; }
  };
  return load(resolve(entry));
}

/** Original preload source executes in a lexical scope with only the declared metadata APIs. */
export function createMetadataPreload(source: string, metadata: {version: string; versions: Readonly<Record<string, string>>}): string {
  if (Buffer.byteLength(source) > 65536) throw new Error('Preload source limit');
  const snapshot = JSON.stringify(metadata);
  return `(function(){"use strict";const snapshot=${snapshot};const process=Object.freeze({versions:Object.freeze(snapshot.versions)});` +
    `const remote=Object.freeze({app:Object.freeze({getVersion:()=>snapshot.version})});` +
    `const require=(id)=>{if(id==='@electron/remote')return remote;throw new Error('Unsupported preload module: '+id);};\n` +
    '(function(require,process){\n' + source + '\n})(require,process);})();';
}
