# Licences of design assets

The machine-readable record is [`assets.lock`](assets.lock); the licence texts that ship with the product are in `THIRD-PARTY-NOTICES` in the repository root. This page explains the rules (PRD-0005 VL-7 AC4).

## Rules

- Every font, icon package, brand file and CSS framework that ends up in a build is listed in `assets.lock` with its source URL, version, hash and licence. An npm asset is pinned by the integrity hash of the lock file, a committed file by its SHA-256.
- `npm run assets:check` (frontend directory, CI job `design`) fails when a hash differs, when an asset is not named in `THIRD-PARTY-NOTICES`, and when any package in `package-lock.json` has a licence that is not on the allowlist.
- The allowlist contains permissive licences and MPL-2.0 (file-level copyleft, accepted for build-time tools such as `lightningcss`, which are not shipped in the product). Any other licence, including GPL and AGPL variants, fails the check. A new entry in `allowedLicences` or `licenceExceptions` is a decision of the owner and goes through CODEOWNERS review.
- A package without a licence field needs an exception with the reason and the evidence (for example the hash of its LICENSE file).
- Fonts are never fetched at build time without a hash (PRD-0005 section 8).

## Assets by milestone

| Milestone | Asset | Licence | Status |
|---|---|---|---|
| V1 | Bootstrap 5.3.8 (SCSS) | MIT | recorded in `assets.lock` |
| V2 | Source Sans 3 (`@fontsource-variable/source-sans-3`) | OFL-1.1 | planned, recorded when the files are added |
| V2 | Lucide icons | ISC (MIT notice for Feather-derived parts) | planned, recorded when the package is added |
