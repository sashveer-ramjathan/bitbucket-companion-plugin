package com.hyphentechnology.bitbucketcompanion.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Everything except the API token (see [BitbucketCredentials]) - persisted so dialogs never
 * need to be re-filled: workspace/email, git commit identity overrides, and remembered
 * defaults/last-used values for the various tabs and dialogs.
 */
@State(
    name = "com.hyphentechnology.bitbucketcompanion.settings.BitbucketSettingsState",
    storages = [Storage("bitbucket-companion.xml")]
)
@Service(Service.Level.APP)
class BitbucketSettingsState : PersistentStateComponent<BitbucketSettingsState.State> {

    class State {
        var workspace: String = ""

        // Auth identity: either works as the Basic-auth username half alongside the API token.
        // Username is preferred when both are set (see BackgroundTasks.buildApiClient).
        var email: String = ""
        var username: String = ""

        // Git commit identity override for the Commit action; blank = fall back to global `git config`
        var gitAuthorName: String = ""
        var gitAuthorEmail: String = ""

        var defaultDestBranch: String = "main"
        var watchIntervalSeconds: Int = 15

        // Remembered last-used values so dialogs pre-fill instead of starting blank
        var lastRepo: String = ""
        var lastProjectFilter: String = ""
        var lastCloneDir: String = ""

        // Repos tab slug filters - comma-separated keywords, matched case-insensitively against
        // the slug substring. A repo is shown if its slug contains at least one include keyword
        // (or the include list is empty) AND contains none of the exclude keywords.
        var slugIncludeFilter: String = ""
        var slugExcludeFilter: String = ""

        /** The Basic-auth identity to pair with the API token - username if set, else email. */
        fun authIdentity(): String = username.ifBlank { email }

        /** True once there's enough to attempt authenticating: workspace + (username or email). */
        fun hasIdentity(): Boolean = workspace.isNotBlank() && authIdentity().isNotBlank()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): BitbucketSettingsState = service()
    }
}
