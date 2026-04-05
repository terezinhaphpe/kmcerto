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
          minimumPerKm = payload.optDouble("minimumPerKm", 1.5),
          sourceApp = payload.optString("sourceApp", "Desconhecido"),
          rawText = payload.optString("rawText", ""),
          distanceKm = if (payload.has("distanceKm")) payload.optDouble("distanceKm") else null
        )
      } catch (_: Throwable) {
        null
      }
    }
  }
}

object KmCertoOfferParser {
  fun fromJsonPayload(payload: String?, minimumPerKm: Double): OfferDecisionData? {
    if (payload.isNullOrBlank()) return null
    return try {
      val json = JSONObject(payload)
      val fare = json.optDouble("totalFare", 0.0)
      val distance = json.optDouble("totalDistance", 0.0)
      val minutes = json.optDouble("totalMinutes", 0.0)
      val source = json.optString("sourceApp", "Manual")
      val raw = json.optString("rawText", "")

      calculate(fare, distance, minutes, minimumPerKm, source, raw)
    } catch (_: Throwable) {
      null
    }
  }

  fun parseFromText(text: String, minimumPerKm: Double, sourceApp: String): OfferDecisionData? {
    if (text.isBlank()) return null

    val fare = findFare(text) ?: return null
    val distance = findDistance(text) ?: return null
    val minutes = findMinutes(text)

    return calculate(fare, distance, minutes, minimumPerKm, sourceApp, text)
  }

  private fun calculate(
    fare: Double,
    distance: Double,
    minutes: Double?,
    minimumPerKm: Double,
    sourceApp: String,
    rawText: String
  ): OfferDecisionData {
    val perKm = if (distance > 0) fare / distance else 0.0
    val perHour = if (minutes != null && minutes > 0) (fare / minutes) * 60 else null
    val perMinute = if (minutes != null && minutes > 0) fare / minutes else null

    val isAccepted = perKm >= minimumPerKm
    val status = if (isAccepted) "ACEITAR" else "RECUSAR"
    val statusColor = if (isAccepted) "#16A34A" else "#DC2626"

    return OfferDecisionData(
      totalFare = fare,
      totalFareLabel = "R$ ${String.format("%.2f", fare)}",
      status = status,
      statusColor = statusColor,
      perKm = perKm,
      perHour = perHour,
      perMinute = perMinute,
      minimumPerKm = minimumPerKm,
      sourceApp = sourceApp,
      rawText = rawText,
      distanceKm = distance
    )
  }

  private fun findFare(text: String): Double? {
    val regex = Regex("""(?:R\$|RS|S|R)\s*(\d+[\.,]\d{2})""", RegexOption.IGNORE_CASE)
    return regex.find(text)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()
  }

  private fun findDistance(text: String): Double? {
    val regex = Regex("""(\d+[\.,]\d+)\s*(?:km|k\s*m)""", RegexOption.IGNORE_CASE)
    return regex.find(text)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()
  }

  private fun findMinutes(text: String): Double? {
    val regex = Regex("""(\d+)\s*(?:min|m\s*i\s*n)""", RegexOption.IGNORE_CASE)
    return regex.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
  }
}

class KmCertoAccessibilityService : AccessibilityService() {
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    val info = AccessibilityServiceInfo().apply {
      eventTypes = AccessibilityEvent.TYPES_ALL_MASK
      feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
      flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
              AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
              AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
      notificationTimeout = 100
    }
    this.serviceInfo = info
    
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KmCerto::WakeLock")
    
    KmCertoLogger.init(this)
    KmCertoLogger.log("Serviço de Acessibilidade Conectado")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!KmCertoRuntime.isMonitoringEnabled(this)) return

    val packageName = event.packageName?.toString() ?: return
    if (!KmCertoRuntime.supportsPackage(packageName)) return

    wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)

    val allText = StringBuilder()
    
    // 1. Tentar ler de todas as janelas (Acessibilidade Profunda)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      windows.forEach { window ->
        collectTextRecursive(window.root, allText)
      }
    }

    // 2. Fallback para o root da janela ativa
    if (allText.isEmpty()) {
      collectTextRecursive(rootInActiveWindow, allText)
    }

    // 3. Fallback para o texto do evento
    if (allText.isEmpty()) {
      event.text.forEach { allText.append(it).append(" ") }
    }

    val text = allText.toString()
    if (text.isNotBlank()) {
      processText(text, packageName)
    }
    
    // 4. Se o texto via acessibilidade for suspeito de estar vazio (Uber/99), tentar OCR
    if (packageName.contains("uber") || packageName.contains("app99")) {
        KmCertoScreenCapture.captureAndProcess(this, packageName)
    }
  }

  private fun collectTextRecursive(node: AccessibilityNodeInfo?, out: StringBuilder) {
    if (node == null) return
    
    val text = node.text?.toString()
    val contentDesc = node.contentDescription?.toString()
    val viewId = node.viewIdResourceName
    
    if (!text.isNullOrBlank()) {
      out.append(text).append(" ")
      if (!viewId.isNullOrBlank()) KmCertoLogger.log("TEXT: $text (ID: $viewId)")
    }
    if (!contentDesc.isNullOrBlank()) {
      out.append(contentDesc).append(" ")
      if (!viewId.isNullOrBlank()) KmCertoLogger.log("DESC: $contentDesc (ID: $viewId)")
    }

    for (i in 0 until node.childCount) {
      collectTextRecursive(node.getChild(i), out)
    }
  }

  private fun processText(text: String, packageName: String) {
    val minimumPerKm = KmCertoRuntime.getMinimumPerKm(this)
    val sourceApp = KmCertoRuntime.sourceLabel(packageName)
    
    val offer = KmCertoOfferParser.parseFromText(text, minimumPerKm, sourceApp)
    if (offer != null) {
      KmCertoLogger.log("OFERTA DETECTADA ($sourceApp): R$ ${offer.totalFare} | ${offer.distanceKm} km | Status: ${offer.status}")
      KmCertoOverlayService.show(this, offer)
    }
  }

  override fun onInterrupt() {}

  override fun onDestroy() {
    super.onDestroy()
    wakeLock?.let { if (it.isHeld) it.release() }
  }

  companion object {
    fun isEnabled(context: Context): Boolean {
      val expectedComponentName = android.content.ComponentName(context, KmCertoAccessibilityService::class.java)
      val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
      val colonSplitter = TextUtils.SimpleStringSplitter(':')
      colonSplitter.setString(enabledServices)
      while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedComponentName.flattenToString(), ignoreCase = true)) return true
      }
      return false
    }
  }
}

object KmCertoScreenCapture {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = false

    fun hasPermission(): Boolean = mediaProjection != null

    fun setPermissionResult(resultCode: Int, data: Intent, context: Context) {
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)
    }

    fun captureAndProcess(context: Context, packageName: String) {
        if (isCapturing || mediaProjection == null) return
        isCapturing = true

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "KmCertoCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
                
                processBitmap(bitmap, context, packageName)
            }
            
            virtualDisplay?.release()
            imageReader?.close()
            isCapturing = false
        }, 500)
    }

    private fun processBitmap(bitmap: Bitmap, context: Context, packageName: String) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                if (text.isNotBlank()) {
                    KmCertoLogger.log("OCR_BRUTO ($packageName): ${text.replace("\n", " | ")}")
                    val minimumPerKm = KmCertoRuntime.getMinimumPerKm(context)
                    val sourceApp = KmCertoRuntime.sourceLabel(packageName)
                    val offer = KmCertoOfferParser.parseFromText(text, minimumPerKm, sourceApp)
                    if (offer != null) {
                        KmCertoOverlayService.show(context, offer)
                    }
                }
            }
            .addOnFailureListener { e ->
                KmCertoLogger.log("OCR_FALHA: ${e.message}")
            }
    }
}

class KmCertoPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpManager.createScreenCaptureIntent(), 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            KmCertoScreenCapture.setPermissionResult(resultCode, data, this)
        }
        finish()
    }
}

object KmCertoLogger {
  private var logFile: File? = null
  private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

  fun init(context: Context) {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    logFile = File(dir, "kmcerto_debug.txt")
    if (logFile?.exists() == true) {
        if (logFile!!.length() > 1024 * 1024) logFile?.delete() // Limpa se > 1MB
    }
  }

  fun log(message: String) {
    val time = sdf.format(Date())
    val line = "[$time] $message\n"
    Log.d("KmCerto", message)
    try {
      logFile?.appendText(line)
    } catch (_: Throwable) {}
  }

  fun getLogPath(): String = logFile?.absolutePath ?: "N/A"
}

object KmCertoOverlayService {
  private var overlayView: LinearLayout? = null

  fun show(context: Context, data: OfferDecisionData) {
    Handler(Looper.getMainLooper()).post {
      try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        stop(context)

        val view = LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(40, 30, 40, 30)
          val shape = GradientDrawable().apply {
            setColor(Color.parseColor("#1D2026"))
            cornerRadius = 40f
            setStroke(4, Color.parseColor("#2D313A"))
          }
          background = shape
        }

        // Header: App Source + Status
        val header = LinearLayout(context).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
        }
        
        val sourceTxt = TextView(context).apply {
          text = data.sourceApp
          setTextColor(Color.parseColor("#9CA3AF"))
          textSize = 12f
          typeface = Typeface.DEFAULT_BOLD
        }
        
        val statusTxt = TextView(context).apply {
          text = data.status
          setTextColor(Color.WHITE)
          textSize = 12f
          typeface = Typeface.DEFAULT_BOLD
          setPadding(20, 5, 20, 5)
          val bg = GradientDrawable().apply {
            setColor(Color.parseColor(data.statusColor))
            cornerRadius = 12f
          }
          background = bg
        }
        
        header.addView(sourceTxt, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(statusTxt)
        view.addView(header)

        // Fare
        val fareTxt = TextView(context).apply {
          text = data.totalFareLabel
          setTextColor(Color.WHITE)
          textSize = 32f
          typeface = Typeface.DEFAULT_BOLD
          setPadding(0, 10, 0, 10)
        }
        view.addView(fareTxt)

        // Metrics
        val metrics = LinearLayout(context).apply {
          orientation = LinearLayout.HORIZONTAL
          setPadding(0, 10, 0, 0)
        }
        
        val perKmTxt = TextView(context).apply {
          text = "R$ ${String.format("%.2f", data.perKm)}/km"
          setTextColor(Color.parseColor("#F5D400"))
          textSize = 16f
          typeface = Typeface.DEFAULT_BOLD
        }
        metrics.addView(perKmTxt)
        
        view.addView(metrics)

        val params = WindowManager.LayoutParams(
          WindowManager.LayoutParams.MATCH_PARENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
          PixelFormat.TRANSLUCENT
        ).apply {
          gravity = Gravity.TOP
          y = 100
          horizontalMargin = 0.05f
          width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
        }

        wm.addView(view, params)
        overlayView = view
        
        // Auto-hide after 15 seconds
        Handler(Looper.getMainLooper()).postDelayed({ stop(context) }, 15000)
      } catch (e: Exception) {
        KmCertoLogger.log("ERRO OVERLAY: ${e.message}")
      }
    }
  }

  fun stop(context: Context) {
    Handler(Looper.getMainLooper()).post {
      try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView?.let {
          wm.removeView(it)
          overlayView = null
        }
      } catch (_: Exception) {}
    }
  }
}
