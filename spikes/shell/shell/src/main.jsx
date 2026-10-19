import 'bootstrap/dist/css/bootstrap.min.css';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';

// Mitigation experiment (WP3.6): freeze window.fetch so a remote cannot wrap it. Skipped with ?nolock to show the baseline.
if (!new URLSearchParams(location.search).has('nolock')) {
  Object.defineProperty(window, 'fetch', { value: window.fetch.bind(window), writable: false, configurable: false });
}

createRoot(document.getElementById('root')).render(<App />);
