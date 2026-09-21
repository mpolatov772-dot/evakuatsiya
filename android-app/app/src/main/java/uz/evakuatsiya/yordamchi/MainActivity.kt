package uz.evakuatsiya.yordamchi

import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : Activity(), SensorEventListener {

    companion object {
        const val EXTRA_ALERT = "alert"
        private const val PICK_MAP = 101
        private const val PICK_AUDIO = 102
        private const val PERMISSIONS = 501
    }

    private lateinit var mapPreview: FloorPlanView
    private lateinit var audioStatus: TextView
    private lateinit var monitorStatus: TextView
    private lateinit var locationStatus: TextView
    private var selectedAudioUri: Uri? = null
    private var receiver: BroadcastReceiver? = null
    private var locationManager: LocationManager? = null
    private var lastLocation: Location? = null
    private var gpsAnchor: GeoAnchor? = null
    private var routeActive = false
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var accelerationSensor: Sensor? = null
    private var rotationSensor: Sensor? = null
    private var headingRadians = 0.0
    private var indoorTracking = false
    private var locationPromptShown = false
    private var lastAcceleration = 9.8
    private var lastDetectedStepAt = 0L

    private data class GeoAnchor(
        val latitude: Double,
        val longitude: Double,
        val planX: Float,
        val planY: Float
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            locationStatus.text = "GPS: ${location.latitude.format(5)}, ${location.longitude.format(5)}"
            updatePlanPosition(location)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        registerSirenReceiver()
        indoorTracking = true
        startSensorTracking()
        window.decorView.postDelayed({ requestPermissionsOnLaunch() }, 500)
        if (savedInstanceState?.getBoolean(EXTRA_ALERT) == true || intent.getBooleanExtra(EXTRA_ALERT, false)) {
            showSirenDetected()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_ALERT, false) == true) showSirenDetected()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(26))
            setBackgroundColor(Color.rgb(244, 248, 248))
        }
        scroll.addView(content)

        content.addView(text("FAVQULODDA YORDAMCHI", 12, Color.rgb(0, 137, 123), true))
        content.addView(text("Evakuatsiya", 30, Color.rgb(16, 39, 56), true).apply {
            setPadding(0, dp(5), 0, dp(4))
        })
        content.addView(button("Orqaga") { finish() }, marginParams(top = 8))
        content.addView(text(
            "Bino rejasini yuklang. Ilova haqiqiy sirenani eshitganda telefonni ogohlantiradi.",
            14,
            Color.DKGRAY,
            false
        ))

        monitorStatus = cardText("Kuzatuv o‘chiq", Color.rgb(16, 39, 56))
        content.addView(monitorStatus, marginParams(top = 18))

        mapPreview = FloorPlanView(this).apply {
            setDefaultImage(R.drawable.evakuatsiya_xaritasi)
            onPlanTap = { x, y ->
                val location = lastLocation
                if (location != null) {
                    gpsAnchor = GeoAnchor(location.latitude, location.longitude, x, y)
                    locationStatus.text = "Kalibratsiya saqlandi: plan ${x.toInt()}%, ${y.toInt()}%"
                    toast("GPS nuqtasi xaritaga bog‘landi")
                } else {
                    locationStatus.text = "Plan nuqtasi belgilandi: ${x.toInt()}%, ${y.toInt()}%"
                    toast("Endi lokatsiyani oling va yana kalibratsiya qiling")
                }
                indoorTracking = true
                startSensorTracking()
            }
        }
        content.addView(mapPreview, marginParams(top = 14))

        content.addView(button("Evakuatsiya xaritasini tanlash") { pickFile("image/*", PICK_MAP) }, marginParams(top = 10))
        content.addView(button("Meni xaritada belgilash") { beginCalibration() }, marginParams(top = 10))
        content.addView(button("QR orqali joylashuvni aniqlash") { scanQr() }, marginParams(top = 10))
        content.addView(button("Chiqishga yo‘l ko‘rsat") {
            routeActive = true
            mapPreview.setRoute(IndoorRoutePlanner.calculateRoute(mapPreview.currentUser()))
            toast("Eng yaqin belgilangan chiqishgacha yo‘l chizildi")
        }, marginParams(top = 10))

        audioStatus = cardText("Sirena: ichki namunaviy audio", Color.DKGRAY)
        content.addView(audioStatus, marginParams(top = 10))
        content.addView(button("Sirena audiosini tanlash") { pickFile("audio/*", PICK_AUDIO) }, marginParams(top = 10))

        content.addView(button("Ruxsatlarni berish") { requestRequiredPermissions() }, marginParams(top = 18))
        content.addView(button("Sirena kuzatuvini yoqish") { startMonitoring() }, marginParams(top = 10))
        content.addView(button("Kuzatuvni to‘xtatish") { stopMonitoring() }, marginParams(top = 10))

        locationStatus = cardText("GPS hali olinmagan", Color.DKGRAY)
        content.addView(locationStatus, marginParams(top = 18))
        content.addView(button("Lokatsiyani olish") { startLocationUpdates() }, marginParams(top = 10))

        content.addView(text(
            "1-bosqich tayyor: xarita, audio va haqiqiy sirenani kuzatish. Keyingi bosqichda EXIT/yo‘lak grafigi va QR orqali bino ichidagi aniq joylashuv qo‘shiladi.",
            12,
            Color.GRAY,
            false
        ).apply { setPadding(0, dp(18), 0, 0) })

        setContentView(scroll)
    }

    private fun pickFile(type: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = type
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        startActivityForResult(intent, requestCode)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Ba'zi fayl menejerlari doimiy ruxsat bermaydi; joriy sessiya uchun baribir ishlaydi.
        }
        when (requestCode) {
            PICK_MAP -> {
                mapPreview.setImageUri(uri)
                toast("Xarita yuklandi")
            }
            PICK_AUDIO -> {
                selectedAudioUri = uri
                audioStatus.text = "Sirena: ${uri.lastPathSegment ?: "tanlangan audio"}"
                toast("Sirena audiosi yuklandi")
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(permissions.toTypedArray(), PERMISSIONS)
    }

    private fun requestPermissionsOnLaunch() {
        val needsPermission = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) ||
            (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        if (needsPermission) {
            requestRequiredPermissions()
        } else {
            startMonitoring()
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMonitoring()
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        }
    }

    private fun startMonitoring() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Avval mikrofon ruxsatini bering")
            requestRequiredPermissions()
            return
        }
        val intent = Intent(this, SirenMonitorService::class.java).apply {
            putExtra(SirenMonitorService.EXTRA_AUDIO_URI, selectedAudioUri?.toString() ?: defaultAudioUri().toString())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        monitorStatus.text = "Kuzatuv yoqilgan — telefon sirenani eshitmoqda"
        toast("Kuzatuv yoqildi. Bildirishnoma ko‘rinib turadi.")
    }

    private fun stopMonitoring() {
        startService(Intent(this, SirenMonitorService::class.java).setAction(SirenMonitorService.ACTION_STOP))
        monitorStatus.text = "Kuzatuv o‘chiq"
    }

    private fun defaultAudioUri(): Uri = Uri.parse("android.resource://$packageName/${R.raw.sirena}")

    private fun registerSirenReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == SirenMonitorService.EVENT_SIREN_DETECTED) showSirenDetected()
            }
        }
        val filter = IntentFilter(SirenMonitorService.EVENT_SIREN_DETECTED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun showSirenDetected() {
        monitorStatus.text = "⚠ SIRENA ANIQLANDI — EVAKUATSIYAGA TAYYORLANING"
        monitorStatus.setTextColor(Color.rgb(180, 35, 25))
        monitorStatus.setBackgroundColor(Color.rgb(255, 225, 222))
        toast("Sirenaga o‘xshash ovoz aniqlandi")
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Avval lokatsiya ruxsatini bering")
            requestRequiredPermissions()
            return
        }
        locationManager = getSystemService(LocationManager::class.java)
        val gpsEnabled = try {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        } catch (_: Exception) {
            false
        }
        val networkEnabled = try {
            locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        } catch (_: Exception) {
            false
        }
        if (!gpsEnabled && !networkEnabled) {
            askToEnableLocation()
            return
        }
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener, mainLooper)
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, locationListener, mainLooper)
            locationStatus.text = "GPS kuzatuvi yoqildi..."
        } catch (_: SecurityException) {
            toast("Lokatsiya ruxsati berilmadi")
        }
    }

    private fun askToEnableLocation() {
        if (locationPromptShown || isFinishing) return
        locationPromptShown = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("GPS yoqilmagan")
            .setMessage("Xaritada joylashuvingizni ko‘rsatish uchun telefon GPS lokatsiyasini yoqing.")
            .setNegativeButton("Keyinroq", null)
            .setPositiveButton("GPS sozlamalari") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .create()
        dialog.setOnDismissListener { locationPromptShown = false }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        }
    }

    private fun beginCalibration() {
        if (lastLocation == null) startLocationUpdates()
        mapPreview.setCalibrationMode(true)
        indoorTracking = true
        startSensorTracking()
        locationStatus.text = "Xaritada hozir turgan joyingizni bosing"
        toast("Xaritadagi haqiqiy turgan joyingizni bosing")
    }

    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue.orEmpty()
                val point = parseQrPoint(rawValue)
                if (point == null) {
                    toast("QR formati noto‘g‘ri. QR_FORMAT.md dagi formatdan foydalaning")
                    return@addOnSuccessListener
                }
                indoorTracking = true
                gpsAnchor = null
                mapPreview.setUserPercent(point.first, point.second)
                if (routeActive) mapPreview.setRoute(IndoorRoutePlanner.calculateRoute(mapPreview.currentUser()))
                locationStatus.text = "QR joylashuv: ${point.third} (${point.first.toInt()}%, ${point.second.toInt()}%)"
                toast("Joylashuv QR orqali aniqlandi")
                startSensorTracking()
            }
            .addOnCanceledListener { toast("QR skaneri bekor qilindi") }
            .addOnFailureListener { toast("QR skanerini ishga tushirib bo‘lmadi") }
    }

    private fun parseQrPoint(value: String): Triple<Float, Float, String>? {
        val x = Regex("(?:^|[;|&\\s])x\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val y = Regex("(?:^|[;|&\\s])y\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        if (x == null || y == null || x !in 0f..100f || y !in 0f..100f) return null
        val name = Regex("(?:^|[;|&])name\\s*[=:]\\s*([^;|&]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "belgilangan nuqta" }
        return Triple(x, y, name)
    }

    private fun updatePlanPosition(location: Location) {
        val anchor = gpsAnchor ?: return
        if (location.accuracy > 35f) return
        val latitudeMeters = (location.latitude - anchor.latitude) * 111_320.0
        val longitudeMeters = (location.longitude - anchor.longitude) * 111_320.0 *
            kotlin.math.cos(Math.toRadians(anchor.latitude))
        // Bu taxminiy tashqi/GPS mapping. Bino ichida 1:1 aniqlik QR yoki BLE anchor bilan qilinadi.
        val x = (anchor.planX + (longitudeMeters / 40.0 * 100.0)).toFloat().coerceIn(0f, 100f)
        val y = (anchor.planY - (latitudeMeters / 28.0 * 100.0)).toFloat().coerceIn(0f, 100f)
        mapPreview.setUserPercent(x, y)
        if (routeActive) mapPreview.setRoute(IndoorRoutePlanner.calculateRoute(mapPreview.currentUser()))
    }

    private fun startSensorTracking() {
        if (sensorManager == null) sensorManager = getSystemService(SensorManager::class.java)
        if (stepSensor == null) stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (accelerationSensor == null) accelerationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (rotationSensor == null) rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorManager?.let { manager ->
            rotationSensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            stepSensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            if (stepSensor == null) accelerationSensor?.let {
                manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    private fun moveOneStep() {
        if (!indoorTracking) return
        val current = mapPreview.currentUser()
        val stepMeters = 0.7
        val nextX = (current.x + (sin(headingRadians) * stepMeters / 40.0 * 100.0)).toFloat().coerceIn(0f, 100f)
        val nextY = (current.y - (cos(headingRadians) * stepMeters / 28.0 * 100.0)).toFloat().coerceIn(0f, 100f)
        mapPreview.setUserPercent(nextX, nextY)
        if (routeActive) mapPreview.setRoute(IndoorRoutePlanner.calculateRoute(mapPreview.currentUser()))
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotation = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                headingRadians = orientation[0].toDouble()
            }
            Sensor.TYPE_STEP_DETECTOR -> moveOneStep()
            Sensor.TYPE_ACCELEROMETER -> detectStepFromAcceleration(event)
        }
    }

    private fun detectStepFromAcceleration(event: SensorEvent) {
        // STEP_DETECTOR bo‘lmagan telefonlar uchun oddiy qadam zaxira algoritmi.
        if (stepSensor != null) return
        val magnitude = sqrt(
            (event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]).toDouble()
        )
        val now = System.currentTimeMillis()
        if (abs(magnitude - lastAcceleration) > 1.25 && now - lastDetectedStepAt > 350) {
            lastDetectedStepAt = now
            moveOneStep()
        }
        lastAcceleration = (lastAcceleration * 0.8) + (magnitude * 0.2)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        receiver?.let { unregisterReceiver(it) }
        locationManager?.removeUpdates(locationListener)
        sensorManager?.unregisterListener(this)
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(7, 143, 130))
        setOnClickListener { action() }
        minHeight = dp(48)
    }

    private fun cardText(value: String, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(color)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setBackgroundColor(Color.WHITE)
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun marginParams(top: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun Double.format(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)
}
