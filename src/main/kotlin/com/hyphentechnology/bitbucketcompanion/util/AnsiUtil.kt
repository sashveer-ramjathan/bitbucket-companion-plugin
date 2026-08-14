package com.hyphentechnology.bitbucketcompanion.util

/**
 * Strips ANSI color/cursor escape codes from Bitbucket pipeline log output so it renders as
 * plain text instead of garbage in a Swing text component.
 *
 * Builds the ESC control character from its code point (27) rather than a `\u`-style string
 * escape, since escape/control characters can be lossy to author directly in some editing
 * pipelines - safer to construct it explicitly at runtime.
 */
object AnsiUtil {
    private val ESC: Char = 27.toChar()
    private val ANSI_REGEX = Regex(Regex.escape(ESC.toString()) + "\\[[0-9;]*[a-zA-Z]")

    fun strip(text: String): String = ANSI_REGEX.replace(text, "")
}
