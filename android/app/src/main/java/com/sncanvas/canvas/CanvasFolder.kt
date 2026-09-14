package com.sncanvas.canvas

import java.io.File

/**
 * The canvas folder's files (FR12/FR13): which canvases it holds, newest
 * first, and taking in the files of another folder, such as the plugin's own
 * directory, where builds before shared storage kept canvases. Plain java.io,
 * so tested against a temporary folder.
 */
object CanvasFolder {
    /** The canvas files (`*.json`) directly in [dir], by name, most recently saved first; none when there is no such folder. */
    fun canvasFiles(dir: File): List<String> =
        dir
            .listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { it.name }

    /**
     * Moves every file under [from] to the same place under [to], keeping any
     * file [to] already has (the one there is newer, or is the user's), and
     * returns how many moved. Folders emptied by the move go too.
     */
    fun adopt(
        from: File,
        to: File,
    ): Int {
        var moved = 0
        for (file in from.listFiles().orEmpty()) {
            val target = File(to, file.name)
            moved +=
                when {
                    file.isDirectory -> adopt(file, target)
                    target.exists() -> 0
                    else -> move(file, target)
                }
        }
        // Deletes the folder only once it is empty: a file kept because [to] had its own copy keeps it.
        from.delete()
        return moved
    }

    private fun move(
        file: File,
        target: File,
    ): Int {
        // copyTo creates any folders missing on the way to the target.
        file.copyTo(target)
        file.delete()
        return 1
    }
}
