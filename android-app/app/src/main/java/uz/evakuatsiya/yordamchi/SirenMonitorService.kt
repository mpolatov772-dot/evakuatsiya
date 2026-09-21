package uz.evakuatsiya.yordamchi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SirenMonitorService : Service() {

    companion object {
        const val EXTRA_AUDIO_URI = "audio_uri"
        const val ACTION_STOP = "uz.evakuatsiya.yordamchi.STOP"
        const val EVENT_SIREN_DETECTED = "uz.evakuatsiya.yordamchi.SIREN_DETECTED"
        private const val SERVICE_CHANNEL = "siren_monitor"
        private const val ALARM_CHANNEL = "siren_alarm"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val ALARM_NOTIFICATION_ID = 1002
    }

    @Volatile private var stopping = false
    @Volatile private var alarmStarted = false
    private var monitorThread: Thread? = null
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        val audioUri = intent?.getStringExtra(EXTRA_AUDIO_URI)
        stopping = false
        startAsForeground()
        if (monitorThread?.isAlive != true) {
            monitorThread = Thread { monitorMicrophone(audioUri) }.also { it.start() }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ALERT, false)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Evakuatsiya kuzatuvi yoqilgan")
            .setContentText("Telefon sirenani eshitmoqda")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun monitorMicrophone(audioUriString: String?) {
        val sampleRate = 16_000
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) return

        val reference = audioUriString?.let { ReferenceModel.load(this, Uri.parse(it), sampleRate) }
        val detector = SirenDetector(reference, sampleRate)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minimum, sampleRate / 2)
        )

        try {
            record.startRecording()
            val buffer = ShortArray(2048)
            while (!stopping && !alarmStarted) {
                val count = record.read(buffer, 0, buffer.size)
                if (count > 0 && detector.accept(buffer, count)) {
                    triggerAlarm(audioUriString)
                    break
                }
            }
        } catch (_: Exception) {
            // Mikrofon boshqa ilova tomonidan ishlatilsa, foreground notification saqlanadi.
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
        }
    }

    private fun triggerAlarm(audioUriString: String?) {
        if (alarmStarted) return
        alarmStarted = true
        val event = Intent(EVENT_SIREN_DETECTED).setPackage(packageName)
        sendBroadcast(event)
        showAlarmNotification()
        val uri = audioUriString?.let { Uri.parse(it) } ?: Uri.parse("android.resource://$packageName/${R.raw.sirena}")
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_ALARM)
                setDataSource(this@SirenMonitorService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            mediaPlayer = MediaPlayer.create(this, R.raw.sirena)?.apply {
                isLooping = true
                start()
            }
        }
    }

    private fun showAlarmNotification() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ALERT, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            11,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SIRENA ANIQLANDI")
            .setContentText("Evakuatsiya xaritasini ochish uchun bosing")
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "Sirena kuzatuvi", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, "Evakuatsiya ogohlantirishi", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setBypassDnd(true)
            }
        )
    }

    private fun stopMonitoring() {
        stopping = true
        alarmStarted = true
        monitorThread?.interrupt()
        mediaPlayer?.release()
        mediaPlayer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private class SirenDetector(
    private val reference: FloatArray?,
    private val sampleRate: Int
) {
    private val history = ArrayDeque<Boolean>()
    private var previousBand = -1
    private var changes = 0

    fun accept(samples: ShortArray, count: Int): Boolean {
        if (count < 512) return false
        var sum = 0.0
        for (index in 0 until count) {
            val value = samples[index].toDouble() / Short.MAX_VALUE
            sum += value * value
        }
        val rms = sqrt(sum / count)
        if (rms < 0.018) {
            add(false)
            return false
        }

        val live = AudioMath.bandProfile(samples, count, sampleRate)
        val dominant = live.indices.maxByOrNull { live[it] } ?: -1
        if (dominant >= 0 && previousBand >= 0 && dominant != previousBand) changes++
        previousBand = dominant

        val similarity = reference?.let { AudioMath.cosine(live, it) } ?: 0f
        val candidate = if (reference != null) {
            similarity >= 0.62f && live.maxOrNull()!! >= 0.12f
        } else {
            live.maxOrNull()!! >= 0.20f
        }
        add(candidate)
        val hits = history.count { it }
        return history.size >= 8 && hits >= 6 && changes >= 3
    }

    private fun add(value: Boolean) {
        history.addLast(value)
        if (history.size > 8) history.removeFirst()
    }
}

private object ReferenceModel {
    fun load(context: Context, uri: Uri, sampleRate: Int): FloatArray? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val totals = FloatArray(AudioMath.BANDS.size)
            var frameCount = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            while (!outputDone && frameCount < 90) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: break
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val output = decoder.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val bytes = ByteArray(output.remaining())
                        output.get(bytes)
                        val shorts = ShortArray(bytes.size / 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        val frameSize = 2048
                        var offset = 0
                        while (offset + 512 <= shorts.size && frameCount < 90) {
                            val profile = AudioMath.bandProfile(shorts, min(frameSize, shorts.size - offset), sampleRate, offset)
                            for (i in totals.indices) totals[i] += profile[i]
                            frameCount++
                            offset += frameSize
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
            if (frameCount == 0) null else AudioMath.normalize(totals.map { it / frameCount }.toFloatArray())
        } catch (_: Exception) {
            null
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}

private object AudioMath {
    val BANDS = intArrayOf(300, 450, 600, 800, 1000, 1300, 1700, 2200)

    fun bandProfile(samples: ShortArray, count: Int, sampleRate: Int, offset: Int = 0): FloatArray {
        val profile = FloatArray(BANDS.size)
        for (i in BANDS.indices) profile[i] = goertzel(samples, count, sampleRate, BANDS[i], offset)
        return normalize(profile)
    }

    private fun goertzel(samples: ShortArray, count: Int, sampleRate: Int, frequency: Int, offset: Int): Float {
        val coefficient = 2.0 * cos(2.0 * Math.PI * frequency / sampleRate)
        var q1 = 0.0
        var q2 = 0.0
        for (i in 0 until count) {
            val sample = samples[offset + i].toDouble() / Short.MAX_VALUE
            val q0 = coefficient * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }
        return ((q1 * q1) + (q2 * q2) - coefficient * q1 * q2).toFloat().coerceAtLeast(0f)
    }

    fun normalize(values: FloatArray): FloatArray {
        val length = sqrt(values.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(0.0001f)
        return values.map { it / length }.toFloatArray()
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        val size = min(left.size, right.size)
        var total = 0f
        for (i in 0 until size) total += left[i] * right[i]
        return total
    }
}
