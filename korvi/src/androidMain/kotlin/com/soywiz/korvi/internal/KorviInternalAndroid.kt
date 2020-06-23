package com.soywiz.korvi.internal

import com.soywiz.korio.android.androidContext
import com.soywiz.korio.file.VfsFile
import com.soywiz.korvi.KorviVideo
import kotlin.coroutines.coroutineContext

internal actual val korviInternal: KorviInternal = AndroidKorviInternal()

internal class AndroidKorviInternal : KorviInternal() {
    override suspend fun createHighLevel(file: VfsFile): KorviVideo {
        //val final = file.getUnderlyingUnscapedFile()
        //val vfs = final.vfs
        return AndroidKorviVideoSoft(file, androidContext(), coroutineContext)
        //return AndroidKorviVideoAndroidMediaPlayer(file, coroutineContext)
    }
}
