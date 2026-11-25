package io.github.whywood.superpi.runtime

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeWorkspacesTest {
    private lateinit var root: File
    private lateinit var homeDir: File
    private lateinit var workspacesDir: File
    private lateinit var directories: FakeWorkspaceDirectory
    private lateinit var workspaces: RuntimeWorkspaces
    private var listChanges = 0
    private var removedSessions = 0

    @Before
    fun setUp() {
        root = Files.createTempDirectory("superpi-runtime-workspaces").toFile()
        homeDir = File(root, "home").apply { mkdirs() }
        workspacesDir = File(root, "workspaces").apply { mkdirs() }
        directories = FakeWorkspaceDirectory()
        listChanges = 0
        removedSessions = 0
        workspaces = RuntimeWorkspaces(
            mutex = Mutex(),
            homeDir = homeDir,
            workspacesDir = workspacesDir,
            directories = directories,
            catalog = EmptySessionDirectory(),
            emitListChanged = { listChanges += 1 },
            emitRemoved = { removedSessions += 1 },
            stopTurn = { emptyList() },
            detachForget = {},
            sessionCleaner = null,
        )
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun listStartsWithHome() = runBlocking {
        val listed = workspaces.list()
        assertEquals(1, listed.size)
        assertTrue(listed.single().isHome)
        assertEquals(homeDir.absolutePath, listed.single().cwd)
    }

    @Test
    fun defaultCreateUsesPrivateWorkspaceDirectory() = runBlocking {
        val workspace = workspaces.create("Team")

        assertEquals("Team", workspace.name)
        assertEquals(File(workspacesDir, workspace.id).absolutePath, workspace.cwd)
        assertTrue(File(workspace.cwd).isDirectory)
        assertEquals(listOf(workspace), workspaces.list().filterNot { it.isHome })
        assertEquals(1, listChanges)
    }

    @Test
    fun createUsesCustomDirectoryAndKeepsItsFiles() = runBlocking {
        val custom = File(root, "shared/docs").apply { mkdirs() }
        val marker = File(custom, "keep.txt").apply { writeText("keep") }

        val workspace = workspaces.create("Docs", custom.absolutePath)

        assertEquals(custom.absolutePath, workspace.cwd)
        assertTrue(marker.exists())
        assertEquals(custom.absolutePath, workspaces.find(workspace.id).cwd)
    }

    @Test
    fun createMakesMissingCustomDirectory() = runBlocking {
        val custom = File(root, "shared/missing")

        val workspace = workspaces.create("Docs", custom.absolutePath)

        assertEquals(custom.absolutePath, workspace.cwd)
        assertTrue(custom.isDirectory)
    }

    @Test
    fun createRejectsRelativeDirectory() = runBlocking {
        val error = runCatching { workspaces.create("Docs", "relative/dir") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(workspaces.list().none { it.name == "Docs" })
    }

    @Test
    fun createRejectsHomeDirectory() = runBlocking {
        val error = runCatching { workspaces.create("Docs", homeDir.absolutePath) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun createRejectsBlankName() = runBlocking {
        val error = runCatching { workspaces.create("  ") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun createRejectsDirectoryAlreadyUsedByWorkspace() = runBlocking {
        val custom = File(root, "shared/docs").apply { mkdirs() }
        workspaces.create("Docs", custom.absolutePath)

        val error = runCatching { workspaces.create("Docs 2", custom.absolutePath) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(1, workspaces.list().count { !it.isHome })
    }

    @Test
    fun deleteRemovesPrivateWorkspaceDirectory() = runBlocking {
        val workspace = workspaces.create("Team")
        val directory = File(workspace.cwd)
        assertTrue(directory.isDirectory)

        workspaces.delete(workspace.id)

        assertFalse(directory.exists())
        assertTrue(workspaces.list().none { it.id == workspace.id })
    }

    @Test
    fun deleteKeepsCustomWorkspaceDirectory() = runBlocking {
        val custom = File(root, "shared/docs").apply { mkdirs() }
        val marker = File(custom, "keep.txt").apply { writeText("keep") }
        val workspace = workspaces.create("Docs", custom.absolutePath)

        workspaces.delete(workspace.id)

        assertTrue(custom.isDirectory)
        assertTrue(marker.exists())
        assertTrue(workspaces.list().none { it.id == workspace.id })
    }


    @Test
    fun createStoresAvatarAndIntro() = runBlocking {
        val workspace = workspaces.create("Writer", avatar = "/data/avatars/a.webp", intro = "帮我写长文")

        assertEquals("/data/avatars/a.webp", workspace.avatar)
        assertEquals("帮我写长文", workspace.intro)
        assertEquals("/data/avatars/a.webp", workspaces.find(workspace.id).avatar)
        assertEquals("帮我写长文", workspaces.find(workspace.id).intro)
    }

    @Test
    fun createNormalizesAvatarAndIntro() = runBlocking {
        val workspace = workspaces.create(
            "Trimmed",
            avatar = "  /data/avatars/b.webp  ",
            intro = "  " + "x".repeat(250) + "  ",
        )
        assertEquals("/data/avatars/b.webp", workspace.avatar)
        assertEquals("x".repeat(RuntimeWorkspaces.MAX_INTRO_LENGTH), workspace.intro)
    }

    @Test
    fun createKeepsIdentityEmptyWhenBlank() = runBlocking {
        val workspace = workspaces.create("Plain", avatar = "   ", intro = "")

        assertEquals(null, workspace.avatar)
        assertEquals(null, workspace.intro)
    }

    @Test
    fun renameKeepsAvatarAndIntro() = runBlocking {
        val workspace = workspaces.create("Writer", avatar = "/data/avatars/c.webp", intro = "短句")

        workspaces.rename(workspace.id, "New Name")

        val renamed = workspaces.find(workspace.id)
        assertEquals("New Name", renamed.name)
        assertEquals("/data/avatars/c.webp", renamed.avatar)
        assertEquals("短句", renamed.intro)
    }

    @Test
    fun updateProfileOnlyChangesIdentity() = runBlocking {
        val workspace = workspaces.create("Writer", avatar = "/data/avatars/c.webp", intro = "短句")

        workspaces.updateProfile(workspace.id, null, "新简介")

        val updated = workspaces.find(workspace.id)
        assertEquals("Writer", updated.name)
        assertEquals(null, updated.avatar)
        assertEquals("新简介", updated.intro)
    }

    @Test
    fun homeWorkspaceUsesSuperPINameAndNoAvatar() = runBlocking {
        val home = workspaces.list().single { it.isHome }

        assertEquals("PI", home.name)
        assertEquals(null, home.avatar)
        assertEquals(null, home.intro)
    }

    @Test
    fun updateDefaultModelPersistsAndClearsForHomeAndCustomWorkspaces() = runBlocking {
        val global = ModelConfig("deepseek", "deepseek-chat", ThinkingLevel.OFF)
        val homeDefault = ModelConfig("anthropic", "claude-sonnet", ThinkingLevel.MEDIUM)
        val botDefault = ModelConfig("openai", "gpt-5", ThinkingLevel.HIGH)

        workspaces.updateDefaultModel(AgentRuntimeHost.HOME_ID, homeDefault)
        assertEquals(homeDefault, workspaces.list().single { it.isHome }.defaultModel)
        assertEquals(homeDefault, workspaces.defaultModelForCwd(homeDir.absolutePath, global))

        workspaces.updateDefaultModel(AgentRuntimeHost.HOME_ID, null)
        assertEquals(null, workspaces.list().single { it.isHome }.defaultModel)
        assertEquals(global, workspaces.defaultModelForCwd(homeDir.absolutePath, global))

        val bot = workspaces.create("Coder")
        workspaces.updateDefaultModel(bot.id, botDefault)
        assertEquals(botDefault, workspaces.find(bot.id).defaultModel)
        assertEquals(botDefault, workspaces.defaultModelForCwd(bot.cwd, global))

        workspaces.updateDefaultModel(bot.id, null)
        assertEquals(null, workspaces.find(bot.id).defaultModel)
        assertEquals(global, workspaces.defaultModelForCwd(bot.cwd, global))
    }

    @Test
    fun homeWorkspaceSupportsCustomIdentityAndCannotDelete() = runBlocking {
        workspaces.rename(AgentRuntimeHost.HOME_ID, "Atlas")
        workspaces.updateProfile(AgentRuntimeHost.HOME_ID, "/tmp/atlas.jpg", "Main assistant")
        val updated = workspaces.find(AgentRuntimeHost.HOME_ID)

        assertTrue(updated.isHome)
        assertEquals("Atlas", updated.name)
        assertEquals("/tmp/atlas.jpg", updated.avatar)
        assertEquals("Main assistant", updated.intro)

        workspaces.delete(AgentRuntimeHost.HOME_ID)
        assertEquals("Atlas", workspaces.find(AgentRuntimeHost.HOME_ID).name)

        workspaces.rename(AgentRuntimeHost.HOME_ID, "   ")
        assertEquals("Atlas", workspaces.find(AgentRuntimeHost.HOME_ID).name)
        directories.homeProfile = HomeWorkspaceProfile(name = "   ")
        assertEquals(AgentRuntimeHost.HOME_NAME, workspaces.find(AgentRuntimeHost.HOME_ID).name)
    }

    private class FakeWorkspaceDirectory : WorkspaceDirectory {
        val entries = mutableListOf<RuntimeWorkspace>()
        var homeDefaultModel: ModelConfig? = null
        var homeProfile: HomeWorkspaceProfile = HomeWorkspaceProfile()

        override suspend fun read(): List<RuntimeWorkspace> = entries.toList()

        override suspend fun add(workspace: RuntimeWorkspace) {
            entries.removeAll { it.id == workspace.id }
            entries += workspace
        }

        override suspend fun remove(id: String) {
            entries.removeAll { it.id == id }
        }

        override suspend fun readHomeDefaultModel(): ModelConfig? = homeDefaultModel

        override suspend fun updateHomeDefaultModel(model: ModelConfig?) {
            homeDefaultModel = model
        }

        override suspend fun readHomeProfile(): HomeWorkspaceProfile = homeProfile

        override suspend fun updateHomeProfile(profile: HomeWorkspaceProfile) {
            homeProfile = profile
        }
    }

    private class EmptySessionDirectory : SessionDirectory {
        override suspend fun list(cwd: String): List<ConversationSummary> = emptyList()

        override suspend fun listAll(cwds: List<String>): List<ConversationSummary> = emptyList()

        override suspend fun find(cwds: List<String>, sessionId: String): ConversationSummary? = null

        override suspend fun delete(filePath: String) = Unit

        override suspend fun rename(id: String, title: String) = Unit

        override suspend fun setPinned(id: String, pinned: Boolean) = Unit

        override suspend fun setArchived(id: String, archived: Boolean) = Unit

        override suspend fun removeMeta(id: String) = Unit
    }
}
