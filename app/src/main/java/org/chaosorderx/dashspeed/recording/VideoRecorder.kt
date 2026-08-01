package org.chaosorderx.dashspeed.recording

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRecorder
    @Inject
    constructor() {
        // Phase 9: PixelCopy capture loop → MediaCodec → MediaMuxer → MP4
        var isRecording = false
            private set

        fun start() = Unit

        fun stop() = Unit
    }
