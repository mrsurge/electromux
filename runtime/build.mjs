import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
await build({entryPoints: [fileURLToPath(new URL('src/main.ts', import.meta.url))],
  outfile: fileURLToPath(new URL('dist/main.mjs', import.meta.url)), bundle: true,
  platform: 'node', format: 'esm', target: 'node24', sourcemap: true});
