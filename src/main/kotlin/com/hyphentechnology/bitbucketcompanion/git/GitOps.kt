package com.hyphentechnology.bitbucketcompanion.git

import java.io.File
import java.util.concurrent.TimeUnit

data class GitResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

data class RepoStatus(
    val name: String,
    val state: String,
    val dirty: Boolean,
    val detail: String = "",
)

data class BranchEntry(val name: String, val current: Boolean, val remote: Boolean)

/**
 * Subprocess-based git operations, ported from bb.py's `_git()` helper and the safety rules
 * built into `status`/`pull`/`pull-all`/`branch`/`switch`/`commit`/`push`: never touch a repo
 * with uncommitted changes, always pull `--ff-only`. Deliberately shells out to system `git`
 * rather than using Git4Idea, to keep behavior identical to bb.py (see plan notes).
 */
object GitOps {

    /** Runs `git -C <dir> <args>`, optionally with extra environment variables (e.g. GIT_AUTHOR_*). */
    fun run(dir: File, vararg args: String, timeoutSeconds: Long = 60, env: Map<String, String> = emptyMap()): GitResult {
        val pb = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
        if (env.isNotEmpty()) pb.environment().putAll(env)
        val process = pb.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return GitResult(-1, stdout, "git command timed out after ${timeoutSeconds}s")
        }
        return GitResult(process.exitValue(), stdout, stderr)
    }

    /** True if the working tree has any uncommitted changes (tracked or untracked). */
    fun isDirty(dir: File): Boolean = run(dir, "status", "--porcelain").stdout.isNotBlank()

    /** Clones [cloneUrl] (may embed a token) into [target]. Caller is responsible for stripping the token afterward. */
    fun clone(cloneUrl: String, target: File): GitResult {
        val process = ProcessBuilder("git", "clone", cloneUrl, target.absolutePath).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        process.waitFor(300, TimeUnit.SECONDS)
        return GitResult(process.exitValue(), stdout, stderr)
    }

    /** Rewrites `origin` to a token-free URL - called immediately after [clone]. */
    fun setOriginUrl(dir: File, plainUrl: String): GitResult =
        run(dir, "remote", "set-url", "origin", plainUrl)

    /** Fetches, then reports ahead/behind/diverged/up-to-date + dirty state relative to upstream. */
    fun status(dir: File): RepoStatus {
        val name = dir.name
        val fetch = run(dir, "fetch", "--quiet")
        if (!fetch.ok) {
            val err = fetch.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "fetch failed"
            return RepoStatus(name, "fetch-fail", dirty = isDirty(dir), detail = err)
        }
        val dirty = isDirty(dir)
        val upstream = run(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        if (!upstream.ok) {
            return RepoStatus(name, "no-upstream", dirty)
        }
        val counts = run(dir, "rev-list", "--left-right", "--count", "HEAD...@{u}").stdout.trim().split(Regex("\\s+"))
        val ahead = counts.getOrNull(0)?.toIntOrNull() ?: 0
        val behind = counts.getOrNull(1)?.toIntOrNull() ?: 0
        val state = when {
            ahead == 0 && behind == 0 -> "up-to-date"
            ahead > 0 && behind > 0 -> "diverged (+$ahead/-$behind)"
            ahead > 0 -> "ahead +$ahead"
            else -> "behind -$behind"
        }
        return RepoStatus(name, state, dirty)
    }

    /** Fast-forward-only pull; refuses (returns a synthetic failure result) if the tree is dirty. */
    fun pull(dir: File): GitResult {
        if (isDirty(dir)) return GitResult(-1, "", "SKIP: uncommitted changes")
        return run(dir, "pull", "--ff-only", timeoutSeconds = 120)
    }

    /** Creates and switches to a new branch. */
    fun createBranch(dir: File, name: String): GitResult = run(dir, "checkout", "-b", name)

    /** Switches to an existing branch; git itself refuses if it would clobber conflicting local changes. */
    fun switchBranch(dir: File, name: String): GitResult = run(dir, "checkout", name)

    /** Local branches, or local + remote-tracking branches, with the current one flagged. */
    fun listBranches(dir: File, localOnly: Boolean): List<BranchEntry> {
        val args = if (localOnly) arrayOf("branch") else arrayOf("branch", "-a")
        val out = run(dir, *args).stdout
        return out.lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val current = line.trimStart().startsWith("*")
            val cleaned = line.removePrefix("*").trim()
            BranchEntry(cleaned, current, remote = cleaned.startsWith("remotes/"))
        }
    }

    /**
     * Stages everything (`git add -A`) and commits. No-ops cleanly (ok result, no error) if
     * there's nothing to commit. [authorName]/[authorEmail] override the commit author via
     * GIT_AUTHOR_* env vars when non-blank; otherwise falls back to the global `git config`.
     */
    fun commit(dir: File, message: String, authorName: String?, authorEmail: String?): GitResult {
        if (!isDirty(dir)) return GitResult(0, "NOTHING TO COMMIT", "")
        val add = run(dir, "add", "-A")
        if (!add.ok) return add
        val env = buildMap {
            if (!authorName.isNullOrBlank()) put("GIT_AUTHOR_NAME", authorName)
            if (!authorEmail.isNullOrBlank()) put("GIT_AUTHOR_EMAIL", authorEmail)
        }
        return run(dir, "commit", "-m", message, env = env)
    }

    /** Pushes the current branch, automatically setting the upstream on a first push. */
    fun push(dir: File): GitResult {
        val branch = run(dir, "rev-parse", "--abbrev-ref", "HEAD").stdout.trim()
        if (branch.isBlank() || branch == "HEAD") return GitResult(-1, "", "detached HEAD, not on a branch")
        val upstream = run(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        return if (!upstream.ok) {
            run(dir, "push", "--set-upstream", "origin", branch, timeoutSeconds = 120)
        } else {
            run(dir, "push", timeoutSeconds = 120)
        }
    }

    /** Returns (ok, branch, commitCount) - same fields bb.py's `verify` prints per repo. */
    fun verify(dir: File): Triple<Boolean, String, String> {
        val branch = run(dir, "rev-parse", "--abbrev-ref", "HEAD")
        val count = run(dir, "rev-list", "--count", "HEAD")
        return Triple(branch.ok, branch.stdout.trim().ifEmpty { "?" }, count.stdout.trim().ifEmpty { "?" })
    }
}
