package io.invideo.features.avcore

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer


/**
 * Created by Amud on 29/07/16.
 */
@RequiresApi(21)
class AVMediaExtractor {
    private val MAX_FRAME_INTERVAL = 50
    private val mAudioEnabled: Boolean
    private var mExtractionMode = 0
    var mRealTimeExtract = true

    /**
     * if true, the audio will be played using audiotrack
     * (this is useful especially when using OpenGL to start the video)
     */
    private var mPlayAudio = false

    @set:Synchronized
    var isLooping = false
    private var mShouldLoop = false
    private val mReadyForNextFrame = Object()
    private var mVideoEncoderConfig: VideoConfig? = null
    private var mAudioEncoderConfig: AudioConfig? = null
    private var mVideoFrameRendered = false
    private var mAudioFrameRendered = false
    private var mAudioPlaying = false

    @Volatile
    private var mIsReadyForNextFrame = false

    @Volatile
    private var seekVideo = false

    @Volatile
    private var seekAudio = false

    @Volatile
    private var mSeekTime: Long = -1
    private var mSeekingAudio = false
    private var mSeekingVideo = false

    @Volatile
    private var mAudioVolume = 1.0f
/*    private var mOnPreparedListener: OnPreparedListener? = null
    private var mOnCompletionListener: OnCompletionListener? = null
    private var mOnSeekListener: MediaPlayerUtils.OnSeekListener? = null
    private var mOnSeekCompleteListener: OnSeekCompleteListener? = null
    private var mOnVideoSizeChangedListener: OnVideoSizeChangedListener? = null
    private var mOnBufferingUpdateListener: OnBufferingUpdateListener? = null
    private var mOnProgressListener: MediaPlayerUtils.OnProgressListener? = null

    @Volatile
    private var mOnErrorListener: MediaPlayerUtils.OnErrorListener? = null
    private var mOnInfoListener: MediaPlayerUtils.OnInfoListener? = null*/
    var id: String? = null
    private var mVideoPresentationTime: Long = 0
    private var mAudioPresentationTime: Long = 0

    @Volatile
    var currentPosition: Long = 0
        private set
    private var mPlayTime: Long = 0
    private var mPrevSeekTime: Long = -1
    private var gotImageFrame = false

    @Volatile
    var pauseTime = System.currentTimeMillis()

    @Volatile
    private var mAudioCodecReady = false

    @Volatile
    private var mVideoCodecReady = false

    @Volatile
    private var mCanStartAudio = false

    @Volatile
    private var mClosestSeekReached = false

    @Volatile
    private var mNextSeek: Long = -1
    private var mPlaybackSpeed = 1.0f

    @Volatile
    private var mFeedSilenceFrames = false
    private var mCurrBeginTime: Long = -1
    private var beepOffset = 0
    private var sampleIndex = 0

    @Volatile
    private var pipelineReady = false
    private var mExtractSeekFrames = false
    private var mSeekFrameCallback: SeekFrameCallback? = null
    private var mCaptureBitmap: Bitmap? = null
    private var mLatestPresentationTimeUs: Long = -1
    private var mPrevKeyFrameTs: Long = 0
    val duration: Int
        get() = (mVideoLength / 1000).toInt()

//    fun setSeekMode(seekMode: MediaPlayer.SeekMode?) {}
    var playbackSpeed: Float
        get() = mPlaybackSpeed
        set(playbackSpeed) {
            if (playbackSpeed > 4.0f || playbackSpeed < 0.25f) {
                Log.e(TAG, "Invalid playback speed: $playbackSpeed")
                return
            }
            mPlaybackSpeed = playbackSpeed
            mTimeBase.setSpeed(playbackSpeed)
            setAudioPlaybackSpeed(playbackSpeed)
        }
    val isPrepared: Boolean
        get() = mState >= STATE_PREPARED
    val isPlaying: Boolean
        get() = mState == STATE_PLAYING

    fun reset() {}
    val currentPositionMs: Int
        get() = (mBeginTime + currentPosition / 1000).toInt()

    fun prepareAsync() {
        prepare()
    }

    fun prepare() {
        if (VERBOSE) Log.v(TAG, "prepare:")
        synchronized(mSync) { mPlayerHandler!!.sendEmptyMessage(MSG_PREPARE) }
    }

    /**
     * Well actually it's just a request to start...
     * Extractor will start in another thread based on certain conditions (related to system state).
     */
    fun start() {
        if (VERBOSE) Log.v(TAG, "start:")
        synchronized(mSync) {
            if (mState == STATE_PLAYING) {
                Log.w(TAG, "Already playing. Ignoring!")
                return
            }
            mPlayerHandler!!.sendEmptyMessage(MSG_START)
        }
    }

    var mStopRunnable = Runnable { stop(true) }

    /**
     * request to seek to specifc timed frame<br></br>
     * if the frame is not a key frame, frame image will be broken
     *
     * @param newTime seek to new time[usec]
     */
    @JvmOverloads
    fun seekTo(newTime: Long, shouldPause: Boolean = true) {
        if (VERBOSE) Log.v(
            TAG,
            "request seek: $newTime"
        )
        synchronized(mSync) {
            mRequestTime = newTime
            mPlayerHandler!!.sendMessage(
                mPlayerHandler!!.obtainMessage(
                    MSG_SEEK,
                    (newTime shr 32).toInt(), newTime.toInt(), shouldPause
                )
            )
        }
    }

    /**
     * request stop playing
     */
    @JvmOverloads
    fun stop(force: Boolean = false) {
        if (VERBOSE) Log.v(TAG, "stop:")
        synchronized(mSync) {
            if (mRequestState == STATE_STOP) {
                return
            }
            if (force) {
                mRequestState = STATE_STOP
            }
            if (mState != STATE_STOP) {
                mPlayerHandler!!.sendMessage(
                    mPlayerHandler!!.obtainMessage(
                        MSG_STOP,
                        if (force) 1 else 0,
                        0
                    )
                )
            }
        }
    }

    /**
     * request pause playing<br></br>
     * this function is un-implemented yet
     */
    fun pause() {
        if (VERBOSE) Log.v(TAG, "pause:")
        synchronized(mSync) {
            mRequestState = STATE_PAUSED
            mPlayerHandler!!.sendEmptyMessage(MSG_PAUSE)
        }
    }

    /**
     * request resume from pausing<br></br>
     * this function is un-implemented yet
     */
    fun resume() {
        if (VERBOSE) Log.v(TAG, "resume:")
        synchronized(mSync) {
            mCurrBeginTime = -1
            mRequestState = STATE_PLAYING
            mPlayerHandler!!.sendEmptyMessage(MSG_RESUME)
        }
    }

    /**
     * release releated resources
     */
    fun release() {
        if (VERBOSE) Log.v(TAG, "release:")
        stop(true)
        synchronized(mSync) { mPlayerHandler!!.sendEmptyMessage(MSG_QUIT) }
    }

    protected var mMetadata: MediaMetadataRetriever? = null
    private val mSync = Object()
    private val mVideoPause = Object()
    private val mAudioPause = Object()

    @Volatile
    private var mIsRunning = false
    private var mState = 0
    private var mSourcePath: String? = null
    private var mDuration: Long = 0
    private var mRequestState = -1
    private var mRequestTime: Long = 0

    // for video playback
    private val mVideoSync = Object()
    private var mOutputSurface: Surface? = null
    protected lateinit var mVideoMediaExtractor: MediaExtractor
    private lateinit var mVideoMediaCodec: MediaCodec
    private var mVideoBufferInfo: MediaCodec.BufferInfo? = null

    @Volatile
    /**
     * Could be fake timestamp coming from the Renderer just in case we're not extracting
     * the data to be rendered to the video from Video source
     */
    private var mVideoTrackIndex = 0

    @Volatile
    private var mVideoInputDone = false

    @Volatile
    private var mVideoOutputDone = false
    private var mVideoWidth = 0
    private var mVideoHeight = 0
    private var mVideoBitRate = 0
    private var mVideoFrameRate = -1
    private var mVideoFrameInterval = 0
    private var mProfile = 0
    private var mLevel = 0
    private var mBitrate = 0
    private var mFrameRate = 0f
    private var mRotation = 0

    // for audio playback
    private val mAudioSync = Any()
    private lateinit var mAudioMediaExtractor: MediaExtractor
    private var mAudioMediaCodec: MediaCodec? = null
    private var mAudioBufferInfo: MediaCodec.BufferInfo? = null
    private var mAudioStartTime: Long = 0

    @Volatile
    private var previousAudioPresentationTimeUs: Long = -1

    @Volatile
    private var previousVideoPresentationTimeUs: Long = -1

    @Volatile
    private var mAudioTrackIndex = 0

    @Volatile
    private var mAudioInputDone = false

    @Volatile
    private var mAudioOutputDone = false
    private var mAudioChannels = 0
    private var mAudioSampleRate = 0
    private var mAudioBitRate = 0
    private var mAudioPCMEncoding = 0
    private var mAudioInputBufSize = 0
    private var mAudioTrack: AudioTrack? = null
    var videoThread: Thread? = null
    var audioThread: Thread? = null

    @Volatile
    private var isAudioPausedRequested = false

    @Volatile
    private var isVideoPausedRequested = false

    //--------------------------------------------------------------------------------
    private var mPlayerHandler: Handler? = null
    private val mTimeBase: TimeBase = TimeBase()

    @Volatile
    private var mWaitTime: Long = 0
    private var playWhileSeek = false

    /**
     * playback control task
     */
    private val mMoviePlayerTask = Runnable {
        Looper.prepare()
        mPlayerHandler = Handler(
            Looper.myLooper()!!
        ) { msg ->
            val message = msg.what
            when (message) {
                MSG_PREPARE -> handlePrepare(mSourcePath)
                MSG_START -> handleStart()
                MSG_RESUME -> if (mState == STATE_PREPARED || needsStart()) {
                    handleStart()
                    //                                handleSeek((mSeekTime < 0) ? mPresentationTime : mSeekTime);
                    handleResume()
                } else if (mState == STATE_PAUSED) {
                    handleResume()
                }
                MSG_SEEK -> {
                    if (needsStart()) {
                        //handleStop(true);
                        handleStart()
                    }
                    val shouldPause = msg.obj as Boolean
                    val timeStampNs =
                        msg.arg1.toLong() shl 32 or (msg.arg2.toLong() and 0xffffffffL)
                    if (shouldPause) {
                        handlePause()
                    }
                    handleSeek(timeStampNs)
                }
                MSG_PAUSE -> handlePause()
                MSG_STOP -> {
                    val force = msg.arg1 > 0
                    handleStop(force)
                }
                MSG_QUIT -> {
                    try {
                        handleRelease()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    Looper.myLooper()!!.quit()
                }
                else -> throw IllegalArgumentException("Unknown msg")
            }
            true
        }
        synchronized(mSync) {
            mIsRunning = true
            mState = STATE_STOP
            mRequestTime = -1
            mSync.notifyAll()
        }
        Looper.loop()
        Log.d(TAG, "AVMediaExtractor exiting!")
    }

    private fun needsStart(): Boolean {
        if (VERBOSE) Log.d(
            TAG,
            "audioThread: " + audioThread + " isAlive: " + (audioThread != null && audioThread!!.isAlive) + "videoThread: " + videoThread + " isAlive: " + (videoThread != null && videoThread!!.isAlive)
        )
        return extractAudio() && (audioThread == null || !audioThread!!.isAlive) || extractVideo() && (videoThread == null || !videoThread!!.isAlive)
    }
    //--------------------------------------------------------------------------------
    /*    */
    /**
     * video playback task
     */
    /*
    private final Runnable mVideoTask = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.v(TAG, "VideoTask:start");
            for (; mIsRunning && !mVideoInputDone; ) {
                try {
                    if (!mVideoInputDone) {
                        handleInputMuxing(mVideoMediaExtractor, mVideoTrackIndex);
                    }
                } catch (final Exception e) {
                    Log.e(TAG, "VideoTask:", e);
                    break;
                }


            } // end of for
            if (DEBUG) Log.v(TAG, "VideoTask:finished");
            synchronized (mSync) {
                mVideoInputDone = mVideoOutputDone = true;
            }
        }
    };*/
    private val mVideoTask: Runnable = object : Runnable {
        override fun run() {
            if (VERBOSE) Log.v(TAG, "VideoTask:start")
            var videoCodecFresh = false
            gotImageFrame = false
            if (mVideoTrackIndex >= 0 && mVideoMediaCodec == null) {
                val codec = internalStartVideo(mVideoMediaExtractor, mVideoTrackIndex)
                if (codec != null) {
                    mVideoMediaCodec = codec
                    mVideoBufferInfo = MediaCodec.BufferInfo()
                    mVideoCodecReady = true
                    videoCodecFresh = true
                } else {
                    Log.e(TAG, "VideoTask error")
                    stop(true)
                }
            } else {
                mVideoCodecReady = true
            }
            if (extractAudio()) {
                while (!mAudioCodecReady) {
                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
            }
            startTimeBase()
            mVideoFrameRendered = false
            if (VERBOSE) Log.d(TAG, "VideoTask: start loop")
            while (mIsRunning && !(mVideoInputDone && mVideoOutputDone)) {
                try {
                    Thread.sleep(2)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
                synchronized(mVideoPause) {
                    if (DEBUG) Log.d(
                        TAG + "pause",
                        "isVideoPausedRequested $isVideoPausedRequested seekVideo: $seekVideo mPlayTime: $mPlayTime mWaitTime: $mWaitTime"
                    )
                    if (!isVideoPausedRequested && !seekVideo && (mPlayTime >= 0 || mWaitTime > 0)) {
                        mPrevSeekTime = -1
                        if (mPlayTime >= 0) {
                            mTimeBase.startAt(mPlayTime)
                            mPlayTime = -1
                        } else {
                            mTimeBase.offset(mWaitTime * 1000L)
                        }
                        mWaitTime = 0
                        pauseTime = System.currentTimeMillis()
                    }
                    if (mSeekTime >= 0 && seekVideo && !mSeekingVideo) {
                        mSeekingVideo = true
                        previousVideoPresentationTimeUs = mSeekTime - mBeginTime
                        if (mVideoTrackIndex >= 0) {
                            mPlayTime = mSeekTime
                            if (VERBOSE) Log.d(
                                TAG,
                                "seeking video extractor to: $mSeekTime"
                            )
                            if (mLatestPresentationTimeUs < 0 || mSeekTime > mLatestPresentationTimeUs && mSeekTime - mPrevKeyFrameTs > 999999) {
                                mVideoMediaExtractor.seekTo(
                                    mSeekTime,
                                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC
                                )
                                try {
                                    if (!videoCodecFresh) {
                                        mVideoMediaCodec.flush()
                                    }
                                } catch (e: IllegalStateException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                    if (!isVideoPausedRequested || seekVideo) {
                        if (DEBUG) Log.d(
                            TAG + "pause",
                            "isVideoPausedRequested $isVideoPausedRequested"
                        )
                        if (seekVideo) {
                            synchronized(mReadyForNextFrame) {
                                // Spurious wake ups shouldn't be an issue here... so no need of an additional bool check
                                try {
                                    if (DEBUG) Log.d(
                                        TAG,
                                        "video: waiting for next frame signal shortest"
                                    )
                                    mReadyForNextFrame.wait(0, 1000)
                                    if (DEBUG) Log.d(
                                        TAG,
                                        "video: got the next frame signal shortest"
                                    )
                                } catch (e: InterruptedException) {
                                    e.printStackTrace()
                                }
                            }
                        } else {
                            if (!mVideoFrameRendered) {
                                synchronized(mReadyForNextFrame) {
                                    // Spurious wake ups shouldn't be an issue here... so no need of an additional bool check
                                    try {
                                        if (DEBUG) Log.d(
                                            TAG,
                                            "video: waiting for next frame signal short"
                                        )
                                        mReadyForNextFrame.wait(2)
                                        if (DEBUG) Log.d(
                                            TAG,
                                            "video: got the next frame signal short"
                                        )
                                    } catch (e: InterruptedException) {
                                        e.printStackTrace()
                                    }
                                }
                            } else if (!mIsReadyForNextFrame) {
                                // Don't wait at all if we're ready for next frame i.e. we've got a signal from the client.
                                if (previousVideoPresentationTimeUs > 0 && previousAudioPresentationTimeUs >= 0) {
                                    synchronized(mReadyForNextFrame) {
                                        // Spurious wake ups shouldn't be an issue here... so no need of an additional bool check
                                        try {
                                            if (DEBUG) {
                                                Log.d(
                                                    TAG,
                                                    "video: waiting for next frame signal"
                                                )
                                            }
                                            mReadyForNextFrame.wait(100)
                                            if (previousVideoPresentationTimeUs - previousAudioPresentationTimeUs > MAX_FRAME_INTERVAL / 2) {
                                                Log.w(
                                                    TAG,
                                                    "Video leading audio by a good margin, trying to keep it slow: prevVideoTs: $previousVideoPresentationTimeUs \tprevAudioTs: $previousAudioPresentationTimeUs"
                                                )
                                                mReadyForNextFrame.wait(50)
// TODO critical                                                continue
                                            }
                                        } catch (e: InterruptedException) {
                                            e.printStackTrace()
                                        }
                                    }
                                    if (DEBUG) Log.d(
                                        TAG,
                                        "video: got the next frame signal"
                                    )
                                } else {
                                    synchronized(mReadyForNextFrame) {
                                        try {
                                            mReadyForNextFrame.wait(32)
                                        } catch (e: InterruptedException) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                            }
                        }
                        try {
                            if (!mVideoInputDone) {
                                handleInputVideo()
                            }
                            if (!mVideoOutputDone) {
// TODO critical                               handleOutputVideo(mCallback)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "VideoTask:", e)
                            break
                        }
                        synchronized(this) {
                            if (mVideoFrameRendered && seekVideo && mClosestSeekReached) {
                                seekVideo = false
                                if (DEBUG) Log.d(
                                    TAG,
                                    "Video Seek finished"
                                )
                                handleSeeked()
                                videoCodecFresh = false
                            }
                        }
                    } else {
                        while (isVideoPausedRequested && !seekVideo && !(mVideoInputDone && mVideoOutputDone)) {
                            try {
                                if (VERBOSE) Log.d(
                                    TAG + "pause",
                                    "mPause " + "wait"
                                )
                                if (VERBOSE) Log.d(
                                    TAG + "pause",
                                    "isVideoPausedRequested $isVideoPausedRequested"
                                )
                                mVideoPause.wait()
                            } catch (e: InterruptedException) {
                                e.printStackTrace()
                            } finally {
                                mWaitTime = System.currentTimeMillis() - pauseTime
                            }
                        }
                    }
                }
                Thread.yield()
            }
            if (VERBOSE) Log.v(TAG, "VideoTask:finishing")
            synchronized(mSync) {
                mShouldLoop = isLooping
                mVideoOutputDone = true
                mVideoInputDone = mVideoOutputDone
                onExtractionFinished()
            }
            if (VERBOSE) Log.v(TAG, "VideoTask:finished")
        }
    }

    private fun onExtractionFinished() {
        if ((mVideoInputDone && mVideoOutputDone || !extractVideo()) && (mAudioInputDone && mAudioOutputDone || !extractAudio()) && mRequestState != STATE_STOP) {
            stop(true)
        }
    }

    private fun handleSeeked() {
/*        synchronized (mVideoPause) {
            synchronized (mAudioPause) {*/
        if ((!extractVideo() || !seekVideo) && (!extractAudio() || !seekAudio)) {
            if (VERBOSE) Log.d(
                TAG,
                "seek finished: $mSeekTime"
            )
            mSeekingAudio = false
            mSeekingVideo = false
/*            if (mOnSeekListener != null) {
                mOnSeekListener.onSeek(this)
            }*/
            if (mNextSeek > -1) {
                if (VERBOSE) Log.d(
                    TAG,
                    "Handling pending seek: $mNextSeek"
                )
                // Can't all handleSeek from here (Will cause Deadlocks)
                seekTo(mNextSeek, false) // Call seek without pausing.
                mNextSeek = -1
            } else {
                if (mSeekTime >= mEndTime - 235000) {
                    if (VERBOSE) Log.d(TAG, "SEEK Reached end: Stopping")
                }
            }
            mSeekTime = -1
        }
        /*            }
        }*/
    }

    /**
     * audio playback task
     */
    private val mAudioTask: Runnable = object : Runnable {
        override fun run() {
            var audioCodecFresh = false
            if (VERBOSE) Log.v(TAG, "AudioTask:start")
            if (mAudioTrackIndex >= 0 && mAudioMediaCodec == null) {
                val codec = internalStartAudio(mAudioMediaExtractor, mAudioTrackIndex)
                if (codec != null) {
                    mAudioMediaCodec = codec
                    mAudioBufferInfo = MediaCodec.BufferInfo()
                    mAudioCodecReady = true
                    audioCodecFresh = true
                } else {
/*                    mOnErrorListener.onError(
                        this@AVMediaExtractor,
                        MSG_START,
                        AndroidUtilities.ERROR_DECODING
                    )*/
                    stop(true)
                }
            } else {
                mAudioCodecReady = true
            }
            if (extractVideo()) {
                while (!mVideoCodecReady || !mCanStartAudio) {
                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
            }
            if (!extractVideo()) {
                startTimeBase()
            }
            mAudioFrameRendered = false
            if (VERBOSE) Log.d(TAG, "AudioTask: start loop")
            if (decodeAudio()) {
                while (mIsRunning && !(mAudioInputDone && mAudioOutputDone)) {
                    try {
                        Thread.sleep(2)
                        synchronized(mAudioPause) {
                            if (mSeekTime >= 0 && seekAudio && !mSeekingAudio) {
                                mSeekingAudio = true
                                previousAudioPresentationTimeUs = mSeekTime - mBeginTime
                                if (mAudioTrackIndex >= 0) {
                                    if (VERBOSE) Log.d(
                                        TAG,
                                        "seeking audio extractor to: $mSeekTime"
                                    )
                                    mAudioMediaExtractor.seekTo(
                                        mSeekTime,
                                        MediaExtractor.SEEK_TO_PREVIOUS_SYNC
                                    )
                                }
                            }
                            if (!isAudioPausedRequested || seekAudio) {
                                if (DEBUG) Log.d(
                                    TAG,
                                    "AUDIO PAUSED: $isAudioPausedRequested \tseekAudio: $seekAudio"
                                )
                                // TODO with the new queues mechanism, these timestamps should not be required anymore.
                                keepAudioSpeedInSync()
                                if (!mAudioInputDone) {
                                    handleInputAudio()
                                }
                                if (!mAudioOutputDone) {
                                    handleOutputAudio()
                                }
                                synchronized(this) {
                                    if (mAudioFrameRendered && seekAudio) {
                                        seekAudio = false
                                        if (DEBUG) Log.d(
                                            TAG,
                                            "Audio Seek finished"
                                        )
                                        handleSeeked()
                                    }
                                }
                            } else {
                                while (isAudioPausedRequested && !seekAudio && !(mAudioInputDone && mAudioOutputDone)) {
                                    mAudioPause.wait()
                                }
                                beepOffset = 0
                                sampleIndex++
                            }
                        }
                        Thread.yield()
                        if (DEBUG) {
                            Log.d(
                                TAG,
                                "prevAudioTs: $previousAudioPresentationTimeUs\tprevVideoTs: $previousVideoPresentationTimeUs"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "AudioTask:", e)
                        break
                    }
                }
            } else {
                while (mIsRunning && !mAudioInputDone) {
                    try {
//                        Log.d(TAG, "prev audio time: " + previousAudioPresentationTimeUs + " prev video time: " + previousVideoPresentationTimeUs);
                        if (previousAudioPresentationTimeUs > previousVideoPresentationTimeUs) {
                            synchronized(mReadyForNextFrame) {

                                // Spurious wake ups shouldn't be an issue here... so no need of an additional bool check
                                try {
                                    Log.d(
                                        TAG,
                                        "Waiting for video frame with higher timestamp to be rendered"
                                    )
                                    mReadyForNextFrame.wait(40)
                                } catch (e: InterruptedException) {
                                    e.printStackTrace()
                                }
                                Log.d(
                                    TAG,
                                    "Got the signal video frame with higher timestamp to be rendered"
                                )
                            }
                        }
                        if (!mAudioInputDone) {
                            handleInputMuxing(mAudioMediaExtractor, mAudioTrackIndex)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "VideoTask:", e)
                        break
                    }
                }
            }
            if (VERBOSE) Log.v(TAG, "AudioTask:finishing")
            synchronized(mSync) {
                mShouldLoop = isLooping
                mAudioOutputDone = true
                mAudioInputDone = mAudioOutputDone
                onExtractionFinished()
            }
            if (VERBOSE) Log.v(TAG, "AudioTask:finished")
        }
    }

    private fun keepAudioSpeedInSync() {
        if (!seekAudio) {
            val timeBase =
                if (mRealTimeExtract) mTimeBase.currentTime else previousVideoPresentationTimeUs
            if (previousAudioPresentationTimeUs > timeBase && previousAudioPresentationTimeUs - timeBase < MAX_FRAME_INTERVAL) {
                synchronized(mReadyForNextFrame) {

                    // Spurious wake ups shouldn't be an issue here... so no need of an additional bool check
                    try {
                        if (DEBUG) {
                            Log.d(
                                TAG,
                                "audio: waiting for next frame signal short"
                            )
                        }
                        mReadyForNextFrame.wait(5)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                    if (DEBUG) {
                        Log.d(
                            TAG,
                            "audio: got the next frame signal"
                        )
                    }
                }
            } else if (previousAudioPresentationTimeUs - timeBase > 0) {
                synchronized(mReadyForNextFrame) {
                    if (DEBUG) {
                        Log.d(
                            TAG,
                            "audio: waiting for next frame signal"
                        )
                    }
                    try {
                        mReadyForNextFrame.wait(24)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
                if (DEBUG) {
                    Log.d(TAG, "audio: got the next frame signal")
                }
                //                                        continue;
            }
        }
    }
    //--------------------------------------------------------------------------------
    //
    //--------------------------------------------------------------------------------
    /**
     * @param source_file source file path to be decoded
     */
    private fun handlePrepare(source_file: String?) {
        if (VERBOSE) Log.v(
            TAG,
            "handlePrepare:$source_file"
        )
        synchronized(mSync) {
            if (mState != STATE_STOP) {
                throw RuntimeException("invalid state:$mState")
            }
        }
        val src = File(source_file)
        // TODO critical sahilbajaj handle these exceptions
        mAudioTrackIndex = -1
        mVideoTrackIndex = mAudioTrackIndex
        mMetadata = MediaMetadataRetriever()
        mMetadata!!.setDataSource(source_file)
        if (extractVideo()) {
            updateMovieInfo()
            // preparation for video playback
            mVideoTrackIndex = internalPrepareVideo(source_file)
        }
        if (mMetadata != null) {
            mMetadata!!.release()
            mMetadata = null
        }
        // preparation for audio playback
        if (extractAudio()) mAudioTrackIndex = internalPrepareAudio(source_file)
        if (extractVideo() && mVideoTrackIndex < 0 || extractAudio() && mAudioTrackIndex < 0) {
            RuntimeException("No video and audio track found in " + source_file + " video: " + extractVideo() + " audio: " + extractAudio()).printStackTrace()
            return
        }
        synchronized(mSync) { mState = STATE_PREPARED }
    }

    private fun extractAudio(): Boolean {
        return mExtractionMode and (EXTRACT_TYPE_AUDIO_DECODED or EXTRACT_TYPE_AUDIO) > 0
    }

    private fun extractVideo(): Boolean {
        return mExtractionMode and (EXTRACT_TYPE_VIDEO_DECODED or EXTRACT_TYPE_VIDEO) > 0
    }

    private fun decodeAudio(): Boolean {
        return mExtractionMode and EXTRACT_TYPE_AUDIO_DECODED > 0
    }

    private fun decodeVideo(): Boolean {
        return mExtractionMode and EXTRACT_TYPE_VIDEO_DECODED > 0
    }

    private var mBeginTime: Long = 0
    private var mEndTime: Long = 0
    private var mVideoLength: Long = 0 // us

    /**
     * @param source_path source file path to be decoded
     * @return first video track index, -1 if not found
     */
    private fun internalPrepareVideo(source_path: String?): Int {
        var trackindex = -1
        mVideoMediaExtractor = MediaExtractor()
        try {
            mVideoMediaExtractor.setDataSource(source_path!!)
            trackindex = selectTrack(mVideoMediaExtractor, "video/")
            if (trackindex >= 0) {
                mVideoMediaExtractor.selectTrack(trackindex)
                mVideoMediaExtractor.seekTo(mBeginTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val format = mVideoMediaExtractor.getTrackFormat(trackindex)
                if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                    mVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                } else {
                    Log.d(TAG, "WTF, no video width")
                }
                if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    mVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                } else {
                    Log.d(TAG, "WTF, no video height")
                }
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    val rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                    if (rotation == 90 || rotation == 270 || rotation == -90) {
                        val temp = mVideoHeight
                        mVideoHeight = mVideoWidth
                        mVideoWidth = temp
                    }
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    mDuration = format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    Log.d(TAG, "WTF, no video duration")
                }
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    mVideoFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                }
                mVideoFrameInterval = if (mVideoFrameRate > 0) {
                    1000 / mVideoFrameRate
                } else {
                    17
                }
                if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    mVideoBitRate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                } else {
                    Log.d(TAG, "WTF, no video bit rate")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                        mProfile = format.getInteger(MediaFormat.KEY_PROFILE)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (format.containsKey(MediaFormat.KEY_LEVEL)) {
                            mLevel = format.getInteger(MediaFormat.KEY_LEVEL)
                        }
                    }
                }
                if (VERBOSE) Log.v(
                    TAG, String.format(
                        "format:size(%d,%d),duration=%d,bps=%d,framerate=%d,rotation=%d,pr=%d,lvl=%d",
                        mVideoWidth,
                        mVideoHeight,
                        mDuration,
                        mBitrate,
                        mVideoFrameRate,
                        mRotation,
                        mProfile,
                        mLevel
                    )
                )
            }
        } catch (e: Exception) {
            mVideoMediaExtractor.release()
        }
        mVideoEncoderConfig = VideoConfig(mVideoWidth, mVideoHeight, mVideoBitRate, profile = mProfile, level = mLevel)
        return trackindex
    }

    /**
     * @param source_file source file path to be decoded
     * @return first audio track index, -1 if not found
     */
    private fun internalPrepareAudio(source_file: String?): Int {
        var trackindex = -1
        mAudioMediaExtractor = MediaExtractor()
        try {
            mAudioMediaExtractor.setDataSource(source_file!!)
            trackindex = selectTrack(mAudioMediaExtractor, "audio/")
            if (trackindex >= 0) {
                mAudioMediaExtractor.selectTrack(trackindex)
                mAudioMediaExtractor.seekTo(mBeginTime, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val format = mAudioMediaExtractor.getTrackFormat(trackindex)
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    mAudioChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    Log.w(TAG, "WTF, no audio channels")
                }
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    mAudioSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    Log.w(TAG, "WTF, no audio sample rate")
                }
                mAudioBitRate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    format.getInteger(MediaFormat.KEY_BIT_RATE)
                } else {
                    96000
                }
                val min_buf_size = AudioTrack.getMinBufferSize(
                    mAudioSampleRate,
                    if (mAudioChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                /*                int max_input_size = 0;
                if (!format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    max_input_size = 21112; //sahil.bajaj random
                } else {
                    max_input_size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                }*/mAudioInputBufSize = if (min_buf_size > 0) min_buf_size else 4 * 2048
                /*                if (mAudioInputBufSize > max_input_size) mAudioInputBufSize = max_input_size;*/
                val frameSizeInBytes = mAudioChannels * 2
                mAudioInputBufSize =
                    (mAudioInputBufSize + (frameSizeInBytes - 1)) / frameSizeInBytes * frameSizeInBytes
                if (DEBUG) Log.v(
                    TAG,
                    String.format(
                        "getMinBufferSize=%d,mAudioInputBufSize=%d",
                        min_buf_size,
                        mAudioInputBufSize
                    )
                )
//                prepareSilenceData(25, AUDIO_SAMPLE_RATE, mAudioChannels)
                //                prepareBeepData(25, AVUtils.AUDIO_SAMPLE_RATE, mAudioChannels, 20000);
//                configureAudioTrack();
            }
        } catch (e: Exception) {
            mAudioMediaExtractor.release()
        }
        mAudioEncoderConfig = AudioConfig(mAudioChannels, mAudioSampleRate, mAudioBitRate)
        return trackindex
    }

    private fun configureAudioTrack() {
        if (!mPlayAudio) return
        mAudioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            mAudioSampleRate,
            if (mAudioChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
            mAudioPCMEncoding,
            mAudioInputBufSize,
            AudioTrack.MODE_STREAM
        )
        setVolume(mAudioVolume)
        setAudioPlaybackSpeed(mPlaybackSpeed)
    }

    private fun startAudioPlayback() {
        if (!mPlayAudio) return
        if (extractVideo() && previousVideoPresentationTimeUs < 0) return
        Log.d(TAG, "startAudioPlayback")
        try {
            mAudioTrack!!.play()
            mAudioPlaying = true
        } catch (e: Exception) {
            Log.e(TAG, "failed to start audio track playing", e)
            mAudioTrack!!.release()
            mAudioTrack = null
        }
    }

    private fun updateMovieInfo() {
        mBitrate = 0
        mRotation = mBitrate
        mVideoHeight = mRotation
        mVideoWidth = mVideoHeight
        mDuration = 0
        mFrameRate = 0f
        var value = mMetadata!!.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        if (!TextUtils.isEmpty(value)) {
            try {
                mVideoWidth = value!!.toInt()
            } catch (e: NumberFormatException) {
                e.printStackTrace()
            }
        }
        value = mMetadata!!.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        if (!TextUtils.isEmpty(value)) {
            try {
                mVideoHeight = value!!.toInt()
            } catch (e: NumberFormatException) {
                e.printStackTrace()
            }
        }
        value = mMetadata!!.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        if (!TextUtils.isEmpty(value)) {
            try {
                mRotation = value!!.toInt()
            } catch (e: NumberFormatException) {
                e.printStackTrace()
            }
        }
        value = mMetadata!!.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        if (!TextUtils.isEmpty(value)) {
            try {
                mBitrate = value!!.toInt()
            } catch (e: NumberFormatException) {
                e.printStackTrace()
            }
        }
        value = mMetadata!!.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        if (!TextUtils.isEmpty(value)) {
            try {
                mDuration = value!!.toLong() * 1000
            } catch (e: NumberFormatException) {
                e.printStackTrace()
            }
        }
    }

    private fun handleStartRender() {
        mShouldLoop = false
        prepareForLoopback()
        sampleIndex = 0
        beepOffset = 0
        mPlayTime = -1
        mWaitTime = 0
        mRequestTime = mBeginTime
        mCanStartAudio = !mRealTimeExtract
        mLatestPresentationTimeUs = -1
        mPrevKeyFrameTs = -1
        //        handleSeek(mBeginTime);
        mAudioCodecReady = false
        mVideoCodecReady = false
        mTimeBase.reset()
        if (mVideoTrackIndex >= 0) {
            mVideoOutputDone = false
            mVideoInputDone = mVideoOutputDone
            videoThread = Thread(mVideoTask, "VideoTask")
        }
        if (mAudioTrackIndex >= 0) {
            mAudioOutputDone = false
            mAudioInputDone = mAudioOutputDone
            audioThread = Thread(mAudioTask, "AudioTask")
        }
        if (VERBOSE) Log.d(TAG, "Creating video and audio threads")
        videoThread?.start()
        audioThread?.start()
    }

    private fun prepareForLoopback() {
        resetPlaybackState()
    }

    private fun resetPlaybackState() {
        previousVideoPresentationTimeUs = -1
        previousAudioPresentationTimeUs = -1
        mAudioStartTime = 0
    }

    private fun handleStart() {
        if (DEBUG) Log.v(TAG, "handleStart:")
        mRequestState = STATE_UNKNOWN
        synchronized(mSync) {
            if (mState == STATE_PLAYING) {
                Log.w(TAG, "Already playing. Ignoring!")
                return
            }
            if (mState < STATE_PREPARED) {
                throw RuntimeException("invalid state:$mState")
            }
            mRequestState = STATE_PLAYING
        }
        mPrevSeekTime = -1
        mClosestSeekReached = mBeginTime == 0L && !mExtractSeekFrames
        mSeekingVideo = false
        mSeekingAudio = mSeekingVideo
        seekVideo = false
        seekAudio = seekVideo
        if (mState == STATE_PREPARED) {
            mState = STATE_PLAYING
            mAudioPlaying = false
        }
        if (mSeekTime >= 0) {
            handleSeek(mSeekTime)
        } else if (mBeginTime > 0) {
            handleSeek(mBeginTime)
        }
        handleStartRender()
    }

    private fun startTimeBase() {
        if (mSeekTime >= 0) {
            mTimeBase.startAt(mSeekTime)
        } else {
            mTimeBase.startAt(mBeginTime)
        }
    }

    private fun stopTimebase() {
        mTimeBase.reset()
    }

    /**
     * @param media_extractor
     * @param trackIndex
     * @return
     */
    private fun internalStartVideo(media_extractor: MediaExtractor, trackIndex: Int): MediaCodec? {
        if (VERBOSE) Log.v(TAG, "internalStartVideo:")
        //        stopCurrentVideoCodecIfAny();
        var codec: MediaCodec? = null
        if (trackIndex >= 0) {
            val format = media_extractor!!.getTrackFormat(trackIndex)
            if (!format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                // TODO critical fix it.
                format.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate)
            }
            format.setInteger(MediaFormat.KEY_HEIGHT, mVideoHeight)
            format.setInteger(MediaFormat.KEY_WIDTH, mVideoWidth)
            format.setFloat(MEDIA_FORMAT_EXTENSION_KEY_DAR, mVideoWidth.toFloat() / mVideoHeight)
            // https://developer.android.com/reference/android/media/MediaCodec#creation
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
                format.setString(MediaFormat.KEY_FRAME_RATE, null)
            }
            Log.d(TAG, "Configuring with format: $format")
            val mime = format.getString(MediaFormat.KEY_MIME)
            try {
                codec = if (false && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val codecName =
                        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                    if (codecName != null) {
                        MediaCodec.createByCodecName(codecName)
                    } else {
                        MediaCodec.createDecoderByType(mime!!)
                    }
                } else {
                    MediaCodec.createDecoderByType(mime!!)
                }
                codec.stop()
                Log.d(
                    TAG,
                    "mOutputSurface = $mOutputSurface"
                )
                codec.configure(format, mOutputSurface, null, 0)
                codec.start()
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
            if (DEBUG) Log.v(TAG, "internalStartVideo:codec started")
        }
        return codec
    }

    /**
     * @param media_extractor
     * @param trackIndex
     * @return
     */
    private fun internalStartAudio(media_extractor: MediaExtractor, trackIndex: Int): MediaCodec? {
        if (VERBOSE) Log.v(TAG, "internalStartAudio:")
        var codec: MediaCodec? = null
        if (trackIndex >= 0) {
            val format = media_extractor.getTrackFormat(trackIndex)
            Log.d(TAG, "audio format: $format")
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null) {
                Log.e(TAG, "mime not found in format")
                return null
            }
            try {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
            if (DEBUG) Log.v(TAG, "internalStartAudio:codec started")
            //
            val buffers = codec.outputBuffers
            var sz = buffers[0].capacity()
            if (sz <= 0) sz = mAudioInputBufSize
            if (DEBUG) Log.v(TAG, "AudioOutputBufSize:$sz")
        }
        return codec
    }

    private fun handleSeek(newTime: Long) {
        var newTime = newTime
        if (newTime < mBeginTime) return
        if (newTime > mEndTime) newTime = mEndTime
        synchronized(mVideoPause) {
            synchronized(mAudioPause) {
                if (extractAudio() && seekAudio || extractVideo() && seekVideo) {
                    if (DEBUG) Log.d(
                        TAG,
                        "Already seeking to " + mSeekTime + ". Ignoring seek to " + newTime + " prevSeek: " + mPrevSeekTime + "seekAudio: " + seekAudio + " seekVideo: " + seekVideo
                    )
                    mNextSeek = newTime
                    mVideoPause.notifyAll()
                    mAudioPause.notifyAll()
                    return
                }
                if (DEBUG) Log.d(
                    TAG,
                    "Proceeding for seeking: $newTime"
                )
                mSeekTime = newTime
                mNextSeek = -1
                if (newTime >= 0) {
                    mPrevSeekTime = newTime
                }
            }
        }
        if (DEBUG) Log.d(
            TAG,
            "handleSeek: $newTime"
        )
        mRequestTime = -1
        if (extractVideo()) {
            synchronized(mVideoPause) {
                seekVideo = true
                mVideoPresentationTime = mSeekTime
                mVideoInputDone = false
                mVideoOutputDone = mVideoInputDone
                mVideoFrameRendered = false
                if (DEBUG) Log.d(
                    TAG,
                    "Notify video seek"
                )
                mVideoPause.notifyAll()
            }
        }
        if (extractAudio()) {
            synchronized(mAudioPause) {
                seekAudio = true
                mAudioInputDone = false
                mAudioOutputDone = mAudioInputDone
                mAudioFrameRendered = false
                if (DEBUG) Log.d(
                    TAG,
                    "Notify audio seek"
                )
                mAudioPause.notifyAll()
            }
        }
    }

    /**
     * @param codec
     * @param extractor
     * @param presentationTimeUs
     * @param isAudio
     */
    private fun internalProcessInput(
        codec: MediaCodec,
        extractor: MediaExtractor,
        presentationTimeUs: Long,
        isAudio: Boolean
    ): Boolean {
//		if (DEBUG) Log.v(TAG, "internalProcessInput:presentationTimeUs=" + presentationTimeUs);
        var result = true
        while (mIsRunning) {
            val inputBufIndex = codec.dequeueInputBuffer(TIMEOUT_USEC.toLong())
            if (inputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (DEBUG) {
                    Log.d(TAG, (if (isAudio) "audio" else "video") + " input: try again later")
                }
                break
            }
            if (inputBufIndex >= 0) {
                var inputBuf: ByteBuffer? = null
                inputBuf = codec.getInputBuffer(inputBufIndex)
                if (inputBuf == null) {
                    IllegalStateException("Codec input buffer null: $inputBufIndex").printStackTrace()
                    break
                }
                val size = extractor.readSampleData(inputBuf, 0)
                if (DEBUG) Log.d(
                    TAG,
                    (if (isAudio) "audio" else "video") + "\tqueueInputBuffer: " + inputBufIndex + " pts: " + presentationTimeUs + " size: " + size
                )
                if (size > 0) {
                    codec.queueInputBuffer(inputBufIndex, 0, size, presentationTimeUs, 0)
                }
                val isKeyFrame =
                    !isAudio && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                if (isKeyFrame) {
                    mPrevKeyFrameTs = presentationTimeUs
                }
                result = true
                if ((!mFeedSilenceFrames || mFeedSilenceFrames && !pipelineReady)) {
                    result = extractor.advance() // return false if no data is available
                }
                break
            }
        }
        return result
    }

    private fun handleInputVideo() {
        val sampleTime = mVideoMediaExtractor.sampleTime
        val presentationTimeUs = sampleTime - mBeginTime
        if (DEBUG) Log.d(
            TAG,
            "video: sampleTime: $presentationTimeUs"
        )
        keepVideoSpeedInSync(presentationTimeUs)
        var b = sampleTime != -1L
        b = try {
            b && internalProcessInput(
                mVideoMediaCodec, mVideoMediaExtractor,
                presentationTimeUs, false
            )
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            return
        }
        if (DEBUG) Log.d(
            TAG,
            "handleInputVideo: status: $b"
        )
        if (!b) {
            if (VERBOSE) Log.i(TAG, "video track input reached EOS")
            var count = 10
            while (mIsRunning && count > 0) {
                count--
                var inputBufIndex = 0
                try {
                    inputBufIndex =
                        mVideoMediaCodec.dequeueInputBuffer((TIMEOUT_USEC * 2).toLong())
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                }
                if (VERBOSE) Log.d(
                    TAG,
                    "video dequeue status: $inputBufIndex"
                )
                if (inputBufIndex >= 0) {
                    mVideoMediaCodec.queueInputBuffer(
                        inputBufIndex, 0, 0, 0L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    if (DEBUG) Log.v(
                        TAG,
                        "sent input EOS:$mVideoMediaCodec"
                    )
                    break
                }
            }
            synchronized(mSync) { mVideoInputDone = true }
        }
    }

    /**
     * @param frameCallback
     */
    @SuppressLint("WrongConstant")
    private fun handleOutputVideo(frameCallback: Any?) {
        if (DEBUG) Log.v(TAG, "handleOutputVideo")
        mVideoFrameRendered = false
        while (mIsRunning && !mVideoOutputDone && (!isVideoPausedRequested || seekVideo)) {
            var decoderStatus = 0
            decoderStatus = try {
                mVideoMediaCodec.dequeueOutputBuffer(mVideoBufferInfo!!, TIMEOUT_USEC.toLong())
            } catch (e: IllegalStateException) {
                e.printStackTrace()
                return
            }
            val presentationTimeUs = mVideoBufferInfo!!.presentationTimeUs
            mLatestPresentationTimeUs = presentationTimeUs + mBeginTime
            if (DEBUG) Log.d(
                TAG,
                "handleOutputVideo: decoderStatus: $decoderStatus"
            )
            if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (DEBUG) {
                    Log.w(TAG, "Video try again later")
                }
                try {
                    Thread.sleep(1)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
                break
            } else {
                mVideoFrameRendered = true
                mIsReadyForNextFrame = false
                if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    if (VERBOSE) Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED:")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = mVideoMediaCodec.outputFormat
                    if (VERBOSE) Log.d(
                        TAG,
                        "video decoder output format changed: $newFormat"
                    )
                } else if (decoderStatus < 0) {
                    throw RuntimeException(
                        "unexpected result from video decoder.dequeueOutputBuffer: $decoderStatus"
                    )
                } else { // decoderStatus >= 0
                    var doRender = false
                    if (mVideoBufferInfo!!.size > 0) {
                        doRender = (mVideoBufferInfo!!.size != 0)
                        mClosestSeekReached =
                            mSeekTime - (presentationTimeUs + mBeginTime) < mVideoFrameInterval || Math.abs(
                                presentationTimeUs + mBeginTime - mEndTime
                            ) < 235000
                        doRender =
                            doRender && (!mExtractSeekFrames && !seekVideo || mExtractSeekFrames && seekVideo && mClosestSeekReached)
                        if (DEBUG) {
                            Log.d(
                                TAG,
                                "video releaseOutputBuffer: " + "decoderstatus: " + String.format(
                                    "%2d",
                                    decoderStatus
                                ) + "\tdoRender: " + doRender + "\tvideots: " + presentationTimeUs + "\tsize: " + mVideoBufferInfo!!.size + "\t flags: " + mVideoBufferInfo!!.flags
                            )
                        }
                        if (doRender) {
                            mVideoPresentationTime = presentationTimeUs
                            currentPosition = if (extractVideo()) Math.min(
                                mAudioPresentationTime,
                                mVideoPresentationTime
                            ) else mVideoPresentationTime
                        }
                    }
                    var eos = false
                    if (DEBUG) {
                        Log.d(
                            TAG,
                            "videoTs: $presentationTimeUs expected Length: $mVideoLength"
                        )
                    }
                    if (presentationTimeUs >= mVideoLength) {
                        if (DEBUG) Log.d(
                            TAG,
                            "Forcing EOSpts: $presentationTimeUs expected duration: $mVideoLength"
                        )
                        mShouldLoop = isLooping
                        synchronized(mSync) {
                            mVideoOutputDone = true
                            mVideoInputDone = mVideoOutputDone
                            // Just being overly cautious and setting audio flags to done, JIC, someone is using them.
                            if (!extractAudio()) {
                                mAudioOutputDone = true
                                mAudioInputDone = mAudioOutputDone
                            }
                        }
                        eos = true
                    }
                    if (eos) {
                        mVideoBufferInfo!!.flags =
                            mVideoBufferInfo!!.flags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    }
                    try {
                        if (VERBOSE) Log.d(
                            TAG,
                            "doRender: $doRender"
                        )
                        if (doRender) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                if (VERBOSE) Log.d(
                                    TAG,
                                    "mExtractSeekFrames: $mExtractSeekFrames gotImageFrame: $gotImageFrame"
                                )
                                if (mExtractSeekFrames) {
                                    val image = mVideoMediaCodec.getOutputImage(decoderStatus)
                                    if (image != null) {
                                        if (VERBOSE) Log.d(
                                            TAG,
                                            "Captured image: " + image.width + "x" + image.height + "format" + image.format + "ts: " + image.timestamp
                                        )
                                        gotImageFrame = false
                                        if (mSeekFrameCallback != null) {
                                            val strides = IntArray(3)
                                            val t1 = System.currentTimeMillis()
                                            //                                            Log.d(TAG , "code start image to byte buffer " + SystemClock.currentThreadTimeMillis());
                                            val im420 = imageToByteBuffer(image, strides)
                                            // Alter the second parameter of this to the actual format you are receiving
                                            val pWidth = image.width
                                            val pHeight = image.height
                                            val yuv = YuvImage(
                                                im420,
                                                ImageFormat.NV21,
                                                pWidth,
                                                pHeight,
                                                strides
                                            )
                                            image.close()
                                            // bWidth and bHeight define the size of the bitmap you wish the fill with the preview image
                                            val os = ByteArrayOutputStream()
                                            yuv.compressToJpeg(Rect(0, 0, pWidth, pHeight), 50, os)
                                            os.flush()
                                            val `in`: InputStream =
                                                ByteArrayInputStream(os.toByteArray())
                                            val options = BitmapFactory.Options()
                                            if (mCaptureBitmap == null) {
                                                mCaptureBitmap = Bitmap.createBitmap(
                                                    pWidth,
                                                    pHeight,
                                                    Bitmap.Config.ARGB_8888
                                                )
                                            }
                                            options.inBitmap = mCaptureBitmap
                                            var bitmap: Bitmap? = null
                                            try {
                                                bitmap =
                                                    BitmapFactory.decodeStream(`in`, null, options)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                            `in`.close()
                                            if (VERBOSE) Log.d(
                                                TAG,
                                                "code end image to byte buffer -------------------" + (System.currentTimeMillis() - t1)
                                            )
                                            mSeekFrameCallback!!.onFrameAvailable(bitmap)
                                        } else {
                                            image.close()
                                        }
                                    } else {
                                        Log.d(TAG, "null image")
                                    }
                                    mVideoMediaCodec.releaseOutputBuffer(decoderStatus, false)
                                } else {
                                    mVideoMediaCodec.releaseOutputBuffer(
                                        decoderStatus,
                                        presentationTimeUs * 1000L
                                    )
                                }
                            } else {
                                mVideoMediaCodec.releaseOutputBuffer(decoderStatus, true)
                            }
                        } else {
                            mVideoMediaCodec.releaseOutputBuffer(decoderStatus, false)
                        }
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    if (mVideoBufferInfo!!.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (DEBUG) Log.d(TAG, "video:output EOS")
                        synchronized(mSync) { mVideoOutputDone = true }
                    }
                }
                break
            }
        }
    }

    private fun keepVideoSpeedInSync(presentationTimeUs: Long) {
        val timeBase =
            if (mRealTimeExtract) mTimeBase.currentTime else previousVideoPresentationTimeUs
        /*if (DEBUG) */if (DEBUG) Log.d(
            TAG,
            "timeBase: " + timeBase + " \tpresentationTime: " + presentationTimeUs + "\tseeking: " + (mPlayTime > 0)
        )
        if (seekVideo) {
        } else if (mRealTimeExtract) {
            if (presentationTimeUs - timeBase > 0L) {
                var tsDiff = ((presentationTimeUs - timeBase) / 1000L).toInt()
                tsDiff = Math.min(100, tsDiff)
                try {
                    Thread.sleep(tsDiff.toLong())
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            } else {
                // Can't rest, we're lagging behind.
            }
        } else {
            if (presentationTimeUs - timeBase > 100 * 1000L) {
                try {
                    Thread.sleep(40)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleInputAudio() {
        val audioMediaCodec = mAudioMediaCodec ?: return
        val sampleTime = mAudioMediaExtractor.sampleTime
        val presentationTimeUs = sampleTime - mBeginTime
        val b: Boolean
        b = try {
            internalProcessInput(
                audioMediaCodec, mAudioMediaExtractor,
                presentationTimeUs, true
            )
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            return
        }
        if (!b) {
            if (DEBUG) Log.i(TAG, "audio track input reached EOS")
            var count = 10
            while (mIsRunning && count > 0) {
                count--
                var inputBufIndex = 0
                try {
                    inputBufIndex = audioMediaCodec.dequeueInputBuffer((TIMEOUT_USEC * 2).toLong())
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                }
                if (inputBufIndex >= 0) {
                    audioMediaCodec.queueInputBuffer(
                        inputBufIndex, 0, 0, 0L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    if (DEBUG) Log.v(TAG, "sent input EOS:$mAudioMediaCodec")
                    break
                }
            }
            synchronized(mSync) { mAudioInputDone = true }
        }
    }

    @SuppressLint("WrongConstant")
    private fun handleOutputAudio( /*final IFrameCallback frameCallback*/) {
//		if (DEBUG) Log.v(TAG, "handleDrainAudio:");
        mAudioFrameRendered = false
        val audioMediaCodec = mAudioMediaCodec ?: return
        while (mIsRunning && !mAudioOutputDone && (!isAudioPausedRequested || seekAudio)) {
            var decoderStatus = 0
            decoderStatus = try {
                audioMediaCodec.dequeueOutputBuffer(mAudioBufferInfo!!, TIMEOUT_USEC.toLong())
            } catch (e: IllegalStateException) {
                e.printStackTrace()
                return
            }
            if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (DEBUG) Log.d(TAG, "audio output: try again later")
                return
            } else {
                mAudioFrameRendered = true
                if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    if (DEBUG) Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED:")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = audioMediaCodec.outputFormat
                    if (DEBUG) Log.d(
                        TAG,
                        "audio decoder output format changed: $format"
                    )
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        mAudioChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else {
                        Log.w(TAG, "WTF, no audio channels")
                    }
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        mAudioSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        Log.w(TAG, "WTF, no audio sample rate")
                    }
                    mAudioBitRate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        format.getInteger(MediaFormat.KEY_BIT_RATE)
                    } else {
                        96000
                    }
                    mAudioPCMEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    configureAudioTrack()
                } else if (decoderStatus < 0) {
                    throw RuntimeException(
                        "unexpected result from audio decoder.dequeueOutputBuffer: $decoderStatus"
                    )
                } else { // decoderStatus >= 0
                    if (mAudioBufferInfo!!.size > 0) {
                        if (DEBUG) Log.d(
                            TAG,
                            "extracted audio timestamp: " + mAudioBufferInfo!!.presentationTimeUs.toString() + "\toffset: " + mAudioBufferInfo!!.offset + "\tsize: " + mAudioBufferInfo!!.size
                        )
                        var eos = false
                        val audioTs = mAudioBufferInfo!!.presentationTimeUs
                        pipelineReady = true
                        if (DEBUG) Log.d(
                            TAG,
                            "audioTs: $audioTs expected Length: $mVideoLength"
                        )
                        if (audioTs > mVideoLength) {
                            eos = true
                        }

                        // Unlike video, where releasing output buffer renders to
                        // the bound SurfaceTexture which is then fed to encoder,
                        // we have to do it manually in case of audio.
                        mAudioPresentationTime = audioTs
                        currentPosition = if (extractVideo()) Math.min(
                            mAudioPresentationTime,
                            mVideoPresentationTime
                        ) else mAudioPresentationTime
                        var buffer = mAudioOutputBuffers!![decoderStatus]
                        var size = mAudioBufferInfo!!.size
                        var offset = mAudioBufferInfo!!.offset
                        if (mCurrBeginTime < 0) mCurrBeginTime = audioTs
                        if (mCallback != null) {
                            mCallback.sendAudioFrame(
                                mAudioOutputBuffers!![decoderStatus],
                                mAudioBufferInfo,
                                eos
                            )
                        } else {
                            setPrevAudioFrameTs(mAudioPresentationTime)
                        }
                        if (eos) {
                            val eosBuffer = ByteBuffer.allocate(0)
                            val eosInfo = cloneBufferInfo(mAudioBufferInfo)
                            eosInfo.offset = 0
                            eosInfo.size = 0
                            eosInfo.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
/*                            if (mCallback != null) {
                                mCallback.sendAudioFrame(eosBuffer, eosInfo, eos)
                            }*/
                        }

/*                    if (!frameCallback.onFrameAvailable(mAudioBufferInfo.presentationTimeUs))
                        mAudioStartTime = adjustPresentationTime(mAudioSync, mAudioStartTime, mAudioBufferInfo.presentationTimeUs);*/if (eos) {
                            mAudioOutputDone = true
                            mAudioInputDone = mAudioOutputDone
                            // Just being overly cautious and setting video flags to done.
                            if (!extractVideo()) {
                                mVideoOutputDone = true
                                mVideoInputDone = mVideoOutputDone
                            }
                        }
                    }
                    try {
                        if (DEBUG) {
                            Log.d(
                                TAG,
                                "audio releaseOutputBuffer: " + "decoderstatus: " + String.format(
                                    "%2d",
                                    decoderStatus
                                ) + "\taudiots: " + mAudioBufferInfo!!.presentationTimeUs + "\tsize: " + mAudioBufferInfo!!.size + "\t flags: " + mAudioBufferInfo!!.flags
                            )
                        }
                        audioMediaCodec.releaseOutputBuffer(decoderStatus, false)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                    }
                    if (mAudioBufferInfo!!.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (DEBUG) Log.d(TAG, "audio:output EOS")
                        synchronized(mSync) { mAudioOutputDone = true }
                    }
                }
                break
            }
        }
    }

    fun cloneBufferInfo(bufferInfo: MediaCodec.BufferInfo?): MediaCodec.BufferInfo {
        val outBufferInfo = MediaCodec.BufferInfo()
        outBufferInfo[bufferInfo!!.offset, bufferInfo.size, bufferInfo.presentationTimeUs] =
            bufferInfo.flags
        return outBufferInfo
    }

    /**
     * adjusting frame rate
     *
     * @param sync
     * @param startTime
     * @param presentationTimeUs
     * @return startTime
     */
    private fun adjustPresentationTime(sync: Object, startTime: Long, presentationTimeUs: Long): Long {
        return if (startTime > 0) {
            var t = presentationTimeUs - (System.nanoTime() / 1000 - startTime)
            while (t > 0) {
                synchronized(sync) {
                    try {
                        sync.wait(t / 1000, (t % 1000 * 1000).toInt())
                    } catch (e: InterruptedException) {
                    }
                }
                t = presentationTimeUs - (System.nanoTime() / 1000 - startTime)
            }
            startTime
        } else {
            System.nanoTime() / 1000
        }
    }

    var inputChunk = 0
    var inputBuf: ByteBuffer? = null
    private fun handleInputMuxing(extractor: MediaExtractor?, trackIndex: Int) {
        var bufferInfoSize = 0
        val bufferInfoOffset = 0
        var bufferInfoPTS: Long = 0
        var bufferInfoFlag = 0
        if (inputBuf == null) {
            inputBuf = ByteBuffer.allocate(512 * 512)
        }
        if (!mAudioInputDone) {
            val chunkSize = extractor!!.readSampleData(inputBuf!!, 0)
            val flag = extractor.sampleFlags
            val presentationTimeUs = extractor.sampleTime
            Log.d(
                TAG,
                "time : $presentationTimeUs track $trackIndex"
            )
            previousAudioPresentationTimeUs =
                presentationTimeUs //TODO do the same for video as well
            if (presentationTimeUs > mEndTime) {
                mAudioInputDone = true
            } else {
                if (flag == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) bufferInfoFlag =
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG else if (flag == MediaCodec.BUFFER_FLAG_SYNC_FRAME) bufferInfoFlag =
                    MediaCodec.BUFFER_FLAG_SYNC_FRAME else if (flag == MediaCodec.BUFFER_FLAG_END_OF_STREAM) bufferInfoFlag =
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
            }
            Log.d(TAG, "flag : $flag track $trackIndex")
            if (chunkSize < 0) {
                mAudioOutputDone = true
                mAudioInputDone = mAudioOutputDone
                Log.d(TAG, "sent input EOS")
            } else {
                if (extractor.sampleTrackIndex != trackIndex) {
                    Log.w(
                        TAG, "WEIRD: got sample from track " +
                                extractor.sampleTrackIndex + ", expected " + trackIndex
                    )
                }
                bufferInfoSize = chunkSize
                bufferInfoPTS = extractor.sampleTime
                Log.d(
                    TAG, "submitted frame " + inputChunk + " to dec, size=" +
                            chunkSize
                )
                inputChunk++
                extractor.advance()
            }
            val bufferInfo = MediaCodec.BufferInfo()
            val encodedData: ByteBuffer
            if (mAudioInputDone) {
                bufferInfo[0, 1, bufferInfoPTS] = MediaCodec.BUFFER_FLAG_END_OF_STREAM
                val data = ByteArray(1)
                Log.d(
                    TAG,
                    "Sent EOS at time: $bufferInfoPTS"
                )
                encodedData = ByteBuffer.wrap(data)
            } else {
                bufferInfo[bufferInfoOffset, bufferInfoSize, bufferInfoPTS] = bufferInfoFlag
                inputBuf!!.position(bufferInfo.offset)
                inputBuf!!.limit(bufferInfo.offset + bufferInfo.size)
                encodedData = ByteBuffer.allocate(bufferInfo.size)
                encodedData.put(inputBuf!!.array(), bufferInfo.offset, bufferInfo.size)
            }
            Log.d(
                TAG, "sent " + bufferInfo.size + " bytes to muxer, ts=" +
                        bufferInfo.presentationTimeUs
            )
            val mediaFrame =
                Muxer.MediaFrame(encodedData, bufferInfo, -1, Muxer.MediaFrame.FRAME_TYPE_AUDIO_EOS, -1)
            // mCallback.sendDirectAudioFrame(mediaFrame)
        }
        if (mAudioInputDone) {
            if (DEBUG) Log.i(TAG, "video track input reached EOS")
            synchronized(mSync) {
                if (trackIndex == mVideoTrackIndex) {
                    mVideoInputDone = true
                    mVideoOutputDone = true
                } else if (trackIndex == mAudioTrackIndex) {
                    mAudioInputDone = true
                    mAudioOutputDone = true
                    // Fake Video track end in case we're extracting just audio
                    if (mVideoTrackIndex < 0) {
                        mVideoInputDone = true
                        mVideoOutputDone = true
                    }
                }
            }
        }
    }

    private fun handleStop(force: Boolean) {
        if (DEBUG) Log.v(TAG, "handleStop:")
        val prevState = mState
        mRequestState = STATE_UNKNOWN
        synchronized(mSync) {
            mNextSeek = -1
            if (mState < STATE_PLAYING && !force) {
                return
            }
            mAudioOutputDone = true
            mAudioInputDone = mAudioOutputDone
            mVideoOutputDone = mAudioInputDone
            mVideoInputDone = mVideoOutputDone
            mState = STATE_PREPARED
            mPrevSeekTime = -1
            mSeekTime = -1
            mRequestTime = -1
        }
        /*        if (prevState != STATE_PREPARED) {*/synchronized(mAudioPause) { mAudioPause.notifyAll() }
        synchronized(mVideoPause) { mVideoPause.notifyAll() }
        /*        }*/if (force) {
            if (videoThread != null && videoThread!!.isAlive) {
                try {
                    videoThread!!.join()
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
            if (audioThread != null && audioThread!!.isAlive) {
                try {
                    audioThread!!.join()
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
/*            if (prevState >= STATE_PREPARED) {
                AndroidUtilities.runOnUIThread(Runnable {
                    if (mOnCompletionListener != null) {
                        mOnCompletionListener.onCompletion(this@AVMediaExtractor)
                    } else {
                        Log.w(TAG, "onCompletionListener is NULL")
                    }
                })
            }*/
            stopTimebase()
            Log.d(TAG, "Audio Video threads finished")
        }
    }

    private fun handleRelease() {
        if (DEBUG) Log.v(TAG, "handleRelease:")
        synchronized(mAudioPause) {
            synchronized(mSync) {
                mAudioOutputDone = true
                mAudioInputDone = mAudioOutputDone
                mVideoOutputDone = mAudioInputDone
                mVideoInputDone = mVideoOutputDone
                mState = STATE_STOP
            }
            synchronized(mVideoTask) {
                internalStopVideo()
                mVideoTrackIndex = -1
            }
            synchronized(mAudioTask) {
                internalStopAudio()
                mAudioTrackIndex = -1
            }
            if (mVideoMediaCodec != null) {
                if (VERBOSE) Log.d(
                    TAG,
                    "Releasing video codec"
                )
                mVideoCodecReady = false
                try {
                    mVideoMediaCodec.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                }
                mVideoMediaCodec.release()
                mVideoMediaCodec = null
            }
            if (mAudioMediaCodec != null) {
                if (VERBOSE) Log.d(
                    TAG,
                    "Releasing audio codec"
                )
                mAudioCodecReady = false
                try {
                    mAudioMediaCodec.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                }
                mAudioMediaCodec.release()
                mAudioMediaCodec = null
            }
            mVideoMediaExtractor.release()

            mAudioMediaExtractor.release()

            mAudioBufferInfo = null
            mVideoBufferInfo = mAudioBufferInfo

            mMetadata?.release()
        }
    }

    private fun internalStopVideo() {
        if (DEBUG) Log.v(TAG, "internalStopVideo:")
    }

    private fun internalStopAudio() {
        if (DEBUG) Log.v(TAG, "internalStopAudio:")
        if (mAudioTrack != null) {
            if (mAudioTrack!!.state != AudioTrack.STATE_UNINITIALIZED) mAudioTrack!!.stop()
            mAudioTrack!!.release()
            mAudioTrack = null
        }
    }

    private fun handlePause() {
        if (mState != STATE_PLAYING) {
            if (DEBUG) Log.w(
                TAG,
                "handlePause in invalid state: $mState"
            )
            return
        }
        mRequestState = STATE_UNKNOWN
        if (VERBOSE) Log.v(TAG, "handlePause:")
        if (extractVideo()) {
            synchronized(mVideoPause) {
                if (!mVideoInputDone && !mVideoOutputDone) {
                    pauseTime = System.currentTimeMillis()
                    isVideoPausedRequested = true
                    mVideoPause.notify()
                }
            }
        }
        if (extractAudio()) {
            synchronized(mAudioPause) {
                if (!mAudioInputDone && !mAudioOutputDone) {
                    isAudioPausedRequested = true
                    mAudioPause.notify()
                }
            }
        }
        synchronized(mSync) { mState = STATE_PAUSED }
        if (VERBOSE) Log.d(
            TAG + "pause",
            "pausing $isVideoPausedRequested"
        )
    }

    private fun handleResume() {
        if (VERBOSE) Log.v(TAG, "handleResume:")
        synchronized(mSync) { mState = STATE_PLAYING }
        sampleIndex = 0
        beepOffset = 0

        if (extractVideo()) {
            synchronized(mVideoPause) {
                mVideoFrameRendered = false
                isVideoPausedRequested = false
                mVideoPause.notifyAll()
            }
        }
        if (extractAudio()) {
            synchronized(mAudioPause) {
                isAudioPausedRequested = false
                mAudioFrameRendered = false
                mAudioPause.notifyAll()
            }
        }
    }

    /**
     * search first track index matched specific MIME
     *
     * @param extractor
     * @param mimeType  "video/" or "audio/"
     * @return track index, -1 if not found
     */
    protected fun selectTrack(extractor: MediaExtractor, mimeType: String?): Int {
        val numTracks = extractor.trackCount
        var format: MediaFormat
        var mime: String?
        for (i in 0 until numTracks) {
            format = extractor.getTrackFormat(i)
            mime = format.getString(MediaFormat.KEY_MIME)
            if (mime!!.startsWith(mimeType!!)) {
                if (VERBOSE) {
                    Log.d(
                        TAG,
                        "Extractor selected track $i ($mime): $format"
                    )
                }
                format.setLong(MediaFormat.KEY_DURATION, mVideoLength)
                // TODO place a few checks here                mCallback.addTrack(format);
                return i
            }
        }
        return -1
    }

    fun setClipSize(startTime: Long, endTime: Long) {
        mBeginTime = startTime
        mEndTime = endTime
        Log.d(
            TAG,
            "Start time: $startTime End time: $mEndTime"
        )
        mVideoLength = mEndTime - mBeginTime
        mTimeBase.setBeginTime(mBeginTime)
        mCurrBeginTime = -1
    }

    fun readyForNextFrame() {
        synchronized(mReadyForNextFrame) {
            mIsReadyForNextFrame = true
            if (DEBUG) Log.d(
                TAG,
                "Notify for next frame"
            )
            mReadyForNextFrame.notifyAll()
        }
    }

    fun setExtractionMode(extractionMode: Int) {
        mExtractionMode = extractionMode
        playWhileSeek = extractVideo()
    }

    fun setDataSource(source: String) {
        mSourcePath = source
    }

    fun setSurface(surface: Surface?) {
        if (mExtractSeekFrames) {
            Log.w(TAG, "extract seek frames set to true. Ignoring output surface!")
            return
        }
        mOutputSurface = surface
    }

    fun setExtractSeekFrames(extractSeekFrames: Boolean) {
        mExtractSeekFrames = extractSeekFrames
    }

    fun setSeekFrameCallback(callback: SeekFrameCallback?) {
        mSeekFrameCallback = callback
    }

    @Synchronized
    fun setPlayAudio(playAudio: Boolean) {
        mPlayAudio = playAudio
    }

    @Synchronized
    fun setRealTimeExtract(realTimeExtract: Boolean) {
        mRealTimeExtract = realTimeExtract
    }

    fun onProgressUpdate(timestamp: Long) {}
    fun setVolume(audioVolume: Float) {
        mAudioVolume = audioVolume
        if (mAudioTrack == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mAudioTrack!!.setVolume(audioVolume)
        } else {
            mAudioTrack!!.setStereoVolume(audioVolume, audioVolume)
        }
    }

    private fun setAudioPlaybackSpeed(playbackSpeed: Float) {
        if (mAudioTrack != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val params = PlaybackParams()
                params.speed = playbackSpeed
                params.pitch = 1.0f
                mAudioTrack!!.playbackParams = params
            } else {
                mAudioTrack!!.playbackRate = mAudioSampleRate
            }
        }
    }

    fun setFeedSilenceFrames(feedSilenceFrames: Boolean) {
        mFeedSilenceFrames = feedSilenceFrames
        if (mFeedSilenceFrames) {
            pipelineReady = false
        }
    }

    var nv21: ByteArray? = null

    // TODO move it to an extension
    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private fun imageToByteBuffer(image: Image, strides: IntArray): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        if (nv21 == null) {
            nv21 = ByteArray(ySize + uvSize * 2)
        }
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V
        var rowStride = image.planes[0].rowStride
        for (i in image.planes.indices) {
            strides[i] = image.planes[i].rowStride
        }
        assert(image.planes[0].pixelStride == 1)
        var pos = 0
        if (rowStride == width) { // likely
            yBuffer[nv21, 0, ySize]
            pos += ySize
        } else {
            var yBufferPos = width - rowStride // not an actual position
            while (pos < ySize) {
                yBufferPos += rowStride - width
                yBuffer.position(yBufferPos)
                yBuffer[nv21, pos, width]
                pos += width
            }
        }
        rowStride = image.planes[2].rowStride
        val pixelStride = image.planes[2].pixelStride
        assert(rowStride == image.planes[1].rowStride)
        assert(pixelStride == image.planes[1].pixelStride)

/*        if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
            // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
            byte savePixel = vBuffer.get(1);
            vBuffer.put(1, (byte)0);
            if (uBuffer.get(0) == 0) {
                vBuffer.put(1, (byte)255);
                if (uBuffer.get(0) == 255) {
                    vBuffer.put(1, savePixel);
                    vBuffer.get(nv21, ySize, uvSize);

                    return nv21; // shortcut
                }
            }

            // unfortunately, the check failed. We must save U and V pixel by pixel
            vBuffer.put(1, savePixel);
        }*/

        // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
        // but performance gain would be less significant
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vuPos = col * pixelStride + row * rowStride
                nv21!![pos++] = vBuffer[vuPos]
                nv21!![pos++] = uBuffer[vuPos]
            }
        }
        return nv21!!
    }

    interface SeekFrameCallback {
        fun onFrameAvailable(image: Image?)
        fun onFrameAvailable(bitmap: Bitmap?)
    }

    companion object {
        private val DEBUG = false
        private const val TAG = "AVMediaExtractor:"
        private val VERBOSE = false
        const val EXTRACT_TYPE_AUDIO = 0X01 // Extracts and feeds raw audio frame
        const val EXTRACT_TYPE_VIDEO = 0X02 // Extracts and feeds raw audio frame
        const val EXTRACT_TYPE_AUDIO_DECODED = 0X04 // Extracts, decodes and feeds
        const val EXTRACT_TYPE_VIDEO_DECODED = 0X08 // Extracts, decodes and feeds
        private const val MEDIA_FORMAT_EXTENSION_KEY_DAR = "mpx-dar"

        //================================================================================
        private const val TIMEOUT_USEC = 2000 // milliseconds

        /*
     * STATE_CLOSED => [preapre] => STATE_PREPARED [start]
     * 	=> STATE_PLAYING => [seek] => STATE_PLAYING
     * 		=> [pause] => STATE_PAUSED => [resume] => STATE_PLAYING
     * 		=> [stop] => STATE_CLOSED
     */
        private const val STATE_UNKNOWN = -1
        private const val STATE_STOP = 0
        private const val STATE_PREPARED = 1
        private const val STATE_PLAYING = 2
        private const val STATE_PAUSED = 3
        private const val MSG_PREPARE = 1
        private const val MSG_START = 2
        private const val MSG_RESUME = 3
        private const val MSG_SEEK = 4
        private const val MSG_PAUSE = 5
        private const val MSG_STOP = 6
        private const val MSG_QUIT = 10

    }

    init {
        if (DEBUG) Log.v(TAG, "Constructor:")
        mAudioEnabled = true
        Thread(mMoviePlayerTask, TAG).start()
        synchronized(mSync) {
            try {
                if (!mIsRunning) mSync.wait()
            } catch (e: InterruptedException) {
            }
        }
    }
}