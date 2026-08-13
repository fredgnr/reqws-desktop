import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  root: 'src/renderer',
  build: {
    // Vite resolves relative output paths from `root`. Keep Forge artifacts in
    // the project-level .vite directory expected by createWindow and packaging.
    outDir: '../../.vite/renderer/main_window',
  },
  plugins: [react()],
});
