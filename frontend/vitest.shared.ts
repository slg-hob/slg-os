import { defineConfig, type ViteUserConfig } from 'vitest/config';

/**
 * Shared test configuration. The coverage gate is part of the build (PRD-0001 NFR-2, PRD-0003 SK-2):
 * a package fails when line coverage drops below the minimum. Never lower these values.
 */
export const LINE_COVERAGE_MINIMUM = 80;

export function packageConfig(overrides: ViteUserConfig = {}) {
  return defineConfig({
    ...overrides,
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: [new URL('./vitest.setup.ts', import.meta.url).pathname],
      include: ['src/**/*.test.{ts,tsx}'],
      coverage: {
        provider: 'v8',
        reporter: ['text-summary', 'json-summary', 'lcov'],
        include: ['src/**/*.{ts,tsx}'],
        // Entry points without logic are excluded; every entry needs a reason here.
        exclude: ['src/**/*.test.{ts,tsx}', 'src/main.tsx'],
        thresholds: { lines: LINE_COVERAGE_MINIMUM, statements: LINE_COVERAGE_MINIMUM, functions: LINE_COVERAGE_MINIMUM },
      },
    },
  });
}
