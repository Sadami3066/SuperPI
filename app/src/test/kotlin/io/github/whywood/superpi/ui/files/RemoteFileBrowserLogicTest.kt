package io.github.whywood.superpi.ui.files

import io.github.whywood.superpi.remote.RemoteFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFileBrowserLogicTest {
    @Test
    fun sortsDirectoriesFirstThenCaseInsensitiveNames() {
        val items = listOf(
            RemoteFileItem("zeta.kt", "zeta.kt", isDirectory = false, size = 10L, modifiedAt = 1L),
            RemoteFileItem("src", "src", isDirectory = true, size = 0L, modifiedAt = 1L),
            RemoteFileItem("Alpha.txt", "Alpha.txt", isDirectory = false, size = 20L, modifiedAt = 1L),
            RemoteFileItem("App", "App", isDirectory = true, size = 0L, modifiedAt = 1L),
        )
        assertEquals(
            listOf("App", "src", "Alpha.txt", "zeta.kt"),
            RemoteFileBrowserLogic.sortEntries(items).map { it.name },
        )
    }

    @Test
    fun buildsChildParentAndBreadcrumbs() {
        assertEquals("src/ui", RemoteFileBrowserLogic.childPath("src/", "/ui/"))
        assertEquals("src", RemoteFileBrowserLogic.parentPath("src/ui/"))
        assertEquals("", RemoteFileBrowserLogic.parentPath("src"))
        assertEquals(
            listOf("src" to "src", "ui" to "src/ui", "files" to "src/ui/files"),
            RemoteFileBrowserLogic.breadcrumbs("/src/ui/files/"),
        )
    }

    @Test
    fun validatesEntryNamesAgainstEmptyAndTraversal() {
        assertEquals(RemoteFileNameValidation.EMPTY, RemoteFileBrowserLogic.validateEntryName("   "))
        assertEquals(RemoteFileNameValidation.INVALID, RemoteFileBrowserLogic.validateEntryName(".."))
        assertEquals(RemoteFileNameValidation.INVALID, RemoteFileBrowserLogic.validateEntryName("a/b"))
        assertEquals(RemoteFileNameValidation.INVALID, RemoteFileBrowserLogic.validateEntryName("a\\b"))
        assertEquals(RemoteFileNameValidation.VALID, RemoteFileBrowserLogic.validateEntryName("readme.md"))
    }

    @Test
    fun classifiesOfflineAndUpgradeErrorCodes() {
        assertTrue(RemoteFileBrowserLogic.isOfflineCode("device_offline"))
        assertTrue(RemoteFileBrowserLogic.isOfflineCode("not_connected"))
        assertFalse(RemoteFileBrowserLogic.isOfflineCode("timeout"))
        assertTrue(RemoteFileBrowserLogic.isUpgradeCode("capability_unavailable"))
        assertTrue(RemoteFileBrowserLogic.isUpgradeCode("unsupported_method"))
        assertFalse(RemoteFileBrowserLogic.isUpgradeCode("permission_denied"))
    }
}
