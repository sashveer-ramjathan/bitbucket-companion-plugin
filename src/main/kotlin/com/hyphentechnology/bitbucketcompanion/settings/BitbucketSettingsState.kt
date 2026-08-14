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
        var email: String = ""

        // Git commit identity override for the Commit action; blank = fall back to global `git config`
        var gitAuthorName: String = ""
        var gitAuthorEmail: String = ""

        var defaultDestBranch: String = "main"
        var watchIntervalSeconds: Int = 15

        // Remembered last-used values so dialogs pre-fill instead of starting blank
        var lastRepo: String = ""
        var lastProjectFilter: String = ""
        var lastCloneDir: String = ""
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
