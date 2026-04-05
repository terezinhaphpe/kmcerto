package expo.modules.kmcertonative

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

class KmCertoNativeModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("KmCertoNative")
    Events("KmCertoOverlayData")

    AsyncFunction("isOverlayPermissionGranted") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      Settings.canDrawOverlays(context)
    }

    AsyncFunction("isAccessibilityServiceEnabled") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      KmCertoAccessibilityService.isEnabled(context)
    }

    AsyncFunction("openOverlaySettings") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      try {
        val intent = Intent(
          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:${context.packageName}"),
        ).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
      } catch (_: Throwable) {
        false
      }
    }

    AsyncFunction("openAccessibilitySettings") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
      } catch (_: Throwable) {
        false
      }
    }

    AsyncFunction("isBatteryOptimizationIgnored") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        pm.isIgnoringBatteryOptimizations(context.packageName)
      } else {
        true
      }
    }

    AsyncFunction("openBatteryOptimizationSettings") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      try {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        } else {
          Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        }
        context.startActivity(intent)
        true
      } catch (_: Throwable) {
        false
      }
    }

    AsyncFunction("isMonitoringActive") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.isMonitoringEnabled(context)
    }

    AsyncFunction("hasScreenCapturePermission") {
      KmCertoScreenCapture.hasPermission()
    }

    AsyncFunction("requestScreenCapturePermission") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      try {
        val intent = Intent(context, KmCertoPermissionActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
      } catch (_: Throwable) { false }
    }

    AsyncFunction("startMonitoring") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.setMonitoringEnabled(context, true)
      true
    }

    AsyncFunction("stopMonitoring") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.setMonitoringEnabled(context, false)
      KmCertoOverlayService.stop(context)
      true
    }

    AsyncFunction("hideOverlay") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      KmCertoOverlayService.stop(context)
      true
    }

    AsyncFunction("setMinimumPerKm") { value: Double ->
      val context = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.setMinimumPerKm(context, value)
      true
    }

    AsyncFunction("getMinimumPerKm") {
      val context = appContext.reactContext ?: return@AsyncFunction KmCertoRuntime.DEFAULT_MINIMUM_PER_KM
      KmCertoRuntime.getMinimumPerKm(context)
    }

    AsyncFunction("getLogPath") {
      KmCertoLogger.getLogPath()
    }

    AsyncFunction("clearLog") {
      val context = appContext.reactContext ?: return@AsyncFunction false
      KmCertoLogger.init(context)
      true
    }

    AsyncFunction("showTestOverlay") { payload: String? ->
      val context = appContext.reactContext ?: return@AsyncFunction false
      val parsed = KmCertoOfferParser.fromJsonPayload(
        payload = payload,
        minimumPerKm = KmCertoRuntime.getMinimumPerKm(context),
      ) ?: return@AsyncFunction false

      this@KmCertoNativeModule.sendEvent("KmCertoOverlayData", mapOf(
        "totalFare" to parsed.totalFare,
        "totalFareLabel" to parsed.totalFareLabel,
        "status" to parsed.status,
        "statusColor" to parsed.statusColor,
        "perKm" to parsed.perKm,
        "perHour" to (parsed.perHour ?: 0.0),
        "perMinute" to (parsed.perMinute ?: 0.0),
        "minimumPerKm" to parsed.minimumPerKm,
        "sourceApp" to parsed.sourceApp,
        "rawText" to parsed.rawText
      ))
      KmCertoOverlayService.show(context, parsed)
      true
    }
  }
}

object KmCertoRuntime {
  const val DEFAULT_MINIMUM_PER_KM = 1.5
  private const val PREFERENCES_NAME = "kmcerto_native_preferences"
  private const val KEY_MINIMUM_PER_KM = "minimum_per_km"
  private const val KEY_MONITORING_ENABLED = "monitoring_enabled"

  val supportedPackages: Map<String, String> = mapOf(
    "br.com.ifood.driver.app" to "iFood",
    "com.app99.driver" to "99Food",
    "com.ubercab.driver" to "Uber",
  )

  fun setMinimumPerKm(context: Context, value: Double) {
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit()
      .putFloat(KEY_MINIMUM_PER_KM, value.toFloat())
      .apply()
  }

  fun getMinimumPerKm(context: Context): Double {
    val stored = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .getFloat(KEY_MINIMUM_PER_KM, DEFAULT_MINIMUM_PER_KM.toFloat())
    return stored.toDouble()
  }

  fun setMonitoringEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_MONITORING_ENABLED, enabled)
      .apply()
  }

  fun isMonitoringEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_MONITORING_ENABLED, true)
  }

  fun supportsPackage(packageName: String): Boolean {
    return supportedPackages.keys.any { key -> packageName == key || packageName.startsWith("$key:") }
  }

  fun sourceLabel(packageName: String): String {
    return supportedPackages.entries.firstOrNull { packageName == it.key || packageName.startsWith("${it.key}:") }
      ?.value
      ?: packageName.substringAfterLast('.')
  }
}

data class OfferDecisionData(
  val totalFare: Double,
  val totalFareLabel: String,
  val status: String,
  val statusColor: String,
  val perKm: Double,
  val perHour: Double?,
  val perMinute: Double?,
  val minimumPerKm: Double,
  val sourceApp: String,
  val rawText: String,
  val distanceKm: Double? = null,
) {
  fun toJson(): String {
    return JSONObject().apply {
      put("totalFare", totalFare)
      put("totalFareLabel", totalFareLabel)
      put("status", status)
      put("statusColor", statusColor)
      put("perKm", perKm)
      put("perHour", perHour)
      put("perMinute", perMinute)
      put("minimumPerKm", minimumPerKm)
      put("sourceApp", sourceApp)
      put("rawText", rawText)
      if (distanceKm != null) put("distanceKm", distanceKm)
    }.toString()
  }

  companion object {
    fun fromJson(json: String?): OfferDecisionData? {
      if (json.isNullOrBlank()) return null
      return try {
        val payload = JSONObject(json)
        OfferDecisionData(
          totalFare = payload.optDouble("totalFare", Double.NaN),
          totalFareLabel = payload.optString("totalFareLabel", ""),
          status = payload.optString("status", "RECUSAR"),
          statusColor = payload.optString("statusColor", "#DC2626"),
          perKm = payload.optDouble("perKm", Double.NaN),
          perHour = if (payload.has("perHour") && !payload.isNull("perHour")) payload.optDouble("perHour") else null,
          perMinute = if (payload.has("perMinute") && !payload.isNull("perMinute")) payload.optDouble("perMinute") else null,
          minimumPerKm = payload.optDouble("minimumPerKm", KmCertoRuntime.DEFAULT_MINIMUM_PER_KM),
          sourceApp = payload.optString("sourceApp", "KmCerto"),
          rawText = payload.optString("rawText", ""),
          distanceKm = if (payload.has("distanceKm") && !payload.isNull("distanceKm")) payload.optDouble("distanceKm") else null,
        )
      } catch (_: Throwable) {
        null
      }
    }
  }
}

object KmCertoOfferParser {
  private val locale = Locale("pt", "BR")
  private val currencyRegex = Regex("""R\$\s*([0-9]{1,4}(?:[.][0-9]{3})*(?:,[0-9]{2})|[0-9]+(?:[.,][0-9]{1,2})?)""")
  private val kmRegex = Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s?km\b""", RegexOption.IGNORE_CASE)
  private val minuteRegex = Regex("""(\d{1,3})\s?min(?:uto)?s?\b""", RegexOption.IGNORE_CASE)
  private val explicitTotalKmRegex = Regex("""(?:dist[âa]ncia\s+total|total)\s*(\d{1,3}(?:[.,]\d{1,2})?)\s?km""", RegexOption.IGNORE_CASE)
  private val explicitTotalMinutesRegex = Regex("""(?:tempo\s+total|total)\s*(\d{1,3})\s?min(?:uto)?s?\b""", RegexOption.IGNORE_CASE)

  fun parse(rawText: String, minimumPerKm: Double, sourcePackage: String): OfferDecisionData? {
    val normalizedText = rawText.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    if (normalizedText.isBlank()) return null

    val fare = currencyRegex.find(normalizedText)
      ?.groupValues
      ?.getOrNull(1)
      ?.let(::parsePtBrNumber)
      ?: return null

    val distance = explicitTotalKmRegex.find(normalizedText)
      ?.groupValues
      ?.getOrNull(1)
      ?.let(::parsePtBrNumber)
      ?: selectDistance(kmRegex.findAll(normalizedText).mapNotNull { it.groupValues.getOrNull(1)?.let(::parsePtBrNumber) }.toList())
      ?: return null

    val minutes = explicitTotalMinutesRegex.find(normalizedText)
      ?.groupValues
      ?.getOrNull(1)
      ?.toDoubleOrNull()
      ?: selectMinutes(minuteRegex.findAll(normalizedText).mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }.toList())

    if (fare <= 0 || distance <= 0) return null

    val perKm = fare / distance
    val perMinute = if (minutes != null && minutes > 0) fare / minutes else null
    val perHour = if (minutes != null && minutes > 0) fare / (minutes / 60.0) else null
    val shouldAccept = perKm + 0.0001 >= minimumPerKm

    return OfferDecisionData(
      totalFare = fare,
      totalFareLabel = formatCurrency(fare),
      status = if (shouldAccept) "ACEITAR" else "RECUSAR",
      statusColor = if (shouldAccept) "#16A34A" else "#DC2626",
      perKm = round2(perKm),
      perHour = perHour?.let(::round2),
      perMinute = perMinute?.let(::round2),
      minimumPerKm = round2(minimumPerKm),
      sourceApp = KmCertoRuntime.sourceLabel(sourcePackage),
      rawText = normalizedText,
      distanceKm = round2(distance)
    )
  }

  fun fromJsonPayload(payload: String?, minimumPerKm: Double): OfferDecisionData? {
    if (payload.isNullOrBlank()) return null
    return try {
      val json = JSONObject(payload)
      val totalFare = json.optDouble("totalFare", Double.NaN)
      val perKm = json.optDouble("perKm", Double.NaN)
      val perHour = if (json.has("perHour") && !json.isNull("perHour")) json.optDouble("perHour") else null
      val perMinute = if (json.has("perMinute") && !json.isNull("perMinute")) json.optDouble("perMinute") else null
      if (!totalFare.isFinite() || !perKm.isFinite()) return null

      OfferDecisionData(
        totalFare = totalFare,
        totalFareLabel = json.optString("totalFareLabel", formatCurrency(totalFare)),
        status = json.optString("status", if (perKm >= minimumPerKm) "ACEITAR" else "RECUSAR"),
        statusColor = json.optString("statusColor", if (perKm >= minimumPerKm) "#16A34A" else "#DC2626"),
        perKm = round2(perKm),
        perHour = perHour?.let(::round2),
        perMinute = perMinute?.let(::round2),
        minimumPerKm = json.optDouble("minimumPerKm", minimumPerKm),
        sourceApp = json.optString("sourceApp", "Teste manual"),
        rawText = json.optString("rawText", ""),
        distanceKm = if (json.has("distanceKm")) json.optDouble("distanceKm") else null
      )
    } catch (_: Throwable) {
      null
    }
  }

  private fun selectDistance(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    if (values.size == 1) return values.first()
    val firstTwo = values.take(2)
    val sum = firstTwo.sum()
    return when {
      sum in 0.5..100.0 -> round2(sum)
      else -> values.maxByOrNull { it.absoluteValue }
    }
  }

  private fun selectMinutes(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    if (values.size == 1) return values.first()
    val firstTwo = values.take(2)
    val sum = firstTwo.sum()
    return when {
      sum in 1.0..360.0 -> sum
      else -> values.maxByOrNull { it.absoluteValue }
    }
  }

  private fun parsePtBrNumber(raw: String): Double {
    val sanitized = raw.trim()
      .replace(".", "")
      .replace(',', '.')
    return sanitized.toDoubleOrNull() ?: Double.NaN
  }

  private fun formatCurrency(value: Double): String {
    return NumberFormat.getCurrencyInstance(locale).format(value)
  }

  private fun round2(value: Double): Double {
    return kotlin.math.round(value * 100.0) / 100.0
  }
}

class KmCertoAccessibilityService : AccessibilityService() {
  private var lastSignature: String? = null
  private var lastEmissionAt: Long = 0
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    KmCertoLogger.init(this)
    KmCertoLogger.log(this, "SERVICO_CONECTADO — monitorando: ${KmCertoRuntime.supportedPackages.keys}")
    
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KmCerto::AccessibilityWakeLock")
    wakeLock?.acquire(10 * 60 * 1000L)

    serviceInfo = AccessibilityServiceInfo().apply {
      eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
      feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
      flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
      notificationTimeout = 150
      packageNames = KmCertoRuntime.supportedPackages.keys.toTypedArray()
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val packageName = event?.packageName?.toString() ?: return
    if (!KmCertoRuntime.supportsPackage(packageName) || !KmCertoRuntime.isMonitoringEnabled(this)) return

    if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)

    val allWindows = windows ?: emptyList()
    val windowTexts = mutableListOf<String>()
    var foundEmptyAppWindow = false

    for (window in allWindows) {
      val winRoot = window.root
      if (winRoot != null) {
        val winPkg = winRoot.packageName?.toString() ?: ""
        if (winPkg == packageName) {
          val winText = collectWindowText(winRoot)
          if (winText.isNotBlank()) {
            windowTexts.add(winText)
          } else {
            foundEmptyAppWindow = true
          }
        }
      }
    }

    val text = windowTexts.joinToString(" | ")
    val minimumPerKm = KmCertoRuntime.getMinimumPerKm(this)
    val parsed = KmCertoOfferParser.parse(text, minimumPerKm, packageName)

    if (parsed != null) {
      KmCertoLogger.log(this, "PARSE_OK(accessibility) ${parsed.totalFareLabel} | ${parsed.distanceKm}km | ${parsed.status}")
      emitAndShow(parsed, packageName)
      return
    }

    if (foundEmptyAppWindow || (text.isBlank() && allWindows.isNotEmpty())) {
      KmCertoLogger.log(this, "OCR_TENTATIVA pkg=$packageName — janela vazia, iniciando captura de tela")
      if (KmCertoScreenCapture.hasPermission()) {
        KmCertoScreenCapture.captureAndOcr(this) { ocrText ->
          KmCertoLogger.log(this, "OCR_RESULTADO tamanho=${ocrText.length} | $ocrText")
          val ocrParsed = KmCertoOfferParser.parse(ocrText, minimumPerKm, packageName)
          if (ocrParsed != null) {
            KmCertoLogger.log(this, "PARSE_OK(ocr) ${ocrParsed.totalFareLabel} | ${ocrParsed.distanceKm}km | ${ocrParsed.status}")
            emitAndShow(ocrParsed, packageName)
          } else {
            KmCertoLogger.log(this, "OCR_PARSE_FALHOU — texto OCR não contém corrida válida")
          }
        }
      } else {
        KmCertoLogger.log(this, "OCR_SEM_PERMISSAO — peça permissão de gravação de tela no app")
      }
    } else if (text.isNotBlank() && (text.contains("R$") || text.contains("km", ignoreCase = true))) {
      KmCertoLogger.log(this, "PARSE_FALHOU — tinha R\$/km mas não parseou: $text")
    }
  }

  private fun emitAndShow(parsed: OfferDecisionData, packageName: String) {
    val signature = "${packageName}|${parsed.totalFareLabel}|${parsed.perKm}"
    val now = System.currentTimeMillis()
    if (signature == lastSignature && now - lastEmissionAt < 3500) return
    lastSignature = signature
    lastEmissionAt = now
    KmCertoOverlayService.show(this, parsed)
  }

  override fun onInterrupt() {
    wakeLock?.let { if (it.isHeld) it.release() }
  }

  override fun onDestroy() {
    wakeLock?.let { if (it.isHeld) it.release() }
    super.onDestroy()
  }

  private fun collectWindowText(root: AccessibilityNodeInfo): String {
    val parts = linkedSetOf<String>()
    fun visit(node: AccessibilityNodeInfo?) {
      if (node == null) return
      val text = node.text?.toString()?.trim().orEmpty()
      if (text.isNotBlank()) parts += text
      val contentDescription = node.contentDescription?.toString()?.trim().orEmpty()
      if (contentDescription.isNotBlank()) parts += contentDescription
      for (index in 0 until node.childCount) {
        visit(node.getChild(index))
      }
    }
    visit(root)
    return parts.joinToString(" | ")
  }

  companion object {
    fun isEnabled(context: Context): Boolean {
      val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      ) ?: return false
      val expected = "${context.packageName}/${KmCertoAccessibilityService::class.java.name}"
      return TextUtils.SimpleStringSplitter(':').run {
        setString(enabledServices)
        any { it.equals(expected, ignoreCase = true) }
      }
    }
  }
}

object KmCertoScreenCapture {
  private var mediaProjection: MediaProjection? = null
  private var resultCode: Int = Activity.RESULT_CANCELED
  private var resultData: Intent? = null
  private val handler = Handler(Looper.getMainLooper())
  private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

  fun hasPermission(): Boolean = resultData != null && resultCode == Activity.RESULT_OK

  fun setPermission(code: Int, data: Intent?) {
    resultCode = code
    resultData = data
  }

  fun captureAndOcr(context: Context, callback: (String) -> Unit) {
    val data = resultData ?: run { callback(""); return }
    if (resultCode != Activity.RESULT_OK) { callback(""); return }

    try {
      val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      val projection = mgr.getMediaProjection(resultCode, data) ?: run { callback(""); return }
      mediaProjection = projection

      val metrics = context.resources.displayMetrics
      val width = metrics.widthPixels
      val height = metrics.heightPixels
      val density = metrics.densityDpi

      val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

      val virtualDisplay = projection.createVirtualDisplay(
        "KmCertoCapture",
        width, height, density,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        imageReader.surface, null, handler
      ) ?: run {
        projection.stop()
        mediaProjection = null
        callback("")
        return
      }

      handler.postDelayed({
        try {
          val image = imageReader.acquireLatestImage()
          if (image != null) {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
              width + rowPadding / pixelStride, height,
              Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height / 2)
            bitmap.recycle()

            val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
            recognizer.process(inputImage)
              .addOnSuccessListener { result ->
                croppedBitmap.recycle()
                virtualDisplay.release()
                projection.stop()
                mediaProjection = null
                callback(result.text)
              }
              .addOnFailureListener {
                croppedBitmap.recycle()
                virtualDisplay.release()
                projection.stop()
                mediaProjection = null
                callback("")
              }
          } else {
            virtualDisplay.release()
            projection.stop()
            mediaProjection = null
            callback("")
          }
        } catch (e: Throwable) {
          virtualDisplay.release()
          projection.stop()
          mediaProjection = null
          callback("")
        }
      }, 300)
    } catch (e: Throwable) {
      callback("")
    }
  }
}

class KmCertoPermissionActivity : Activity() {
  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_CODE) {
      KmCertoScreenCapture.setPermission(resultCode, data)
    }
    finish()
  }

  companion object {
    private const val REQUEST_CODE = 1001
  }
}

object KmCertoLogger {
  private const val TAG = "KmCerto"
  private const val LOG_FILE = "kmcerto_debug.txt"
  private val dateFmt = SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
  private var logFile: File? = null
  private var lineCount = 0
  private const val MAX_LINES = 2000

  fun init(context: Context) {
    try {
      val dir = android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS
      )
      dir.mkdirs()
      logFile = File(dir, LOG_FILE)
      logFile?.writeText("=== KmCerto Debug Log iniciado em ${Date()} ===\n")
      lineCount = 0
    } catch (_: Throwable) {}
  }

  fun log(context: Context, message: String) {
    val line = "[${dateFmt.format(Date())}] $message"
    Log.d(TAG, line)
    try {
      if (logFile == null) init(context)
      if (lineCount >= MAX_LINES) {
        logFile?.writeText("=== Log reiniciado após $MAX_LINES linhas em ${Date()} ===\n")
        lineCount = 0
      }
      logFile?.appendText("$line\n")
      lineCount++
    } catch (_: Throwable) {}
  }

  fun getLogPath(): String = logFile?.absolutePath ?: "não inicializado"
}

class KmCertoOverlayService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var windowManager: WindowManager? = null
  private var overlayView: LinearLayout? = null
  private val dismissRunnable = Runnable {
    hideOverlayInternal()
    stopForegroundCompat()
    stopSelf()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_HIDE -> {
        hideOverlayInternal()
        stopForegroundCompat()
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_SHOW -> {
        if (!Settings.canDrawOverlays(this)) {
          stopSelf()
          return START_NOT_STICKY
        }
        val payload = OfferDecisionData.fromJson(intent.getStringExtra(EXTRA_PAYLOAD)) ?: return START_NOT_STICKY
        startForegroundInternal()
        showOverlayInternal(payload)
        return START_NOT_STICKY
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    handler.removeCallbacks(dismissRunnable)
    hideOverlayInternal()
    super.onDestroy()
  }

  private fun startForegroundInternal() {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL_ID, "KmCerto Overlay", NotificationManager.IMPORTANCE_LOW)
      channel.description = "Canal do overlay automático do KmCerto."
      manager.createNotificationChannel(channel)
    }
    val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("KmCerto ativo")
        .setContentText("Analisando ofertas e exibindo o overlay temporário.")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
        .setContentTitle("KmCerto ativo")
        .setContentText("Analisando ofertas e exibindo o overlay temporário.")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()
    }
    startForeground(NOTIFICATION_ID, notification)
  }

  private fun stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
  }

  private fun showOverlayInternal(data: OfferDecisionData) {
    hideOverlayInternal()
    val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager = manager
    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(20), dp(18), dp(20), dp(18))
      background = GradientDrawable().apply {
        setColor(Color.parseColor("#CC000000"))
        cornerRadius = dp(24).toFloat()
      }
    }
    val fareText = TextView(this).apply {
      text = data.totalFareLabel
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
      setTypeface(typeface, Typeface.BOLD)
      gravity = Gravity.CENTER_HORIZONTAL
    }
    val statusText = TextView(this).apply {
      text = data.status
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      setTypeface(typeface, Typeface.BOLD)
      gravity = Gravity.CENTER
      setPadding(dp(14), dp(8), dp(14), dp(8))
      background = GradientDrawable().apply {
        setColor(Color.parseColor(data.statusColor))
        cornerRadius = dp(999).toFloat()
      }
    }
    val sourceText = TextView(this).apply {
      text = data.sourceApp
      setTextColor(Color.parseColor("#CFCFD4"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      gravity = Gravity.CENTER_HORIZONTAL
    }
    val metricRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      val gap = dp(10)
      showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
      dividerDrawable = GradientDrawable().apply {
        setSize(gap, 1)
        setColor(Color.TRANSPARENT)
      }
    }
    metricRow.addView(createMetricText("R$/km", data.perKm))
    data.perHour?.let { metricRow.addView(createMetricText("R$/hr", it)) }
    data.perMinute?.let { metricRow.addView(createMetricText("R$/min", it)) }
    container.addView(statusText)
    container.addView(spaceView(dp(10)))
    container.addView(fareText)
    container.addView(spaceView(dp(6)))
    container.addView(sourceText)
    container.addView(spaceView(dp(14)))
    container.addView(metricRow)
    val layoutParams = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
      },
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      x = 0
      y = dp(72)
      width = WindowManager.LayoutParams.MATCH_PARENT
      horizontalMargin = 0f
    }
    try {
      manager.addView(container, layoutParams)
      overlayView = container
      handler.removeCallbacks(dismissRunnable)
      handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    } catch (_: Throwable) {
      hideOverlayInternal()
      stopForegroundCompat()
      stopSelf()
    }
  }

  private fun hideOverlayInternal() {
    handler.removeCallbacks(dismissRunnable)
    overlayView?.let { view ->
      try { windowManager?.removeView(view) } catch (_: Throwable) { }
    }
    overlayView = null
  }

  private fun createMetricText(label: String, value: Double): LinearLayout {
    return LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      addView(TextView(this@KmCertoOverlayService).apply {
        text = KmCertoFormatters.decimal(value)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_HORIZONTAL
      })
      addView(TextView(this@KmCertoOverlayService).apply {
        text = label
        setTextColor(Color.parseColor("#CFCFD4"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER_HORIZONTAL
      })
    }
  }

  private fun spaceView(height: Int): TextView {
    return TextView(this).apply { minHeight = height }
  }

  private fun dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
  }

  companion object {
    private const val ACTION_SHOW = "expo.modules.kmcertonative.action.SHOW_OVERLAY"
    private const val ACTION_HIDE = "expo.modules.kmcertonative.action.HIDE_OVERLAY"
    private const val EXTRA_PAYLOAD = "expo.modules.kmcertonative.extra.PAYLOAD"
    private const val AUTO_DISMISS_MS = 8_000L
    private const val CHANNEL_ID = "kmcerto_overlay"
    private const val NOTIFICATION_ID = 7071

    fun show(context: Context, payload: OfferDecisionData) {
      val intent = Intent(context, KmCertoOverlayService::class.java).apply {
        action = ACTION_SHOW
        putExtra(EXTRA_PAYLOAD, payload.toJson())
      }
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (_: Throwable) { }
    }

    fun stop(context: Context) {
      val intent = Intent(context, KmCertoOverlayService::class.java).apply {
        action = ACTION_HIDE
      }
      try { context.startService(intent) } catch (_: Throwable) { }
    }
  }
}

object KmCertoFormatters {
  private val locale = Locale("pt", "BR")

  fun decimal(value: Double): String {
    return String.format(locale, "%.2f", value)
  }
}
