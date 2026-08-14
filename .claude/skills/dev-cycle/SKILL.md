---
name: dev-cycle
description: |
  The repeatable shipping loop for this repo: commit, push, watch the GitHub Actions
  build pipeline, and only move on to the next step once it's green. If it goes red,
  fix, commit, push, and watch again - repeat until green before proceeding.
  Trigger: after finishing any discrete unit of work on bitbucket-companion-plugin.
user-invocable: true
---

# Dev Cycle

This repo's rule: **never stack unverified work**. Each discrete step (a tab, a fix, a
CI change) gets its own commit, push, and a green pipeline before the next step starts.

## Loop

1. **Commit** the change with a clear, specific message (what changed and why, not a
   restatement of the diff).
   ```bash
   git add <files>
   git commit -m "..."
   ```

2. **Push.**
   ```bash
   git push
   ```

3. **Watch the Build workflow** for the commit you just pushed:
   ```bash
   gh run watch $(gh run list --branch "$(git branch --show-current)" --limit 1 --json databaseId --jq '.[0].databaseId')
   ```
   `gh run watch` polls until the run finishes and streams job status live. If `gh` isn't
   on PATH yet in a given shell, use the full path
   (`"/c/Program Files/GitHub CLI/gh.exe"` on this machine) or open a fresh shell.

4. **If it passes** (conclusion: `success`): move on to the next planned step.

5. **If it fails**: pull the failure logs, fix locally, and go back to step 1 - don't
   start new work on top of a red pipeline.
   ```bash
   gh run view <run-id> --log-failed
   ```

## One-time setup

- `gh auth login` - interactive browser login; run this yourself once per machine (not
  something to script or hand a token through chat for).
- Optional: `gh auth setup-git` - lets `gh`'s stored credentials satisfy `git push` too,
  so you're not prompted separately by Git's own credential helper.

## Why

Every step in this project builds on the last one compiling and passing CI. Catching a
break immediately - one commit at a time - is cheap. Finding out three tabs later that
step 2 broke the build is not.
