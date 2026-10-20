import { ignoreFiles, moduleConfig, platformConfig } from '@slg-os/stylelint-config';

// Module code (reference-module and every module repository) gets the strict rules; the platform packages
// (shell, design-system) may declare --bs-* variables and style the document.
const disabled = Object.fromEntries(Object.keys(moduleConfig.rules).map((rule) => [rule, null]));

export default {
  ...moduleConfig,
  ignoreFiles,
  overrides: [{ files: ['shell/**/*.css', 'design-system/**/*.css'], rules: { ...disabled, ...platformConfig.rules } }],
};
