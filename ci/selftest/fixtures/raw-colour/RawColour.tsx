// Self-test fixture: module code that breaks the design rules. The fault "module-design-rules" copies this file into
// frontend/reference-module/src, where the lint of PRD-0005 VL-5 AC3 must reject it.
export function RawColour() {
  return <div style={{ color: '#00aeef' }} className="slg-glass" />;
}
