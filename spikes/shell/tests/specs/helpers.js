// Records CSP violation events in the page (WP1.6) so tests can assert there are none.
export async function trackCsp(page) {
  await page.addInitScript(() => {
    window.__cspViolations = [];
    document.addEventListener('securitypolicyviolation', (e) => {
      window.__cspViolations.push({ directive: e.violatedDirective, blocked: e.blockedURI, source: e.sourceFile });
    });
  });
}
export const cspViolations = (page) => page.evaluate(() => window.__cspViolations);
