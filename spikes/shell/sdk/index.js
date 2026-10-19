// Shell SDK for the spike. Shared as a Module Federation singleton so that the
// shell and all remotes use the same React context instance.
import { createContext, createElement, useContext } from 'react';

export const ShellContext = createContext(null);

export function useShell() {
  const ctx = useContext(ShellContext);
  if (!ctx) throw new Error('useShell must be used inside the shell');
  return ctx;
}

export function ShellProvider({ value, children }) {
  return createElement(ShellContext.Provider, { value }, children);
}

export const SDK_VERSION = '0.0.1';
