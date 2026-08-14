package com.hyphentechnology.bitbucketcompanion.settings

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BitbucketOAuthException(message: String) : RuntimeException(message)

/**
 * Bitbucket Cloud OAuth 2.0 ("Sign in with Bitbucket") - the alternative to the manual API
 * token: opens the system browser to Bitbucket's consent screen, catches the redirect on a
 * short-lived local HTTP listener, and exchanges the resulting code for an access/refresh token
 * pair.
 *
 * Requires an OAuth consumer registered under this workspace's Settings > OAuth consumers -
 * needs workspace admin rights to create. Its Callback URL must be set to exactly [CALLBACK_URL]
 * (a fixed port, since Bitbucket matches it verbatim - it can't be a random OS-assigned one).
 * Bitbucket Cloud's OAuth implementation doesn't support PKCE-only public clients, so both the
 * consumer's Key (client ID) and Secret are required, unlike a typical desktop-app OAuth flow.
 *
 * All calls here are network/blocking - callers MUST run them off the EDT.
 */
object BitbucketOAuthClient {

    private const val CALLBACK_PORT = 47823
    const val CALLBACK_URL = "http://localhost:$CALLBACK_PORT/callback"
    private const val AUTHORIZE_URL = "https://bitbucket.org/site/oauth2/authorize"
    private const val TOKEN_URL = "https://bitbucket.org/site/oauth2/access_token"

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    data class TokenResult(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

    /**
     * Runs the full interactive login: starts the local callback listener, opens the browser,
     * waits up to [timeoutSeconds] for the user to approve or deny, then exchanges the code for
     * tokens. Throws [BitbucketOAuthException] on timeout, denial, or any request failure.
     */
    fun login(clientId: String, clientSecret: String, timeoutSeconds: Long = 180): TokenResult {
        val codeFuture = CompletableFuture<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", CALLBACK_PORT), 0)
        server.createContext("/callback") { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery)
            val code = params["code"]
            val error = params["error"]
            val html = if (code != null) {
                "<html><body><h3>Signed in to Bitbucket - you can close this tab.</h3></body></html>"
            } else {
                "<html><body><h3>Sign-in failed${if (error != null) ": $error" else ""} - you can close this tab.</h3></body></html>"
            }
            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            if (code != null) codeFuture.complete(code) else codeFuture.completeExceptionally(BitbucketOAuthException(error ?: "Sign-in was denied."))
        }
        server.start()
        try {
            runCatching { Desktop.getDesktop().browse(URI("$AUTHORIZE_URL?client_id=$clientId&response_type=code")) }
                .onFailure { throw BitbucketOAuthException("Couldn't open the browser: ${it.message}") }

            val code = try {
                codeFuture.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                throw BitbucketOAuthException("Timed out waiting for sign-in - no response after ${timeoutSeconds}s.")
            } catch (e: ExecutionException) {
                throw BitbucketOAuthException(e.cause?.message ?: "Sign-in was denied.")
            }

            return tokenRequest(clientId, clientSecret, "grant_type=authorization_code&code=$code")
        } finally {
            server.stop(0)
        }
    }

    /** Exchanges a refresh token for a fresh access token (Bitbucket typically rotates the refresh token too - always persist both from the result). */
    fun refresh(clientId: String, clientSecret: String, refreshToken: String): TokenResult =
        tokenRequest(clientId, clientSecret, "grant_type=refresh_token&refresh_token=$refreshToken")

    /**
     * A currently-valid access token for API/git calls - returns the cached one if it's not
     * close to expiring, otherwise refreshes first. Network/blocking - background thread only.
     */
    fun currentAccessToken(): String {
        val state = BitbucketSettingsState.getInstance().state
        val nowSec = System.currentTimeMillis() / 1000
        val cached = BitbucketCredentials.getOAuthAccessToken()
        if (!cached.isNullOrBlank() && state.oauthAccessTokenExpiresAtEpochSec - nowSec > 60) {
            return cached
        }
        val refreshToken = BitbucketCredentials.getOAuthRefreshToken()
            ?: throw BitbucketOAuthException("Not signed in to Bitbucket - sign in from Settings > Tools > Bitbucket Companion.")
        val clientId = state.oauthClientId.ifBlank { throw BitbucketOAuthException("Missing OAuth client ID.") }
        val clientSecret = BitbucketCredentials.getOAuthClientSecret() ?: throw BitbucketOAuthException("Missing OAuth client secret.")
        val result = refresh(clientId, clientSecret, refreshToken)
        BitbucketCredentials.setOAuthAccessToken(result.accessToken)
        BitbucketCredentials.setOAuthRefreshToken(result.refreshToken)
        state.oauthAccessTokenExpiresAtEpochSec = nowSec + result.expiresInSeconds
        return result.accessToken
    }

    private fun tokenRequest(clientId: String, clientSecret: String, form: String): TokenResult {
        val auth = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))
        val req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
            .header("Authorization", "Basic $auth")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 400) {
            throw BitbucketOAuthException("Token request failed: HTTP ${resp.statusCode()} - ${resp.body()}")
        }
        val json = JsonParser.parseString(resp.body()).asJsonObject
        val access = json.get("access_token")?.takeIf { !it.isJsonNull }?.asString
            ?: throw BitbucketOAuthException("No access_token in Bitbucket's response.")
        val refreshToken = json.get("refresh_token")?.takeIf { !it.isJsonNull }?.asString
            ?: throw BitbucketOAuthException("No refresh_token in Bitbucket's response.")
        val expiresIn = json.get("expires_in")?.takeIf { !it.isJsonNull }?.asLong ?: 3600L
        return TokenResult(access, refreshToken, expiresIn)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split("&").filter { it.isNotBlank() }.associate { pair ->
            val parts = pair.split("=", limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to (parts.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: "")
        }
}
