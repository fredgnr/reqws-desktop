import { builtinModules } from 'node:module';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { build } from 'vite';

const root = path.resolve(import.meta.dirname, '..');
const output = path.join(root, '.vite/e2e');
const external = ['electron', ...builtinModules.flatMap((name) => [name, `node:${name}`])];

// Rebuilt once per invocation, separately from Forge's candidate .vite/build.
// Use the same checked-in Vite configs and Forge's CJS/file:// conventions.
export async function buildE2E(): Promise<void> {
  await rm(output, { recursive: true, force: true });
  await mkdir(output, { recursive: true });
  await build({
    configFile: path.join(root, 'vite.main.config.mts'),
    root,
    define: { MAIN_WINDOW_VITE_NAME: JSON.stringify('main_window') },
    resolve: { conditions: ['node'], mainFields: ['module', 'jsnext:main', 'jsnext'] },
    build: {
      outDir: path.join(output, 'build'), emptyOutDir: false, minify: false,
      lib: { entry: 'tests/e2e/fixtures/test-main.ts', formats: ['cjs'], fileName: () => 'main.js' },
      rollupOptions: { external },
    },
  });
  await build({
    configFile: path.join(root, 'vite.preload.config.mts'),
    root,
    build: {
      outDir: path.join(output, 'build'), emptyOutDir: false, minify: false,
      rollupOptions: {
        external, input: 'src/preload/index.ts',
        output: { format: 'cjs', inlineDynamicImports: true, entryFileNames: 'preload.js' },
      },
    },
  });
  await build({
    configFile: path.join(root, 'vite.renderer.config.mts'),
    base: './',
    build: { outDir: path.join(output, 'renderer/main_window'), emptyOutDir: true },
  });
  await writeFile(path.join(output, 'package.json'), JSON.stringify({
    name: 'reqws-e2e', version: JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8')).version, main: 'build/main.js',
  }));
}

export default buildE2E;
