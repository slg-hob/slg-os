#!/usr/bin/env bash
# Dependency, secret and (later) image scanning with Trivy (ADR-0013). Fails on HIGH or CRITICAL findings.
# SCAN_PATH selects the directory (default: repository root); SBOM_OUT, if set, also writes a CycloneDX SBOM.
set -euo pipefail

path="${SCAN_PATH:-.}"
ignore_args=()
if [ -f ci/trivyignore.yaml ]; then ignore_args=(--ignorefile ci/trivyignore.yaml); fi

trivy fs --quiet --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 \
  --skip-dirs node_modules --skip-dirs target --skip-dirs coverage --skip-dirs ci/selftest/fixtures \
  "${ignore_args[@]}" "$path"

if [ -n "${SBOM_OUT:-}" ]; then
  trivy fs --quiet --format cyclonedx --output "$SBOM_OUT" \
    --skip-dirs node_modules --skip-dirs target --skip-dirs coverage --skip-dirs ci/selftest/fixtures "$path"
fi
