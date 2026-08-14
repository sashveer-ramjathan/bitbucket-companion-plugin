package com.hyphentechnology.bitbucketcompanion.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * The Bitbucket API token is the only secret this plugin handles - it's stored via
 * IntelliJ's PasswordSafe (OS credential store), never in the XML-persisted settings state
 * and never on disk in plaintext, unlike bb.py's BB_TOKEN-in-.bashrc approach.
 */
object BitbucketCredentials {
    private val attributes = CredentialAttributes(generateServiceName("Bitbucket Companion", "apiToken"))

    fun getToken(): String? = PasswordSafe.instance.getPassword(attributes)

    fun setToken(token: String?) {
        PasswordSafe.instance.set(attributes, if (token.isNullOrBlank()) null else Credentials("bitbucket", token))
    }

    fun clear() = setToken(null)
}
