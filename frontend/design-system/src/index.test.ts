import { DESIGN_SYSTEM_VERSION } from './index';

describe('design system', () => {
  it('exposes a semantic version', () => {
    expect(DESIGN_SYSTEM_VERSION).toMatch(/^\d+\.\d+\.\d+$/);
  });
});
