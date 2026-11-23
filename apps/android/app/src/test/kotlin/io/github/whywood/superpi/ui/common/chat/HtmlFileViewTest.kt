package io.github.whywood.superpi.ui.common.chat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtmlFileViewTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun allowsFilesInsideSameFolderAndSubfolders() {
        val dir = temp.newFolder("report")
        val index = File(dir, "index.html").apply { writeText("<html/>") }
        val sibling = File(dir, "page.html").apply { writeText("<html/>") }
        val nestedDir = File(dir, "sub").apply { mkdirs() }
        val nested = File(nestedDir, "chart.html").apply { writeText("<html/>") }

        assertTrue(isAllowedHtmlFileNavigation(index.absolutePath, sibling.absolutePath))
        assertTrue(isAllowedHtmlFileNavigation(index.absolutePath, nested.absolutePath))
    }

    @Test
    fun blocksParentTraversalAndSiblingPrefixBypass() {
        val dir = temp.newFolder("report")
        val index = File(dir, "index.html").apply { writeText("<html/>") }
        val outside = File(temp.root, "secret.html").apply { writeText("secret") }
        val siblingPrefixDir = temp.newFolder("report2")
        val siblingPrefixFile = File(siblingPrefixDir, "leak.html").apply { writeText("leak") }

        assertFalse(isAllowedHtmlFileNavigation(index.absolutePath, "${dir.absolutePath}/../secret.html"))
        assertFalse(isAllowedHtmlFileNavigation(index.absolutePath, outside.absolutePath))
        assertFalse(isAllowedHtmlFileNavigation(index.absolutePath, siblingPrefixFile.absolutePath))
        assertFalse(isAllowedHtmlFileNavigation(index.absolutePath, null))
        assertFalse(isAllowedHtmlFileNavigation(index.absolutePath, "   "))
    }

    @Test
    fun blocksSymlinkEscapingBaseFolder() {
        val dir = temp.newFolder("report")
        val index = File(dir, "index.html").apply { writeText("<html/>") }
        val outside = File(temp.root, "secret.txt").apply { writeText("secret") }
        val escapeLink = File(dir, "escape.html").toPath()
        Files.createSymbolicLink(escapeLink, outside.toPath())

        assertFalse(isAllowedHtmlFileNavigation(index.absolutePath, escapeLink.toString()))
    }
}
