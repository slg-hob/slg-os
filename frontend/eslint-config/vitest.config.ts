import { defineConfig } from 'vitest/config';
import { LINE_COVERAGE_MINIMUM } from '../vitest.shared.ts';

// Node-only package: the sources are plain ES modules, so the shared jsdom configuration does not apply.
export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    include: ['src/**/*.test.mjs'],
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'json-summary', 'lcov'],
      include: ['src/**/*.mjs'],
      exclude: ['src/**/*.test.mjs'],
      thresholds: { lines: LINE_COVERAGE_MINIMUM, statements: LINE_COVERAGE_MINIMUM, functions: LINE_COVERAGE_MINIMUM },
    },
  },
});
