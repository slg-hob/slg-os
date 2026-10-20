# 0013. Repository layout, toolchain and CI tooling

- Status: Proposed
- Date: 2026-10-19

## Context
PRD-0003 (walking skeleton) asks for the repository layout, the pinned toolchain versions (Q2/Q3), the Java base package (Q33) and the choice of scanners and checks (Q34), and requires the version table to be machine-checked (SK-1 AC2). The versions in PRD-0003 section 7.4 were only checked for existence in package registries. This ADR records what was built and verified in milestone M1: the combination builds and passes all gates on the CI runners.

## Decision
**Layout** (PRD-0003 section 7.1): `pom.xml` (parent), `backend/{core,sdk-backend,reference-module}`, `frontend/{shell,design-system,sdk-frontend,reference-module,contracts,tools}` as npm workspaces, `deploy/`, `e2e/`, `ci/selftest/`, `scripts/ci/`, `docs/`.

**Java base package and group id (Q33):** `de.systemlogistics.slgos`, deviating from the proposal `com.systemlogistics.slgos` because the company domain is `systemlogistics.de`.

**Versions** (the table is rendered from `docs/adr/0013-versions.json`; `scripts/ci/check-versions.mjs` fails when it differs from `pom.xml`, the Maven wrapper, `frontend/package.json`, the lock file or the workflows):

<!-- versions:start -->
| Component | Version | Where it is pinned |
| --- | --- | --- |
| JDK | 25 | `pom.xml` (`java.version`, `maven.compiler.release`), CI `setup-java` |
| Spring Boot | 4.1.1 | `pom.xml` parent |
| Maven | 3.9.16 | `.mvn/wrapper/maven-wrapper.properties` with SHA-256 |
| JaCoCo | 0.8.15 | `pom.xml` (`jacoco.version`) |
| Node.js | >=22.12.0 <23 | `frontend/package.json` (`engines`) |
| `@eslint/js` | 10.0.1 | `frontend/package.json` and lock file |
| `@formatjs/icu-messageformat-parser` | 3.5.21 | `frontend/package.json` and lock file |
| `@testing-library/dom` | 10.4.2 | `frontend/package.json` and lock file |
| `@testing-library/jest-dom` | 7.0.1 | `frontend/package.json` and lock file |
| `@testing-library/react` | 16.3.3 | `frontend/package.json` and lock file |
| `@types/node` | 22.20.5 | `frontend/package.json` and lock file |
| `@types/react` | 19.3.0 | `frontend/package.json` and lock file |
| `@types/react-dom` | 19.3.0 | `frontend/package.json` and lock file |
| `@vitejs/plugin-react` | 6.1.2 | `frontend/package.json` and lock file |
| `@vitest/coverage-v8` | 5.0.5 | `frontend/package.json` and lock file |
| `eslint` | 10.13.0 | `frontend/package.json` and lock file |
| `eslint-plugin-i18next` | 6.1.5 | `frontend/package.json` and lock file |
| `eslint-plugin-react-hooks` | 7.1.1 | `frontend/package.json` and lock file |
| `i18next` | 26.5.3 | `frontend/package.json` and lock file |
| `i18next-icu` | 2.6.1 | `frontend/package.json` and lock file |
| `intl-messageformat` | 12.1.3 | `frontend/package.json` and lock file |
| `jsdom` | 29.1.1 | `frontend/package.json` and lock file |
| `react` | 19.3.1 | `frontend/package.json` and lock file |
| `react-dom` | 19.3.1 | `frontend/package.json` and lock file |
| `react-i18next` | 17.1.0 | `frontend/package.json` and lock file |
| `typescript` | 6.0.3 | `frontend/package.json` and lock file |
| `typescript-eslint` | 8.72.0 | `frontend/package.json` and lock file |
| `vite` | 8.3.4 | `frontend/package.json` and lock file |
| `vitest` | 5.0.5 | `frontend/package.json` and lock file |
| `actions/checkout` | v6.1.0 (`d23441a`) | workflows (commit SHA) |
| `actions/download-artifact` | v6.0.0 (`018cc2c`) | workflows (commit SHA) |
| `actions/setup-java` | v6.0.1 (`de7274f`) | workflows (commit SHA) |
| `actions/setup-node` | v6.5.0 (`2499707`) | workflows (commit SHA) |
| `actions/upload-artifact` | v6.0.0 (`b7c566a`) | workflows (commit SHA) |
| `aquasecurity/setup-trivy` | v0.2.6 (`3fb12ec`) | workflows (commit SHA) |
| Trivy | v0.73.0 | CI security job |
<!-- versions:end -->

**Deviations from the proposal in PRD-0003 section 7.4**
- TypeScript 6.0.3 instead of 7.x: `typescript-eslint` 8.72.0 supports TypeScript below 6.1 only. The fallback fixed in advance applies; upgrade when the tooling follows.
- `jsdom` 29.1.1 instead of 30.x: jsdom 30 requires Node 22.22.2 or later, which would force an exact patch level of Node on every developer machine.
- Node.js stays on 22 (engines `>=22.12.0 <23`).
- Messages are ICU MessageFormat rendered with `i18next-icu` and checked with `@formatjs/icu-messageformat-parser` (PRD-0001 UI-26).

**Tooling choices (Q34)**
- One scanner, Trivy (pinned by version and action SHA), covers dependency, image and secret scanning and the CycloneDX SBOM. Fewer moving parts than one tool per task; it is replaceable behind `scripts/ci/security-scan.sh`.
- CI scripts are dependency-free Node ES modules with `node:test` and a line-coverage minimum of 80 %; the frontend tools package uses the same runner.
- Load testing (k6), backup encryption (`age`) and shell script tests (`bats`) are chosen when their milestones start, not here.

**Layout and deferred items**
- npm commands run in `frontend/` (there is no root `package.json`), unlike the root-level form in PRD-0003 SK-1 AC1; the commands are listed in `README.md` and `CLAUDE.md`.
- The install-script allowlist is empty: `npm ci --ignore-scripts` and the test run prove the toolchain works without install scripts.
- Deferred to M2: `docker compose build`, image digests in `deploy/` and their check, `mvn help:effective-pom` check of the coverage rule, version rows for Playwright, axe, Module Federation, Bootstrap, Keycloak, PostgreSQL and nginx.
- Deferred to M4 (SK-11): lint of `.ts` files for user-visible strings; `index.html` is checked for literal announced attributes, and its `<title>` is the product name "SLG OS", a brand constant. The shell sets `lang` at start.
- The self-test faults `skipped-job` and `critical-coverage` feed fixtures to the scripts; an end-to-end skipped job in the real workflow is not simulated.
- A dependency bot (`.github/dependabot.yml`) proposes updates for actions, Maven and npm.

**Data access (PRD-0003 D6):** Spring Data JDBC / `JdbcClient` with explicit SQL, no JPA, because sessions, locks and the audit privileges need explicit SQL. Applied when the first persistence code is written (M2).

**Supply-chain rules**
- Maven runs with `--strict-checksums`; the wrapper pins the distribution SHA-256; `maven-enforcer-plugin` requires JDK 25 and Maven 3.9.16 or later within 3.x.
- npm runs with `ignore-scripts`, `save-exact`, `engine-strict`; `npm ci` is the only install command in CI; `--force` and `--legacy-peer-deps` are forbidden (checked in `scripts/ci/check-workflows.mjs`).
- Every external GitHub Action is pinned to a full commit SHA, all older than two weeks when pinned; workflows declare `permissions: contents: read` by default and never use `pull_request_target`.
- `.github/**`, `scripts/ci/**`, `ci/**`, build and coverage configuration and the version table are owned by the owner through `CODEOWNERS`.

## Consequences
- A version change is one edit of the JSON file plus the build file, and the table in this ADR is regenerated; CI fails otherwise.
- The fallbacks fixed in advance remain: JDK 21 (change `java.version`, the enforcer rule and the CI `setup-java` version), TypeScript 6.x, Node 22.
- `CODEOWNERS` names the owner for the gate and build configuration. Enforcing it needs a second maintainer (an author cannot approve their own pull request), so until then the gate is protected by the owner's own review of such changes; this is an accepted gap (see `docs/branch-protection.md`). `@slg-hob` is a personal account, which is a valid code owner.
- The coverage regression baseline moves with every merge to `main` (limit 2 points per merge, not cumulative); the 80 % and 90 % floors bound the drift.
- Trivy and the pinned actions must be re-checked when they are updated; a bot proposes the updates, they are reviewed like code.

## Alternatives considered
- One scanner per task (OSV-Scanner, Gitleaks, Syft): more tools to pin and update, no gain at this scale.
- Gradle: rejected in ADR-0004.
- `com.systemlogistics` as base package: kept as a rejected proposal because the registered domain is `.de`.
