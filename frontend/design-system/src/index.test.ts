import { colorTokens, cssVar, DESIGN_SYSTEM_VERSION, radiusTokens, sizeTokens, spaceTokens, typeScale } from './index';

describe('design system', () => {
  it('exposes a semantic version', () => {
    expect(DESIGN_SYSTEM_VERSION).toMatch(/^\d+\.\d+\.\d+$/);
  });

  it('exposes the colour tokens of the visual language in both themes', () => {
    expect(colorTokens['ink-strong']).toEqual({ light: '#262626', dark: '#f2f2f2' });
    expect(colorTokens.primary.light).toBe('#00aeef');
    expect(colorTokens['surface-glass'].light).toBe('rgba(255, 255, 255, 0.78)');
  });

  it('exposes the space, radius, size and type scales', () => {
    expect(Object.values(spaceTokens)).toEqual(['0.25rem', '0.5rem', '0.75rem', '1rem', '1.5rem', '2rem', '3rem']);
    expect(radiusTokens['radius-pill']).toBe('9999px');
    expect(sizeTokens['top-bar-height']).toBe('2.75rem');
    expect(sizeTokens['focus-ring-width']).toBe('2px');
    expect(typeScale.body).toEqual({ size: '1rem', line: '1.5rem', weight: 400 });
  });

  it('names the custom property that carries a token', () => {
    expect(cssVar('surface-canvas')).toBe('--slg-surface-canvas');
  });
});
