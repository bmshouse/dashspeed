package org.chaosorderx.dashspeed.debug

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.chaosorderx.dashspeed.BuildConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-build-only session log, pulled off the phone after a drive:
 *
 * ```
 * adb pull /sdcard/Android/data/org.chaosorderx.dashspeed.debug/files/debug
 * ```
 *
 * Each app run writes a session folder holding `frames.jsonl` (per-frame state), `snap_*.jpg`
 * (annotated frames, periodic and on events), `mask.json`, `learner.json` and `prefs.json`.
 * All file IO and JPEG encoding happen on one background thread. In release builds every call
 * is a no-op ([enabled] is false) and nothing is written.
 */
@Singleton
class DebugRecorder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val enabled: Boolean = BuildConfig.DEBUG

        private val executor by lazy {
            Executors.newSingleThreadExecutor { Thread(it, "debug-recorder").apply { isDaemon = true } }
        }
        private val pendingReason = AtomicReference<String?>(null)
        private val queuedSnapshots = AtomicInteger(0)

        // Touched only from the analysis thread.
        private var lastPeriodicMs = 0L
        private var lastEventMs = 0L
        private var lastMaskDropMs = 0L

        // Touched only from the executor thread.
        private var sessionDir: File? = null
        private var framesWriter: BufferedWriter? = null
        private var lastFlushMs = 0L
        private var snapshotSeq = 0

        /** Ask for a snapshot on the next analysed frame (e.g. a track appeared); first request wins. */
        fun requestSnapshot(reason: String) {
            if (enabled) pendingReason.compareAndSet(null, reason)
        }

        /** User-flagged moment: a marker line plus an annotated and a raw frame. */
        fun mark() {
            if (!enabled) return
            appendLine(JSONObject().put("type", "mark").put("wall", System.currentTimeMillis()))
            pendingReason.set(MARK_REASON)
        }

        /** Decides on the analysis thread whether this frame should be snapshotted, and why. */
        fun snapshotReason(
            wallMs: Long,
            droppedByMask: Boolean,
        ): String? {
            if (!enabled || queuedSnapshots.get() >= MAX_QUEUED_SNAPSHOTS) return null
            // Marks always go through; other requested events are rate-limited so a busy scene
            // (tracks appearing every few frames) can't flood the disk.
            val pending = pendingReason.getAndSet(null)
            val eventDue = wallMs - lastEventMs >= EVENT_MIN_GAP_MS
            val reason =
                when {
                    pending == MARK_REASON -> MARK_REASON
                    pending != null && eventDue -> pending
                    droppedByMask && eventDue && wallMs - lastMaskDropMs >= MASK_DROP_MIN_GAP_MS -> "mask_drop"
                    wallMs - lastPeriodicMs >= PERIODIC_GAP_MS -> "periodic"
                    else -> null
                }
            if (reason != null) {
                lastPeriodicMs = wallMs
                if (reason != "periodic") lastEventMs = wallMs
                if (reason == "mask_drop") lastMaskDropMs = wallMs
            }
            return reason
        }

        fun appendLine(line: JSONObject) {
            if (!enabled) return
            val text = line.toString()
            runIo {
                framesWriter().apply {
                    write(text)
                    newLine()
                    val now = System.currentTimeMillis()
                    if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                        flush()
                        lastFlushMs = now
                    }
                }
            }
        }

        /** Overwrites `<session>/<name>` with [json] (latest state of mask, learner, prefs). */
        fun writeJson(
            name: String,
            json: JSONObject,
        ) {
            if (!enabled) return
            val text = json.toString(2)
            runIo { File(session(), name).writeText(text) }
        }

        /** Queues an annotated JPEG; [rawToo] additionally saves the untouched frame. */
        fun saveSnapshot(
            request: SnapshotRequest,
            rawToo: Boolean,
        ) {
            if (!enabled) return
            queuedSnapshots.incrementAndGet()
            runIo {
                try {
                    val stem = "snap_%05d_%s".format(snapshotSeq++, request.reason)
                    if (rawToo) writeJpeg(File(session(), "${stem}_raw.jpg"), request.frame)
                    SnapshotRenderer.draw(request)
                    writeJpeg(File(session(), "$stem.jpg"), request.frame)
                } finally {
                    request.frame.recycle()
                    queuedSnapshots.decrementAndGet()
                }
            }
        }

        // Runs on the executor. Debug logging must never take the app down, so any failure
        // (IO, bitmap, JSON) is logged and swallowed.
        @Suppress("TooGenericExceptionCaught")
        private fun runIo(block: () -> Unit) {
            executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Timber.w(e, "DebugRecorder: write failed")
                }
            }
        }

        private fun framesWriter(): BufferedWriter =
            framesWriter ?: File(session(), "frames.jsonl").bufferedWriter().also { framesWriter = it }

        private fun session(): File =
            sessionDir ?: run {
                val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "debug")
                trimOldSessions(root)
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(root, stamp).also {
                    it.mkdirs()
                    sessionDir = it
                    Timber.i("DebugRecorder: session at ${it.absolutePath}")
                }
            }

        private companion object {
            const val MARK_REASON = "mark"
            const val PERIODIC_GAP_MS = 5_000L
            const val EVENT_MIN_GAP_MS = 2_000L
            const val MASK_DROP_MIN_GAP_MS = 10_000L
            const val FLUSH_INTERVAL_MS = 1_000L
            const val MAX_QUEUED_SNAPSHOTS = 4
        }
    }

private fun writeJpeg(
    file: File,
    bitmap: Bitmap,
) {
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
}

/** Deletes the oldest session folders until the total is under [MAX_TOTAL_BYTES]. */
private fun trimOldSessions(root: File) {
    val sessions = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return
    var total = sessions.sumOf { dir -> dir.sizeBytes() }
    for (dir in sessions) {
        if (total <= MAX_TOTAL_BYTES) break
        total -= dir.sizeBytes()
        dir.deleteRecursively()
    }
}

private fun File.sizeBytes(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }

private const val JPEG_QUALITY = 85
private const val MAX_TOTAL_BYTES = 500L * 1024 * 1024
