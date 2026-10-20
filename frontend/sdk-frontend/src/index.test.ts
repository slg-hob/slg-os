import { CONTRACT_VERSION, isSupportedContract } from './index';

describe('isSupportedContract', () => {
  it('accepts the current version', () => {
    expect(isSupportedContract(CONTRACT_VERSION)).toBe(true);
  });

  it.each(['1.1', '2.0', '0.9', '1', '1.0.0', 'x.y', ' 1.0', '1.-1', '99999.0', ''])('rejects %j', (declared) => {
    expect(isSupportedContract(declared)).toBe(false);
  });

  it.each([null, undefined])('rejects %s', (declared) => {
    expect(isSupportedContract(declared)).toBe(false);
  });
});
