package com.slopus.happy.assist

import com.slopus.happy.model.HomeModelFixtures.machine
import com.slopus.happy.model.HomeModelFixtures.machineMetadata
import com.slopus.happy.model.CLONE_REPOSITORY_KEY
import com.slopus.happy.model.HomeModelFixtures.cliAvailability
import com.slopus.happy.model.NewSessionDraft
import com.slopus.happy.model.NewSessionLabels
import com.slopus.happy.model.NewSessionPage
import com.slopus.happy.model.resolveNewSessionOptions
import com.slopus.happy.sync.BashResult
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Clone repository: only repository URLs, safe folder names, a command that
 * quotes everything, reuses an existing checkout and never leaves a half
 * clone behind; and the cloud host's label.
 */
class RepositoryCloneTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun acceptsRepositoryUrlsOnly() {
        assertEquals(RepositoryClone.Target("https://github.com/slopus/happy", "happy"), RepositoryClone.parse(" https://github.com/slopus/happy/ "))
        assertEquals("happy", RepositoryClone.parse("https://github.com/slopus/happy.git")?.name)
        assertEquals("repo", RepositoryClone.parse("git@github.com:owner/repo.git")?.name)
        assertEquals("repo", RepositoryClone.parse("ssh://git@example.com:2222/owner/repo")?.name)
        assertNull(RepositoryClone.parse("file:///etc"))
        assertNull(RepositoryClone.parse("http://github.com/a/b"))
        assertNull(RepositoryClone.parse("https://github.com/a/b; rm -rf ~"))
        assertNull(RepositoryClone.parse("https://github.com/a/\$(whoami)"))
        assertNull(RepositoryClone.parse("--upload-pack=evil"))
        assertNull(RepositoryClone.parse("https://github.com/a/.."))
    }

    private fun run(command: String, workspace: File): BashResult {
        val process = ProcessBuilder("/bin/sh", "-c", command)
            .also { it.environment()["HAPPY_WORKSPACE"] = workspace.path }
            .start()
        process.waitFor(60, TimeUnit.SECONDS)
        return BashResult(
            success = process.exitValue() == 0,
            stdout = process.inputStream.bufferedReader().readText(),
            stderr = process.errorStream.bufferedReader().readText(),
            exitCode = process.exitValue(),
        )
    }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder("git", "-c", "user.email=t@t", "-c", "user.name=t", *args).directory(dir).start()
        p.waitFor(30, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue())
    }

    @Test
    fun clonesIntoTheWorkspaceAndReusesTheCheckout() {
        val origin = folder.newFolder("origin")
        git(origin, "init", "-q")
        File(origin, "README.md").writeText("hello")
        git(origin, "add", ".")
        git(origin, "commit", "-q", "-m", "init")
        val workspace = File(folder.root, "ws")
        val target = RepositoryClone.Target(origin.toURI().toString(), "it's here")

        val first = run(RepositoryClone.command(target), workspace)
        val path = RepositoryClone.checkoutPath(first, target)
        assertNotNull(first.stderr, path)
        assertEquals(File(workspace, "it's here").canonicalPath, File(path!!).canonicalPath)
        assertTrue(File(path, "README.md").exists())
        assertFalse(File(workspace, ".it's here.cloning").exists())

        // A second run reuses the checkout instead of cloning again.
        File(path, "local-change").writeText("kept")
        val second = run(RepositoryClone.command(target), workspace)
        assertEquals(path, RepositoryClone.checkoutPath(second, target))
        assertTrue(File(path, "local-change").exists())
    }

    @Test
    fun aFailedCloneLeavesNothingAndSaysWhy() {
        val workspace = File(folder.root, "ws")
        val target = RepositoryClone.Target(File(folder.root, "missing").toURI().toString(), "missing")
        val result = run(RepositoryClone.command(target), workspace)
        assertNull(RepositoryClone.checkoutPath(result, target))
        assertTrue(RepositoryClone.failure(result).isNotBlank())
        assertFalse(File(workspace, "missing").exists())
    }

    @Test
    fun cloudHostsAreLabelledAndOfferClone() {
        val cloud = machine("m1", machineMetadata { put("hostKind", "cloud"); cliAvailability(claude = true) })
        val laptop = machine("m2", machineMetadata { cliAvailability(claude = true) })
        assertTrue(cloud.metadata.isCloudHost)
        assertFalse(laptop.metadata.isCloudHost)

        val labels = NewSessionLabels(online = "online", cloud = "Cloud", cloneRepository = "Clone repository")
        val options = resolveNewSessionOptions(NewSessionDraft(selectedMachineId = "m1"), listOf(cloud, laptop), emptyList(), null, labels = labels)
        assertEquals("Cloud · online", options.machines.find { it.key == "m1" }?.description)
        assertEquals("online", options.machines.find { it.key == "m2" }?.description)
        val (sections, _) = options.sections(NewSessionPage.Project, labels) { "" }
        assertTrue(sections.flatMap { it.options }.any { it.key == CLONE_REPOSITORY_KEY && it.action })

        val offline = resolveNewSessionOptions(NewSessionDraft(selectedMachineId = "m1"), listOf(machine("m1", cloud.metadata, active = false)), emptyList(), null, labels = labels)
        assertFalse(offline.sections(NewSessionPage.Project, labels) { "" }.first.flatMap { it.options }.any { it.key == CLONE_REPOSITORY_KEY })
    }
}
