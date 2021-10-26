package com.soywiz.korvi.internal

import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.video.VideoSize
import com.soywiz.klock.Frequency
import com.soywiz.klock.hr.HRTimeSpan
import com.soywiz.klock.hr.hr
import com.soywiz.klock.milliseconds
import com.soywiz.klock.nanoseconds
import com.soywiz.klock.timesPerSecond
import com.soywiz.korio.android.androidContext
import com.soywiz.korio.file.VfsFile
import com.soywiz.korvi.KorviVideo
import kotlinx.coroutines.*


class AndroidKorviVideoAndroidExoPlayer private constructor(val file: VfsFile) : KorviVideo() {

    companion object {
        suspend operator fun invoke(file: VfsFile) =
            AndroidKorviVideoAndroidExoPlayer(file).also { it.init() }
    }

    private var player: SimpleExoPlayer? = null
    lateinit var nativeImage: SurfaceNativeImage

    private var lastTimeSpan: HRTimeSpan = HRTimeSpan.ZERO
    private suspend fun init() {

        val androidContext = androidContext()

        CoroutineScope(Dispatchers.Main).launch {
            player = SimpleExoPlayer.Builder(androidContext)
                .build()
            player?.apply {
                //Todo add multiple data sources when received as list
                addMediaItem(MediaItem.fromUri(generateExoPlayerSource(file)))
            }

        }

    }

    @Volatile
    private var frameAvailable = 0

    override fun prepare() {
        val info = SurfaceNativeImage.createSurfacePair()
        // println("SET SURFACE")
        info.texture.setOnFrameAvailableListener {
            // println("frame available: $frameAvailable")
            frameAvailable++
        }

        CoroutineScope(Dispatchers.Main).launch {
            //val offsurface = OffscreenSurface(1024, 1024)
            //offsurface.makeCurrentTemporarily {
            player?.let { player ->
                player.setVideoSurface(info.surface)
                val param = PlaybackParameters(1f)
                player.playbackParameters = param

                player.prepare()

                player.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        super.onVideoSizeChanged(videoSize)
                        println("CREATE SURFACE FOR VIDEO: ${videoSize.width},${videoSize.height}")
                        nativeImage = SurfaceNativeImage(videoSize.width, videoSize.height, info)
                    }
                })
            }
        }
    }

    private var lastUpdatedFrame = -1
    override fun render() {
        if (lastUpdatedFrame == frameAvailable) return
        try {
            // println("AndroidKorviVideoAndroidExoPlayer.render! $frameAvailable")
            val surfaceTexture = nativeImage.surfaceTexture
            lastUpdatedFrame = frameAvailable
            surfaceTexture.updateTexImage()
            lastTimeSpan = surfaceTexture.timestamp.toDouble().nanoseconds.hr
            onVideoFrame(Frame(nativeImage, lastTimeSpan, frameRate.timeSpan.hr))
        } catch (e: Exception) {
            System.err.println(e.message)
        }
    }

    override val running: Boolean get() = player?.isPlaying ?: false
    override val elapsedTimeHr: HRTimeSpan get() = lastTimeSpan

    // @TODO: We should try to get this
    val frameRate: Frequency = 25.timesPerSecond

    override suspend fun getTotalFrames(): Long? =
        getDuration()?.let { duration -> (duration / frameRate.timeSpan.hr).toLong() }

    override suspend fun getDuration(): HRTimeSpan? =
        player?.duration.takeIf { it != null && it >= 0 }?.milliseconds?.hr

    override suspend fun play() {
        //println("START")
        CoroutineScope(Dispatchers.Main).launch {
            player?.play()
            println("Duration:" + getDuration()?.secondsInt)

        }
    }

    override suspend fun pause() {
        super.pause()
        CoroutineScope(Dispatchers.Main).launch {
            player?.pause()
            println("Duration:" + getDuration()?.secondsInt)

        }

    }

    override suspend fun seek(frame: Long) {
        println(frameRate.timeSpan.hr)
        seek(frameRate.timeSpan.hr * frame.toDouble())
    }

    override suspend fun seek(time: HRTimeSpan) {
        lastTimeSpan = time
        CoroutineScope(Dispatchers.Main).launch {
            //Todo seek through multiple media files
//            player?.seekTo(windowIndex, seekPos.toLong())
            player?.seekTo(time.millisecondsInt.toLong())
        }
    }

    override suspend fun stop() {
        close()
    }

    override suspend fun close() {
        nativeImage.dispose()
        player?.stop()
        player?.release()
        player = null
    }
}
