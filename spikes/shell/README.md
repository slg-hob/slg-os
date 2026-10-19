# Spike: shell, Module Federation, window manager (PRD-0002)

Disposable code. Never merged into `main`; tagged `spike-shell-v1`. Findings: `docs/spikes/2026-shell-spike-report.md` on `main`.

## Run (native; this environment has no Docker daemon, so Compose was not used)
```
(cd shell && npm ci && npm run build)
for d in remote-a remote-b remote-mismatch remote-evil; do (cd $d && npm ci && npm run build); done
(cd stub && npm ci); (cd tests && npm ci)
scripts/keycloak.sh start        # downloads nothing; expects keycloak/dist/keycloak-26.8.1 (Maven Central zip)
scripts/up.sh                    # register remotes, shell-server, hostile upstream, stub, nginx on https://localhost:8443
cd tests && WM_ENGINE=inhouse npx playwright test
scripts/down.sh && scripts/keycloak.sh stop
```
Test passwords are generated into `.run/secrets` (ignored). Playwright 1.56.1 matches the preinstalled Chromium build; Firefox is not available here.

## Layout
`shell/` host (Vite 8, React 19, Bootstrap 5.3, oidc-client-ts, window manager in `src/wm`), `remote-a|b|mismatch|evil/` federated modules, `sdk/` shared singleton, `registry/` manifests, `proxy/` nginx template, `stub/` token-validating API, `keycloak/` realm template, `tests/` Playwright suites, `results/` raw measurements and logs.
