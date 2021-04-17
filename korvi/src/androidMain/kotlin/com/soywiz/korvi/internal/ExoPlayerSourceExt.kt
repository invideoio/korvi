package com.soywiz.korvi.internal

import android.net.Uri
import com.soywiz.korio.file.VfsFile
import com.soywiz.korio.file.baseName
import com.soywiz.korio.file.std.AndroidResourcesVfs

suspend fun generateExoPlayerSource(source: VfsFile): Uri {
    val finalVfsFile = source.getUnderlyingUnscapedFile()
    val vfs = finalVfsFile.vfs
    return when (vfs) {
        is AndroidResourcesVfs -> {

            Uri.parse("asset:///" + source.baseName)
        }
        else -> {
            Uri.parse(source.absolutePath)
        }
    }
}
