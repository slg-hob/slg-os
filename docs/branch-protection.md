# Branch protection for `main`

The rule "every change goes through a pull request and the CI check `gate` must be green" (see `CLAUDE.md`) is enforced by GitHub branch protection. It needs repository admin rights, so the owner sets it once. Claude Code sessions cannot change it (the proxy refuses write access to the protection API).

## Settings (GitHub: Settings, Branches, Add branch ruleset or rule for `main`)
- Require a pull request before merging (required approvals: 0, the owner approves PRDs and ADRs in the pull request or in the chat).
- Require status checks to pass before merging: add the check **`gate`**; require branches to be up to date before merging.
- Require linear history (the workflow uses squash merges).
- Block force pushes and block deletion of `main`.
- Do not allow bypassing the rules (include administrators).
- Optionally enable "Automatically delete head branches".

## Same settings as a command
Run once with a token that has admin rights on the repository:

```bash
gh api -X PUT repos/slg-hob/slg-os/branches/main/protection --input - <<'JSON'
{
  "required_status_checks": { "strict": true, "contexts": ["gate"] },
  "enforce_admins": true,
  "required_pull_request_reviews": { "required_approving_review_count": 0 },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
```

The check name `gate` is the job `gate` in `.github/workflows/ci.yml`; it aggregates all required jobs, so new jobs join the quality gate without changing the protection.
