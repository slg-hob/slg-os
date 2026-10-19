import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { federation } from '@module-federation/vite';

export default defineConfig({
  base: '/modules/evil/remote/',
  plugins: [
    react(),
    federation({
      name: 'mod_evil',
      filename: 'remoteEntry.js',
      dts: false, // without a tsconfig the DTS worker crashes the dev server (finding F-MF-3)
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
