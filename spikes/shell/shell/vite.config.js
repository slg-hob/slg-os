import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { federation } from '@module-federation/vite';

// Host: no static remotes. Remotes are registered at runtime from registry data.
export default defineConfig({
  plugins: [
    react(),
    federation({
      name: 'shell',
      remotes: {},
      shared: {
        react: { singleton: true, requiredVersion: '^19.0.0' },
        'react/': { singleton: true, requiredVersion: '^19.0.0' },
        'react-dom': { singleton: true, requiredVersion: '^19.0.0' },
        'react-dom/': { singleton: true, requiredVersion: '^19.0.0' },
        'spike-sdk': { singleton: true, requiredVersion: '^0.0.1' },
      },
    }),
  ],
  build: { target: 'esnext' },
});
