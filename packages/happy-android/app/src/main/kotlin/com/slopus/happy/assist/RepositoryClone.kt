package com.slopus.happy.assist

import com.slopus.happy.model.MachineMetadata
import com.slopus.happy.sync.BashResult

/**
 * "Clone repository" from the new-session project picker: fetches a git
 * repository into the computer's workspace so a session can start in it,
 * without the person ever touching the computer. Meant for cloud hosts,
 * where nothing is on disk until the phone asks for it.
 *
 * It runs through the machine `bash` RPC like the worktree commands
 * (`ui/home/Worktrees.kt`). The URL is checked and single-quoted, the folder
 * name comes from the URL and is limited to safe characters, and a folder
 * that is already a checkout is reused rather than cloned again.
 */
object RepositoryClone {
    data class Target(val url: String, val name: String)

    /** Within the app's 50 s wait for a machine RPC; a longer clone is retried. */
    const val TIMEOUT_MS = 45_000L

    private val HTTPS = Regex("^https://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9._~/-]+$")
    private val SCP = Regex("^git@[A-Za-z0-9.-]+:[A-Za-z0-9._~/-]+$")
    private val SSH = Regex("^ssh://([A-Za-z0-9._-]+@)?[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9._~/-]+$")
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    /** A target for [input], or null when it isn't an https, ssh or `git@` repository URL. */
    fun parse(input: String): Target? {
        val url = input.trim().removeSuffix("/")
        if (!(HTTPS.matches(url) || SCP.matches(url) || SSH.matches(url))) return null
        val name = url.substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
        if (!NAME.matches(name) || name == "." || name == "..") return null
        return Target(url, name)
    }

    /**
     * The shell command: clone into `$HAPPY_WORKSPACE` (the cloud host
     * image's) or `~/workspace`, reuse an existing checkout, and print the
     * checkout's absolute path as the last line.
     */
    fun command(target: Target): String {
        val url = quote(target.url)
        val name = quote(target.name)
        val partial = quote(".${target.name}.cloning")
        // Cloned beside the target and renamed on success, so a clone cut off
        // by the timeout never leaves a half checkout that a retry would reuse.
        return "ws=\"\${HAPPY_WORKSPACE:-\$HOME/workspace}\"; mkdir -p \"\$ws\" && cd \"\$ws\" && " +
            "{ [ -d $name/.git ] || { rm -rf $partial && GIT_TERMINAL_PROMPT=0 git clone --depth 50 -- $url $partial && mv $partial $name; }; } && " +
            "printf '%s/%s\\n' \"\$PWD\" $name"
    }

    /** The checkout's path from a successful run, else null. */
    fun checkoutPath(result: BashResult, target: Target): String? {
        if (!result.success || result.exitCode != 0) return null
        return result.stdout.lineSequence().map(String::trim).lastOrNull { it.endsWith("/" + target.name) && it.startsWith("/") }
    }

    /** The computer's own words for a failed clone. */
    fun failure(result: BashResult): String =
        listOf(result.stderr, result.error.orEmpty(), result.stdout).map(String::trim).firstOrNull { it.isNotEmpty() }?.lines()?.takeLast(6)?.joinToString("\n")
            ?: "git clone failed"

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}

/** An always-on cloud host (`hostKind: "cloud"`, set by `HAPPY_HOST_KIND`). */
val MachineMetadata?.isCloudHost: Boolean
    get() = (this?.raw?.get("hostKind") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content == "cloud"
