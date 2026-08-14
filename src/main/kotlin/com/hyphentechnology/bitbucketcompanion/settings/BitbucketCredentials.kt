package com.hyphentechnology.bitbucketcompanion.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Every secret this plugin handles - the manual API token, and the OAuth ("Sign in with
 * Bitbucket") consumer secret + access/refresh tokens - stored via IntelliJ's PasswordSafe (OS
 * credential store), never in the XML-persisted settings state and never on disk in plaintext,
 * unlike bb.py's BB_TOKEN-in-.bashrc approach.
 */
object BitbucketCredentials {
    private val tokenAttributes = CredentialAttributes(generateServiceName("Bitbucket Companion", "apiToken"))
    private val oauthClientSecretAttributes = CredentialAttributes(generateServiceName("Bitbucket Companion", "oauthClientSecret"))
    private val oauthAccessTokenAttributes = CredentialAttributes(generateServiceName("Bitbucket Companion", "oauthAccessToken"))
    private val oauthRefreshTokenAttributes = CredentialAttributes(generateServiceName("Bitbucket Companion", "oauthRefreshToken"))

    fun getToken(): String? = PasswordSafe.instance.getPassword(tokenAttributes)

    fun setToken(token: String?) {
        PasswordSafe.instance.set(tokenAttributes, if (token.isNullOrBlank()) null else Credentials("bitbucket", token))
    }

    fun clear() = setToken(null)

    fun getOAuthClientSecret(): String? = PasswordSafe.instance.getPassword(oauthClientSecretAttributes)

    fun setOAuthClientSecret(secret: String?) {
        PasswordSafe.instance.set(oauthClientSecretAttributes, if (secret.isNullOrBlank()) null else Credentials("bitbucket-oauth", secret))
    }

    fun getOAuthAccessToken(): String? = PasswordSafe.instance.getPassword(oauthAccessTokenAttributes)

    fun setOAuthAccessToken(token: String?) {
        PasswordSafe.instance.set(oauthAccessTokenAttributes, if (token.isNullOrBlank()) null else Credentials("bitbucket-oauth", token))
    }

    fun getOAuthRefreshToken(): String? = PasswordSafe.instance.getPassword(oauthRefreshTokenAttributes)

    fun setOAuthRefreshToken(token: String?) {
        PasswordSafe.instance.set(oauthRefreshTokenAttributes, if (token.isNullOrBlank()) null else Credentials("bitbucket-oauth", token))
    }

    /** Signs out of the Bitbucket-account flow - clears the token pair but keeps the configured client secret so signing back in doesn't need it re-entered. */
    fun clearOAuthSession() {
        setOAuthAccessToken(null)
        setOAuthRefreshToken(null)
    }
}
