# Bitbucket Companion

[![Build](https://github.com/sashveer-ramjathan/bitbucket-companion-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/sashveer-ramjathan/bitbucket-companion-plugin/actions/workflows/build.yml)

An IntelliJ IDEA plugin that gives the Bitbucket Cloud workflow this team already scripted in
[`bb.py`](https://github.com/sashveer-ramjathan) a real GUI: browse and clone repos, see git
status and pull/branch/commit/push per repo, create and watch pull requests, and browse
pipelines with a live-updating step list and log viewer - all backed by an Atlassian API token
stored in the IDE's secure credential store instead of a plaintext dotfile.

Targets IntelliJ IDEA **Community** edition (no Ultimate-only APIs used).

## Features

| Area | What it covers |
|---|---|
| **Repos** | List/filter repos by project, open or copy a repo's URL, clone one repo or all of them, verify a folder of clones |
| **Local / Git Status** | Ahead/behind/diverged/dirty status per repo, safe fast-forward-only pull, branch create/list/switch, commit, push |
| **Pull Requests** | List/filter by state, create, view/edit, check build statuses with live watch, open in browser |
| **Pipelines** | List recent runs, view/watch steps live, embedded log viewer (ANSI-stripped), open in browser |

Every credential/dialog input is remembered (workspace, email, git author identity, default PR
destination branch, last-used repo/project filter) via IntelliJ's persistent settings - nothing
needs re-entering between sessions.

## Installing

### Option A - one-time manual install

1. Download the latest `bitbucket-companion-plugin-*.zip` from this repo's
   [Releases](../../releases) page (or build it yourself, see below).
2. In IntelliJ IDEA: **Settings/Preferences → Plugins → ⚙️ → Install Plugin from Disk...**
3. Pick the zip, restart the IDE when prompted.

### Option B - self-updating (recommended)

Do the manual install once (Option A), then add this repo as a **Custom Plugin Repository** so
future releases show up as an in-IDE "Update available" prompt instead of a manual re-download:

1. Get a **fine-grained GitHub Personal Access Token** scoped to just this repo with
   **Contents: Read-only** permission (Settings → Developer settings → Personal access tokens →
   Fine-grained tokens on GitHub).
2. In IntelliJ IDEA: **Settings/Preferences → Plugins → ⚙️ → Manage Plugin Repositories → +**
3. Add:
   ```
   https://<YOUR_TOKEN>@raw.githubusercontent.com/sashveer-ramjathan/bitbucket-companion-plugin/plugin-repo/updatePlugins.xml
   ```
4. From then on, new tagged releases appear under **Settings → Plugins → Updates** automatically
   (checked periodically by the IDE) - one click to update, no manual zip downloads.

This is a one-click **update-available prompt**, not a fully silent background install -
IntelliJ doesn't allow silent installs of non-Marketplace plugins; that's a platform security
boundary, not a limitation of this setup.

## Setting up credentials

1. **Settings/Preferences → Tools → Bitbucket Companion**
2. Fill in your Bitbucket **workspace**, either your **Atlassian account email** or your
   **Bitbucket username** (both work as the Basic-auth identity paired with the token - username
   is used if you fill in both), and an **API token**
   (see [Atlassian's API token docs](https://support.atlassian.com/bitbucket-cloud/docs/using-api-tokens/)).
   Required token scopes: `read:repository`, `write:repository`, `read:pullrequest`,
   `write:pullrequest`, `read:pipeline` (add `write:pipeline` to trigger/stop pipelines from
   elsewhere).
3. Click **Test Connection** to confirm.

The token is stored via IntelliJ's `PasswordSafe` (OS-level secure storage - Windows Credential
Manager / macOS Keychain / Secret Service), never written to a plaintext file. Everything else
(workspace, email, git author overrides, defaults) is stored in IntelliJ's own settings, also
local to your machine.

## Building from source

Requires only a JDK (21+) - the Gradle wrapper handles the rest, no separate Gradle or IntelliJ
install needed.

```bash
./gradlew build          # compile + run unit tests
./gradlew runIde         # launch a sandboxed IDE with the plugin loaded, for manual testing
./gradlew buildPlugin    # produce the installable zip in build/distributions/
```

## Development workflow

This repo commits in small, verified steps: commit → push → watch the `Build` GitHub Actions
workflow go green → move on (see `.claude/skills/dev-cycle/` if you're using Claude Code).
Don't stack new work on top of a red pipeline.

Architecture notes, command-to-GUI mapping, and design trade-offs are documented inline in the
source (KDoc on every public class/function) - start with `BitbucketApiClient.kt` (HTTP/API
layer) and `GitOps.kt` (git subprocess layer).

## Contributing

- **Found a bug or something behaving oddly?** Open an [issue](../../issues/new) - include what
  you did, what you expected, and what happened instead. A screenshot of the tool window helps
  for UI issues.
- **Have an idea or want something added?** Open an [issue](../../issues/new) describing the use
  case (not just the feature) - what workflow it'd replace or unblock. Feature requests and bugs
  share the same tracker; no special label required to start.
- **Want to submit a change yourself?** Fork the repo, branch off `master`, and open a pull
  request. `master` is protected - direct pushes and merges require a PR with at least one
  approval (repo admins can bypass this; that's intentional, not a bug you need to work around).
  The `Build` workflow (compile + tests) runs automatically on every PR - keep it green.
- Follow the existing code style: KDoc on public classes/functions, and see
  `.claude/skills/dev-cycle/` for the commit → push → watch-CI loop this repo uses.

## License

[MIT](LICENSE)
