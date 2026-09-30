package com.wim.app

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WiM's own log notebook. Copies every line this process writes to Logcat
 * into a daily file that lives for 30 days, so a glitch from last Tuesday
 * can still be read next month.
 *
 * Why: the phone's Logcat is one shared 5 MB bucket for every app, and on
 * George's phone it holds about ten minutes before the oldest lines fall
 * out. Bugs that show up once a week were unprovable (9/24 lost-take was
 * caught only because it happened while a live logcat was attached).
 *
 * How: one daemon thread runs `logcat --pid=<us>` (own-process lines need
 * no permission since API 16) and appends to
 *   <externalFilesDir>/logs/wim-YYYY-MM-DD.log   (adb-pullable; falls back
 *   to internal filesDir if external storage is missing).
 * Files older than [KEEP_DAYS] are deleted at start and at every day roll.
 * Total size is capped at [MAX_TOTAL_BYTES] (oldest files go first).
 * If logcat dies, the thread restarts it after a short sleep.
 */
object WimLogFile {
    private const val TAG = "WiM"
    const val KEEP_DAYS = 30
    // 9/30: the first two days measured 49 MB and 8 MB, so a 60 MB cap held
    // about one day and had already thrown out 9/28. 85% of the lines are
    // the system's screen-drawing chatter (VRI, BLASTBufferQueue) from the
    // bubble overlay. George's rule is a month, so the cap is sized for a
    // month of the heavy day: 30 x 50 MB. Phone had 41 GB free.
    private const val MAX_TOTAL_BYTES = 1500L * 1024 * 1024
    private const val DAY_MS = 24L * 60 * 60 * 1000

    @Volatile private var started = false
    @Volatile private var dir: File? = null

    fun logDir(context: Context): File {
        dir?.let { return it }
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "logs").also { dir = it }
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val d = logDir(context.applicationContext)
        val t = Thread({ runForever(d) }, "WimLogFile")
        t.isDaemon = true
        t.start()
    }

    /**
     * Copy the file Whisper is about to hear into logs/audio/take-<stamp>.<ext>
     * and write its name to the log, so a bad take can be replayed later.
     * Keeps [AUDIO_KEEP_DAYS] days, capped at [AUDIO_MAX_TOTAL_BYTES].
     * Never throws; never blocks the upload for long (one small file copy).
     */
    fun keepTakeAudio(context: Context, src: File) {
        try {
            val d = File(logDir(context), "audio")
            d.mkdirs()
            val name = "take-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) +
                "." + src.extension
            val dst = File(d, name)
            src.copyTo(dst, overwrite = true)
            Log.i(TAG, "Take audio kept: ${dst.name} (${dst.length() / 1024} KB)")
            pruneAudio(d)
        } catch (t: Throwable) {
            Log.w(TAG, "keepTakeAudio failed: ${t.message}")
        }
    }

    const val AUDIO_KEEP_DAYS = 7
    private const val AUDIO_MAX_TOTAL_BYTES = 80L * 1024 * 1024

    private fun pruneAudio(d: File) {
        val all = d.listFiles { f -> f.name.startsWith("take-") }
            ?.sortedBy { it.name }?.toMutableList() ?: return
        val cutoff = System.currentTimeMillis() - AUDIO_KEEP_DAYS * DAY_MS
        val it = all.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (f.lastModified() < cutoff) { f.delete(); it.remove() }
        }
        var total = all.sumOf { it.length() }
        while (total > AUDIO_MAX_TOTAL_BYTES && all.size > 1) {
            val oldest = all.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    /** Newest-first list of the log files on disk (for a future Share button). */
    fun files(context: Context): List<File> =
        logDir(context).listFiles { f -> f.name.startsWith("wim-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: emptyList()

    private fun runForever(d: File) {
        val pid = android.os.Process.myPid()
        while (true) {
            try {
                prune(d)
                pump(d, pid)
            } catch (t: Throwable) {
                Log.w(TAG, "log file pump died: ${t.javaClass.simpleName}: ${t.message}")
            }
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
    }

    private fun pump(d: File, pid: Int) {
        d.mkdirs()
        val proc = ProcessBuilder(
            "logcat", "-v", "threadtime", "--pid=$pid", "-b", "main,system,crash"
        ).redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream), 16 * 1024)
        var day = ""
        var writer: FileWriter? = null
        var linesSinceFlush = 0
        try {
            writer = openForToday(d).also { day = todayKey() }
            writer.write("---- WimLogFile start pid=$pid ${stamp()} ----\n")
            writer.flush()
            while (true) {
                val line = reader.readLine() ?: break
                if (isSystemNoise(line)) continue
                val today = todayKey()
                if (today != day) {
                    writer?.close()
                    prune(d)
                    writer = openForToday(d)
                    day = today
                    linesSinceFlush = 0
                }
                writer!!.write(line)
                writer.write('\n'.code)
                // Flush often: a crash or force-stop must not eat the last lines.
                if (++linesSinceFlush >= 8) { writer.flush(); linesSinceFlush = 0 }
            }
        } finally {
            try { writer?.flush(); writer?.close() } catch (_: Throwable) {}
            try { proc.destroy() } catch (_: Throwable) {}
        }
    }

    // Android's own drawing chatter for the overlay window. Measured 9/29:
    // 160,851 of 190,067 lines (85%, 49 MB) were these tags; WiM's own
    // lines were 1 in 7. Nothing in them has ever explained a bug.
    private val NOISE_TAGS = arrayOf(
        "VRI[", "BLASTBufferQueue_Java:", "BLASTBufferQueue:", "InsetsController:",
        "ReflectedParamUpdater:", "AidlBufferPool", "Codec2Client:", "CCodec",
        "MediaCodecList:", "MediaCodec:", "Choreographer:", "HWUI",
        "FenceTime:", "InputTransport:", "ViewRootImpl:"
    )
    private val THREADTIME_FIELDS = Regex("\\s+")

    /** threadtime line: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: msg" — the tag is field 6. */
    internal fun isSystemNoise(line: String): Boolean {
        val fields = line.trim().split(THREADTIME_FIELDS, limit = 6)
        if (fields.size < 6) return false
        val tagAndMsg = fields[5]
        for (t in NOISE_TAGS) if (tagAndMsg.startsWith(t)) return true
        return false
    }

    private fun openForToday(d: File): FileWriter =
        FileWriter(File(d, "wim-${todayKey()}.log"), /* append = */ true)

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** Delete files older than KEEP_DAYS, then oldest-first until under the size cap. */
    private fun prune(d: File) {
        val all = d.listFiles { f -> f.name.startsWith("wim-") && f.name.endsWith(".log") }
            ?.sortedBy { it.name }?.toMutableList() ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * DAY_MS
        val it = all.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (f.lastModified() < cutoff) { f.delete(); it.remove() }
        }
        var total = all.sumOf { it.length() }
        while (total > MAX_TOTAL_BYTES && all.size > 1) {
            val oldest = all.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }
}
