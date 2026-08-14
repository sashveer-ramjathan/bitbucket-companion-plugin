package com.hyphentechnology.bitbucketcompanion.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

class BitbucketApiException(message: String) : RuntimeException(message)

data class BbProject(val key: String, val name: String)
data class BbRepo(val slug: String, val projectKey: String, val htmlUrl: String)
data class BbPullRequest(
    val id: Long,
    val state: String,
    val title: String,
    val sourceBranch: String?,
    val destBranch: String?,
    val description: String?,
    val htmlUrl: String?,
    /** Commit hashes on either side - needed to fetch file content for a diff (see [BitbucketApiClient.fileContentAt]). */
    val sourceCommit: String? = null,
    val destCommit: String? = null,
)
data class BbPrStatus(val state: String, val name: String, val url: String?)
data class BbDiffStatEntry(
    val status: String,
    val oldPath: String?,
    val newPath: String?,
    val linesAdded: Int,
    val linesRemoved: Int,
) {
    /** The path to show/use for this entry - the new path unless the file was removed. */
    val displayPath: String get() = newPath ?: oldPath ?: "(unknown)"
}
data class BbComment(
    val id: Long,
    val author: String,
    val createdOn: String,
    val raw: String,
    /** Non-null for an inline (per-line) comment; null for a general PR-level comment. */
    val inlinePath: String? = null,
)
data class BbCommit(
    val hash: String,
    val message: String,
    val author: String,
    val date: String,
    val htmlUrl: String?,
) {
    /** First line of the (often multi-line) commit message. */
    val summary: String get() = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: message.trim()
}
data class BbCommitDetail(
    val hash: String,
    val message: String,
    val author: String,
    val date: String,
    /** First parent's hash - the "old" side when diffing this commit's changes. Null for a repo's root commit. */
    val parentHash: String?,
    val htmlUrl: String?,
)
data class BbPipeline(val buildNumber: Int, val uuid: String, val stateName: String, val resultName: String?)
data class BbPipelineStep(val uuid: String, val name: String, val stateName: String, val resultName: String?)

/** How a [BitbucketApiClient] authenticates - the manual API token, or an OAuth ("Sign in with Bitbucket") access token. */
sealed interface BitbucketAuth {
    /**
     * Basic auth: `identity:secret`, where identity is the Atlassian email or Bitbucket username
     * and secret is the API token. Bitbucket only accepts one of email/username on a given
     * workspace, so [fallbackIdentity] (the other one, if both are configured) is retried once on
     * a 401; whichever succeeds is reported via [onIdentityResolved] so the caller can remember it.
     */
    data class Basic(
        val identity: String,
        val secret: String,
        val fallbackIdentity: String? = null,
        val onIdentityResolved: (String) -> Unit = {},
    ) : BitbucketAuth

    /**
     * Bearer auth via an OAuth access token. [tokenProvider] is invoked at request time (not
     * eagerly) and may block/hit the network to refresh an expired token - safe since every
     * caller already runs off the EDT (see BackgroundTasks.runBackground).
     */
    data class Bearer(val tokenProvider: () -> String) : BitbucketAuth
}

/**
 * Kotlin port of bb.py's request/auth/pagination logic - same Basic-auth-with-email+token
 * scheme, same `next`-link pagination, plus an OAuth Bearer mode bb.py never needed. All calls
 * are synchronous/blocking; callers MUST run them off the EDT (see Task.Backgroundable usages in
 * the UI layer).
 *
 * One deliberate simplification vs. bb.py: pipelineLog() doesn't need bb.py's manual
 * "strip auth header before following the redirect" fix. java.net.http.HttpClient's
 * Redirect.NORMAL policy already drops the Authorization header on a cross-host redirect
 * per its documented behavior - exactly the bug bb.py's urllib had to work around by hand.
 */
class BitbucketApiClient(
    private val workspace: String,
    private val auth: BitbucketAuth,
) {
    companion object {
        private const val API = "https://api.bitbucket.org/2.0"
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Whichever Basic-auth identity last worked (or [BitbucketAuth.Basic.identity], before anything's been tried yet). Unused in Bearer mode. */
    @Volatile
    private var activeIdentity: String? = (auth as? BitbucketAuth.Basic)?.identity

    private fun authHeader(forIdentity: String?): String = when (auth) {
        is BitbucketAuth.Basic -> "Basic " + Base64.getEncoder()
            .encodeToString("${forIdentity ?: auth.identity}:${auth.secret}".toByteArray(Charsets.UTF_8))
        is BitbucketAuth.Bearer -> "Bearer ${auth.tokenProvider()}"
    }

    /**
     * Sends [method] to [url] with [forIdentity]'s Basic auth (ignored in Bearer mode), returning
     * the raw response - no status-code handling, so callers can inspect a 401 before deciding
     * whether to retry.
     */
    private fun send(method: String, url: String, body: String?, forIdentity: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", authHeader(forIdentity))
            .timeout(Duration.ofSeconds(30))
        val req = when (method) {
            "GET" -> builder.GET()
            "POST" -> builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
            "PUT" -> builder.header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
            else -> throw IllegalArgumentException("Unsupported method $method")
        }.build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    /**
     * In Basic mode: sends with [activeIdentity]; if that comes back 401 and a distinct
     * fallbackIdentity is configured, retries once with it, and reports whichever identity
     * actually got a non-401 response via onIdentityResolved. In Bearer mode: just sends once -
     * there's no identity ambiguity to retry, an expired/invalid token is a hard failure.
     */
    private fun sendWithFallback(method: String, url: String, body: String?): HttpResponse<String> {
        val basic = auth as? BitbucketAuth.Basic ?: return send(method, url, body, null)
        val first = send(method, url, body, activeIdentity)
        val fallback = basic.fallbackIdentity
        if (first.statusCode() != 401 || fallback == null || fallback == activeIdentity) return first
        val second = send(method, url, body, fallback)
        if (second.statusCode() != 401) {
            activeIdentity = fallback
            basic.onIdentityResolved(fallback)
        }
        return second
    }

    private fun request(method: String, url: String, body: String? = null): JsonObject {
        val resp = sendWithFallback(method, url, body)
        if (resp.statusCode() >= 400) {
            throw BitbucketApiException("HTTP ${resp.statusCode()} for $url\n${resp.body()}")
        }
        if (resp.body().isNullOrBlank()) return JsonObject()
        return JsonParser.parseString(resp.body()).asJsonObject
    }

    private fun sendBytes(url: String, forIdentity: String?): HttpResponse<ByteArray> {
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", authHeader(forIdentity))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()
        return http.send(req, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun requestBytes(url: String): ByteArray {
        val basic = auth as? BitbucketAuth.Basic
        val first = sendBytes(url, activeIdentity)
        val fallback = basic?.fallbackIdentity
        val resp = if (basic != null && first.statusCode() == 401 && fallback != null && fallback != activeIdentity) {
            val second = sendBytes(url, fallback)
            if (second.statusCode() != 401) {
                activeIdentity = fallback
                basic.onIdentityResolved(fallback)
            }
            second
        } else {
            first
        }
        if (resp.statusCode() >= 400) {
            throw BitbucketApiException("HTTP ${resp.statusCode()} for $url")
        }
        return resp.body()
    }

    private fun paginate(startUrl: String): Sequence<JsonObject> = sequence {
        var url: String? = startUrl
        while (url != null) {
            val page = request("GET", url)
            val values = page.getAsJsonArray("values") ?: JsonArray()
            for (v in values) yield(v.asJsonObject)
            url = page.get("next")?.takeIf { !it.isJsonNull }?.asString
        }
    }

    /**
     * Like [JsonObject.getAsJsonObject], but treats a field that's present-and-explicitly-null
     * the same as a missing one. Gson's own getAsJsonObject() throws a ClassCastException on
     * JsonNull instead of returning null - and Bitbucket routinely sends explicit nulls for
     * optional relations (diffstat's "old"/"new" on an added/removed file, a comment's "inline"
     * on a general comment, a pipeline step's "result" while still running).
     */
    private fun JsonObject.objOrNull(key: String): JsonObject? {
        val el = get(key)
        return if (el == null || el.isJsonNull) null else el.asJsonObject
    }

    private fun normUuid(u: String): String {
        val trimmed = u.trim().trim('{', '}')
        return URLEncoder.encode("{$trimmed}", "UTF-8")
    }

    // ---- account / repos ----

    /**
     * Verifies the configured workspace/email/token can reach the workspace.
     * Throws [BitbucketApiException] on any auth or network failure.
     */
    fun ping() {
        request("GET", "$API/repositories/$workspace?pagelen=1")
    }

    /** Lists every project in the workspace. */
    fun listProjects(): List<BbProject> =
        paginate("$API/workspaces/$workspace/projects?pagelen=100")
            .map { BbProject(it.get("key").asString, it.get("name").asString) }
            .toList()

    /** Lists repos in the workspace, optionally narrowed to a single project key. */
    fun listRepos(projectKey: String? = null): List<BbRepo> {
        val url = if (projectKey.isNullOrBlank()) {
            "$API/repositories/$workspace?pagelen=100"
        } else {
            "$API/repositories/$workspace?q=" +
                URLEncoder.encode("project.key=\"$projectKey\"", "UTF-8") + "&pagelen=100"
        }
        return paginate(url).map { r ->
            val project = r.objOrNull("project")?.get("key")?.asString ?: "-"
            val html = r.objOrNull("links")?.objOrNull("html")?.get("href")?.asString
                ?: "https://bitbucket.org/$workspace/${r.get("slug").asString}"
            BbRepo(r.get("slug").asString, project, html)
        }.toList()
    }

    /** Lists branch names for a repo - used to populate source/destination pickers when opening/editing a PR. */
    fun listBranches(repoSlug: String): List<String> =
        paginate("$API/repositories/$workspace/$repoSlug/refs/branches?pagelen=100")
            .map { it.get("name").asString }
            .toList()

    /** Resolves the browser-facing URL for a single repo. */
    fun repoUrl(repoSlug: String): String {
        val r = request("GET", "$API/repositories/$workspace/$repoSlug")
        return r.objOrNull("links")?.objOrNull("html")?.get("href")?.asString
            ?: "https://bitbucket.org/$workspace/$repoSlug"
    }

    /** Clone URL with credentials embedded for auth - only ever used transiently for the clone call itself. */
    fun cloneUrlWithToken(repoSlug: String): String = when (auth) {
        is BitbucketAuth.Basic -> "https://x-bitbucket-api-token-auth:${auth.secret}@bitbucket.org/$workspace/$repoSlug.git"
        is BitbucketAuth.Bearer -> "https://x-token-auth:${auth.tokenProvider()}@bitbucket.org/$workspace/$repoSlug.git"
    }

    /** Token-free clone URL, written back to `origin` immediately after cloning. */
    fun cloneUrlPlain(repoSlug: String): String =
        "https://bitbucket.org/$workspace/$repoSlug.git"

    // ---- pull requests ----

    /** Opens a new pull request. */
    fun createPullRequest(repoSlug: String, title: String, source: String, dest: String, description: String): BbPullRequest {
        val body = JsonObject().apply {
            addProperty("title", title)
            add("source", JsonObject().apply { add("branch", JsonObject().apply { addProperty("name", source) }) })
            add("destination", JsonObject().apply { add("branch", JsonObject().apply { addProperty("name", dest) }) })
            addProperty("description", description)
            addProperty("close_source_branch", true)
        }
        return parsePr(request("POST", "$API/repositories/$workspace/$repoSlug/pullrequests", body.toString()))
    }

    /** Lists PRs for a repo, filtered by state (`OPEN` / `MERGED` / `DECLINED`). */
    fun listPullRequests(repoSlug: String, state: String = "OPEN"): List<BbPullRequest> =
        paginate("$API/repositories/$workspace/$repoSlug/pullrequests?state=$state&pagelen=50")
            .map { parsePr(it) }
            .toList()

    /** Fetches one PR's full detail. */
    fun getPullRequest(repoSlug: String, id: Long): BbPullRequest =
        parsePr(request("GET", "$API/repositories/$workspace/$repoSlug/pullrequests/$id"))

    /** Updates title/description/destination on an existing PR; null/blank params are left unchanged. */
    fun updatePullRequest(repoSlug: String, id: Long, title: String?, description: String?, dest: String?): BbPullRequest {
        val body = JsonObject()
        if (!title.isNullOrBlank()) body.addProperty("title", title)
        if (description != null) body.addProperty("description", description)
        if (!dest.isNullOrBlank()) {
            body.add("destination", JsonObject().apply { add("branch", JsonObject().apply { addProperty("name", dest) }) })
        }
        return parsePr(request("PUT", "$API/repositories/$workspace/$repoSlug/pullrequests/$id", body.toString()))
    }

    /** The build/check statuses shown as the PR's green/red checks. */
    fun pullRequestStatuses(repoSlug: String, id: Long): List<BbPrStatus> =
        paginate("$API/repositories/$workspace/$repoSlug/pullrequests/$id/statuses")
            .map { BbPrStatus(it.get("state").asString, it.get("name").asString, it.get("url")?.takeIf { j -> !j.isJsonNull }?.asString) }
            .toList()

    /** Per-file change summary for a PR: status (added/removed/modified/renamed) and old/new paths. */
    fun diffstat(repoSlug: String, id: Long): List<BbDiffStatEntry> =
        paginate("$API/repositories/$workspace/$repoSlug/pullrequests/$id/diffstat?pagelen=100")
            .map { parseDiffstatEntry(it) }.toList()

    /** Per-file change summary for a single commit (diffed against its first parent). */
    fun commitDiffstat(repoSlug: String, commitHash: String): List<BbDiffStatEntry> =
        paginate("$API/repositories/$workspace/$repoSlug/diffstat/$commitHash?pagelen=100")
            .map { parseDiffstatEntry(it) }.toList()

    private fun parseDiffstatEntry(d: JsonObject): BbDiffStatEntry = BbDiffStatEntry(
        status = d.get("status")?.asString ?: "modified",
        oldPath = d.objOrNull("old")?.get("path")?.asString,
        newPath = d.objOrNull("new")?.get("path")?.asString,
        linesAdded = d.get("lines_added")?.asInt ?: 0,
        linesRemoved = d.get("lines_removed")?.asInt ?: 0,
    )

    /** Full detail for one commit, including its first parent's hash (needed to build a diff against it). */
    fun getCommit(repoSlug: String, hash: String): BbCommitDetail {
        val c = request("GET", "$API/repositories/$workspace/$repoSlug/commit/$hash")
        val parents = c.get("parents")?.takeIf { !it.isJsonNull }?.asJsonArray
        return BbCommitDetail(
            hash = c.get("hash")?.asString ?: hash,
            message = c.get("message")?.asString ?: "",
            author = c.objOrNull("author")?.objOrNull("user")?.get("display_name")?.asString
                ?: c.objOrNull("author")?.get("raw")?.asString
                ?: "?",
            date = c.get("date")?.asString ?: "",
            parentHash = parents?.firstOrNull()?.asJsonObject?.get("hash")?.asString,
            htmlUrl = c.objOrNull("links")?.objOrNull("html")?.get("href")?.asString,
        )
    }

    /** Raw file content at a specific commit, or null if the file doesn't exist there (added/removed side of a diff). */
    fun fileContentAt(repoSlug: String, commitHash: String, path: String): String? = try {
        String(requestBytes("$API/repositories/$workspace/$repoSlug/src/$commitHash/${encodePath(path)}"), Charsets.UTF_8)
    } catch (e: BitbucketApiException) {
        if (e.message?.contains("404") == true) null else throw e
    }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }

    /** Comments on a PR (general and inline), newest-last, excluding soft-deleted ones. */
    fun listComments(repoSlug: String, id: Long): List<BbComment> =
        paginate("$API/repositories/$workspace/$repoSlug/pullrequests/$id/comments?pagelen=50")
            .filter { it.get("deleted")?.asBoolean != true }
            .map { c ->
                BbComment(
                    id = c.get("id").asLong,
                    author = c.objOrNull("user")?.get("display_name")?.asString ?: "?",
                    createdOn = c.get("created_on")?.asString ?: "",
                    raw = c.objOrNull("content")?.get("raw")?.asString ?: "",
                    inlinePath = c.objOrNull("inline")?.get("path")?.asString,
                )
            }.toList()

    /** Commits that make up a PR, in the order Bitbucket returns them (newest first). */
    fun listCommits(repoSlug: String, id: Long): List<BbCommit> =
        paginate("$API/repositories/$workspace/$repoSlug/pullrequests/$id/commits?pagelen=50")
            .map { c ->
                BbCommit(
                    hash = c.get("hash")?.asString ?: "?",
                    message = c.get("message")?.asString ?: "",
                    author = c.objOrNull("author")?.objOrNull("user")?.get("display_name")?.asString
                        ?: c.objOrNull("author")?.get("raw")?.asString
                        ?: "?",
                    date = c.get("date")?.asString ?: "",
                    htmlUrl = c.objOrNull("links")?.objOrNull("html")?.get("href")?.asString,
                )
            }.toList()

    /** Posts a new general (non-inline) PR comment. */
    fun addComment(repoSlug: String, id: Long, text: String): BbComment {
        val body = JsonObject().apply { add("content", JsonObject().apply { addProperty("raw", text) }) }
        val c = request("POST", "$API/repositories/$workspace/$repoSlug/pullrequests/$id/comments", body.toString())
        return BbComment(
            id = c.get("id").asLong,
            author = c.objOrNull("user")?.get("display_name")?.asString ?: "?",
            createdOn = c.get("created_on")?.asString ?: "",
            raw = c.objOrNull("content")?.get("raw")?.asString ?: "",
        )
    }

    private fun parsePr(pr: JsonObject): BbPullRequest {
        val source = pr.objOrNull("source")?.objOrNull("branch")?.get("name")?.asString
        val dest = pr.objOrNull("destination")?.objOrNull("branch")?.get("name")?.asString
        val sourceCommit = pr.objOrNull("source")?.objOrNull("commit")?.get("hash")?.asString
        val destCommit = pr.objOrNull("destination")?.objOrNull("commit")?.get("hash")?.asString
        val html = pr.objOrNull("links")?.objOrNull("html")?.get("href")?.asString
        return BbPullRequest(
            id = pr.get("id").asLong,
            state = pr.get("state")?.asString ?: "-",
            title = pr.get("title")?.asString ?: "",
            sourceBranch = source,
            destBranch = dest,
            description = pr.get("description")?.takeIf { !it.isJsonNull }?.asString,
            htmlUrl = html,
            sourceCommit = sourceCommit,
            destCommit = destCommit,
        )
    }

    // ---- pipelines ----

    /** Most recent pipeline runs for a repo, newest first. */
    fun listPipelines(repoSlug: String, limit: Int = 10): List<BbPipeline> {
        val page = request("GET", "$API/repositories/$workspace/$repoSlug/pipelines/?sort=-created_on&pagelen=$limit")
        val values = page.getAsJsonArray("values") ?: JsonArray()
        return values.map { v ->
            val o = v.asJsonObject
            val state = o.objOrNull("state")
            BbPipeline(
                buildNumber = o.get("build_number").asInt,
                uuid = o.get("uuid").asString,
                stateName = state?.get("name")?.asString ?: "-",
                resultName = state?.objOrNull("result")?.get("name")?.asString,
            )
        }
    }

    /** Steps of one pipeline run, in execution order. */
    fun pipelineSteps(repoSlug: String, pipelineUuid: String): List<BbPipelineStep> {
        val puuid = normUuid(pipelineUuid)
        return paginate("$API/repositories/$workspace/$repoSlug/pipelines/$puuid/steps/").map { s ->
            val state = s.objOrNull("state")
            BbPipelineStep(
                uuid = s.get("uuid").asString,
                name = s.get("name")?.asString ?: "-",
                stateName = state?.get("name")?.asString ?: "-",
                resultName = state?.objOrNull("result")?.get("name")?.asString,
            )
        }.toList()
    }

    /**
     * Full log text for one pipeline step. Relies on [http]'s Redirect.NORMAL policy to drop
     * the Authorization header on the endpoint's cross-host redirect - see the class doc.
     */
    fun pipelineLog(repoSlug: String, pipelineUuid: String, stepUuid: String): String {
        val puuid = normUuid(pipelineUuid)
        val suuid = normUuid(stepUuid)
        val bytes = requestBytes("$API/repositories/$workspace/$repoSlug/pipelines/$puuid/steps/$suuid/log")
        return String(bytes, Charsets.UTF_8)
    }

    /** Browser-facing URL for one pipeline run. */
    fun pipelineWebUrl(repoSlug: String, pipelineUuid: String): String {
        val puuid = normUuid(pipelineUuid)
        val p = request("GET", "$API/repositories/$workspace/$repoSlug/pipelines/$puuid")
        val buildNumber = p.get("build_number")?.takeIf { !it.isJsonNull }?.asInt
        return if (buildNumber != null) {
            "https://bitbucket.org/$workspace/$repoSlug/pipelines/results/$buildNumber"
        } else {
            p.objOrNull("links")?.objOrNull("html")?.get("href")?.asString ?: ""
        }
    }
}
