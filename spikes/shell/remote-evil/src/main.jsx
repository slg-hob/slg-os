// Standalone dev harness: renders the module outside the shell with a mock shell context.
import { createRoot } from 'react-dom/client';
import { ShellProvider } from 'spike-sdk';
import App from './App.jsx';

const value = { t: (ns, key) => key, theme: 'light', language: 'en', getAccessToken: () => null };
createRoot(document.getElementById('root')).render(
  <ShellProvider value={value}><App /></ShellProvider>
);
