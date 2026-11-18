// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView

/** Descriptor-relative removal: never follows symlinks, including a substituted ancestor.
 * No manifest is required, so damaged/partial installations remain removable.
 * Unsupported filesystems fail closed rather than falling back to path-based recursion.
 */
internal object LocalSpeechSafeDelete {
    fun delete(modelsRoot: File, id: String) {
        require(id.matches(Regex("[a-z0-9_]{1,80}"))) { "模型 ID 不安全" }
        require(modelsRoot.name == "local_speech_models") { "模型根目录不安全" }
        val parent = requireNotNull(modelsRoot.absoluteFile.parentFile)
        require(parent.canonicalFile == parent) { "模型父目录不安全" }
        // filesDir is the trusted, private, canonical anchor (ancestors such as /data are
        // intentionally not listable by an Android app). All model paths below it use *at APIs.
        Files.newDirectoryStream(parent.toPath()).use { opened ->
            val secureParent = opened.secure()
            val rootName = modelsRoot.toPath().fileName
            val root = try { secureParent.newDirectoryStream(rootName, NOFOLLOW_LINKS) }
                catch (_: NoSuchFileException) { return }
            root.use { remove(it, modelsRoot.toPath().fileSystem.getPath(id)) }
        }
    }

    private fun DirectoryStream<Path>.secure(): SecureDirectoryStream<Path> =
        this as? SecureDirectoryStream<Path>
            ?: throw IOException("此文件系统不支持安全删除，未使用不安全的递归删除")

    private fun remove(parent: SecureDirectoryStream<Path>, name: Path) {
        val attributes = try {
            parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
        } catch (_: NoSuchFileException) { return }
        if (attributes.isDirectory) {
            // NOFOLLOW also covers replacement by a symlink between stat and open.
            parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
                for (entry in child) remove(child, entry.fileName)
            }
            parent.deleteDirectory(name)
        } else {
            // Includes ordinary files, dangling links and links to directories: unlink, never traverse.
            parent.deleteFile(name)
        }
        try {
            parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
            throw IOException("模型目录删除后仍存在；可能被并发修改，请刷新状态")
        } catch (_: NoSuchFileException) { /* confirmed absent, not merely File.exists()==false */ }
    }
}
