import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { federation } from '@module-federation/vite';

export default defineConfig({
  base: '/modules/b/remote/',
  plugins: [
    react(),
    federation({
      name: 'mod_b',
      filename: 'remoteEntry.js',
      exposes: { './App': './src/App.jsx' },
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
