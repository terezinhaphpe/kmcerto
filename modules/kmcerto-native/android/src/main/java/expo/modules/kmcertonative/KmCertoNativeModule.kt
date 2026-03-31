package expo.modules.kmcertonative

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.Toast
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class KmCertoNativeModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("KmCertoNative")
    Events("KmCertoOverlayData")

    AsyncFunction("isOverlayPermissionGranted") {
      val ctx = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.bindContext(ctx)
      Settings.canDrawOverlays(ctx)
    }

    AsyncFunction("isAccessibilityServiceEnabled") {
      val ctx = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.bindContext(ctx)
      KmCertoAccessibilityService.isEnabled(ctx)
    }

    AsyncFunction("openOverlaySettings") {
      val ctx = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.bindContext(ctx)
      try {
        val intent = Intent(
          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:${ctx.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ctx.startActivity(intent)
        true
      } catch (_: Throwable) { false }
    }

    AsyncFunction("openAccessibilitySettings") {
      val ctx = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.bindContext(ctx)
      try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
      } catch (_: Throwable) { false }
    }

    AsyncFunction("startMonitoring") {
      val ctx = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.bindContext(ctx)
      KmCertoRuntime.setMonitoringEnabled(ctx, true)
      true
    }

    AsyncFunction("stopMonitoring") {
      val ctx = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.bindContext(ctx)
      KmCertoRuntime.setMonitoringEnabled(ctx, false)
      KmCertoOverlayService.stop(ctx)
      true
    }

    AsyncFunction("hideOverlay") {
      val ctx = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.bindContext(ctx)
      KmCertoOverlayService.stop(ctx)
      true
    }

    AsyncFunction("setMinimumPerKm") { value: Double ->
      val ctx = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.bindContext(ctx)
      KmCertoRuntime.setMinimumPerKm(ctx, value)
      true
    }

    AsyncFunction("getMinimumPerKm") {
      val ctx = appContext.reactContext ?: return@AsyncFunction KmCertoRuntime.DEFAULT_MINIMUM_PER_KM
      KmCertoRuntime.bindContext(ctx)
      KmCertoRuntime.getMinimumPerKm(ctx)
    }

    AsyncFunction("showTestOverlay") { payload: String? ->
      val ctx = appContext.reactContext ?: return@AsyncFunction false
      KmCertoRuntime.bindContext(ctx)
      val parsed = KmCertoOfferParser.fromJsonPayload(
        payload = payload,
        minimumPerKm = KmCertoRuntime.getMinimumPerKm(ctx),
      ) ?: return@AsyncFunction false

      sendEvent("KmCertoOverlayData", mapOf(
        "totalFare" to parsed.totalFare,
        "totalFareLabel" to parsed.totalFareLabel,
        "status" to parsed.status,
        "statusColor" to parsed.statusColor,
        "perKm" to parsed.perKm,
        "perHour" to (parsed.perHour as Any?),
        "perMinute" to (parsed.perMinute as Any?),
        "minimumPerKm" to parsed.minimumPerKm,
        "sourceApp" to parsed.sourceApp,
        "rawText" to parsed.rawText,
      ))
      KmCertoOverlayService.show(ctx, parsed)
      true
    }
  }
}

object KmCertoRuntime {
  const val DEFAULT_MINIMUM_PER_KM = 1.5
  private const val PREFERENCES_NAME = "kmcerto_native_preferences"
  private const val KEY_MINIMUM_PER_KM = "minimum_per_km"
  private const val KEY_MONITORING_ENABLED = "monitoring_enabled"

  private var appContext: Context? = null

  val supportedPackages: Map<String, String> = mapOf(
    "br.com.ifood.driver.app" to "iFood",
    "com.app99.driver" to "99Food",
    "com.ubercab.driver" to "Uber",
  )

  fun bindContext(context: Context) {
    appContext = context.applicationContext
  }

  fun setMinimumPerKm(context: Context, value: Double) {
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit().putFloat(KEY_MINIMUM_PER_KM, value.toFloat()).apply()
  }

  fun getMinimumPerKm(context: Context): Double {
    return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .getFloat(KEY_MINIMUM_PER_KM, DEFAULT_MINIMUM_PER_KM.toFloat()).toDouble()
  }

  fun setMonitoringEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .edit().putBoolean(KEY_MONITORING_ENABLED, enabled).apply()
  }

  fun isMonitoringEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_MONITORING_ENABLED, true)
  }

  fun supportsPackage(packageName: String): Boolean {
    return supportedPackages.keys.any { key ->
      packageName == key || packageName.startsWith("$key:")
    }
  }

  fun sourceLabel(packageName: String): String {
    return supportedPackages.entries
      .firstOrNull { packageName == it.key || packageName.startsWith("${it.key}:") }
      ?.value ?: packageName.substringAfterLast('.')
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
  val totalMinutes: Double?,
  val totalDistance: Double,
  val minimumPerKm: Double,
  val sourceApp: String,
  val rawText: String,
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
      put("totalMinutes", totalMinutes)
      put("totalDistance", totalDistance)
      put("minimumPerKm", minimumPerKm)
      put("sourceApp", sourceApp)
      put("rawText", rawText)
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
          totalMinutes = if (payload.has("totalMinutes") && !payload.isNull("totalMinutes")) payload.optDouble("totalMinutes") else null,
          totalDistance = payload.optDouble("totalDistance", 0.0),
          minimumPerKm = payload.optDouble("minimumPerKm", KmCertoRuntime.DEFAULT_MINIMUM_PER_KM),
          sourceApp = payload.optString("sourceApp", "KmCerto"),
          rawText = payload.optString("rawText", ""),
        )
      } catch (_: Throwable) { null }
    }
  }
}

object KmCertoOfferParser {
  private const val TAG = "KmCerto"
  private val locale = Locale("pt", "BR")

  // ── Regex para capturar valor R$ ──────────────────────────────────────
  private val currencyRegex = Regex(
    """R\$[\s\u00a0\u202f\u2009]*(\d{1,4}(?:[.,]\d{3})*[.,]\d{2}|\d{1,4}[.,]\d{1,2}|\d{2,4})"""
  )

  // ── Regex para capturar km ────────────────────────────────────────────
  private val kmRegex = Regex(
    """(\d{1,3}[.,]\d{1,2}|\d{1,3})\s*km\b""",
    RegexOption.IGNORE_CASE
  )

  // ── Regex para capturar minutos ───────────────────────────────────────
  private val minuteRegex = Regex("""(\d{1,3})\s*min""", RegexOption.IGNORE_CASE)

  // ── Palavras que indicam tela NÃO é oferta (mapa, recompensas, etc) ──
  private val nonOfferKeywords = listOf(
    "buscando", "procurando viagens", "você está online",
    "você está offline", "horas insuficientes", "recompensa",
    "garantidos", "missão", "avance para a categoria",
    "tendências de ganhos"
  )

  // ── Palavras-chave que indicam tela de OFERTA de corrida ──────────────
  private val offerKeywords = listOf(
    "aceitar", "selecionar", "recusar", "viagem de", "de distância",
    "distancia", "corrida", "entrega", "retirada", "coleta",
    "destino", "origem", "rota", "parada"
  )

  fun parse(context: Context?, rawText: String, minimumPerKm: Double, sourcePackage: String): OfferDecisionData? {
    val normalizedText = rawText.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    if (normalizedText.isBlank()) return null

    val lowerText = normalizedText.lowercase(locale)

    Log.d(TAG, "=== PARSE START ===")
    Log.d(TAG, "Package: $sourcePackage")
    Log.d(TAG, "Text (500): ${normalizedText.take(500)}")

    // ── 0. Filtro inteligente: verificar se é tela de oferta ────────────
    val hasKm = kmRegex.containsMatchIn(normalizedText)
    val hasFare = currencyRegex.containsMatchIn(normalizedText)

    // Se não tem km, não é uma oferta de corrida — sair SILENCIOSAMENTE
    if (!hasKm) {
      Log.d(TAG, "SKIP: No km found in text (not a ride offer screen)")
      return null
    }

    // Se não tem R$, também não é oferta — sair silenciosamente
    if (!hasFare) {
      Log.d(TAG, "SKIP: No fare found in text")
      return null
    }

    // Se tem palavras de não-oferta e NÃO tem palavras de oferta
    val hasOfferKeyword = offerKeywords.any { lowerText.contains(it) }
    val hasNonOfferKeyword = nonOfferKeywords.any { lowerText.contains(it) }

    if (hasNonOfferKeyword && !hasOfferKeyword) {
      // Verificação extra: se o valor R$ parece ser de recompensa (>= R$100), ignorar
      val allFares = currencyRegex.findAll(normalizedText)
        .mapNotNull { it.groupValues.getOrNull(1)?.let(::parsePtBrNumber) }
        .filter { it > 0 && it.isFinite() }
        .toList()
      val maxFare = allFares.maxOrNull() ?: 0.0
      if (maxFare >= 100.0) {
        Log.d(TAG, "SKIP: Non-offer screen with high values (likely rewards: R$$maxFare)")
        return null
      }
    }

    // ── 1. Extrair valor R$ ─────────────────────────────────────────────
    val fareMatch = currencyRegex.find(normalizedText)
    var fare = fareMatch?.groupValues?.getOrNull(1)?.let(::parsePtBrNumber)
    Log.d(TAG, "Fare match: '${fareMatch?.value}' -> $fare")

    // Fallback: R$ e número em nós separados pelo " | "
    if (fare == null || fare <= 0 || !fare.isFinite()) {
      val parts = normalizedText.split("|").map { it.trim() }
      val rIdx = parts.indexOfFirst { it.contains("R$") }
      if (rIdx >= 0 && rIdx + 1 < parts.size) {
        val candidate = parts[rIdx + 1].trim()
        fare = parsePtBrNumber(candidate)
        Log.d(TAG, "Fare fallback parts[$rIdx+1]='$candidate' -> $fare")
      }
    }

    if (fare == null || fare <= 0 || !fare.isFinite()) {
      Log.d(TAG, "FAIL: No valid fare found")
      return null
    }

    // Filtro: corridas normalmente são entre R$2 e R$200
    if (fare < 2.0 || fare > 200.0) {
      Log.d(TAG, "SKIP: Fare R$$fare out of reasonable range (2-200)")
      return null
    }

    // ── 2. Extrair km e SOMAR TODOS ─────────────────────────────────────
    val distances = kmRegex.findAll(normalizedText)
      .mapNotNull { it.groupValues.getOrNull(1)?.let(::parsePtBrNumber) }
      .filter { it > 0 && it.isFinite() }
      .toList()

    Log.d(TAG, "KM values: $distances")
    val totalDistance = distances.sum()

    if (totalDistance <= 0) {
      Log.d(TAG, "FAIL: No distance found")
      return null
    }

    // ── 3. Extrair minutos e SOMAR TODOS ────────────────────────────────
    val totalMinutes = minuteRegex.findAll(normalizedText)
      .mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }
      .filter { it > 0 }
      .toList()
      .takeIf { it.isNotEmpty() }
      ?.sum()

    Log.d(TAG, "Total dist: $totalDistance km | Total min: $totalMinutes")

    // ── 4. Calcular R$/km, R$/hr, R$/min ────────────────────────────────
    val perKm = fare / totalDistance
    val perMinute = if (totalMinutes != null && totalMinutes > 0) fare / totalMinutes else null
    val perHour = if (totalMinutes != null && totalMinutes > 0) fare / (totalMinutes / 60.0) else null
    val shouldAccept = perKm + 0.0001 >= minimumPerKm

    Log.d(TAG, "RESULT: fare=$fare dist=$totalDistance perKm=$perKm accept=$shouldAccept")

    return OfferDecisionData(
      totalFare = fare,
      totalFareLabel = formatCurrency(fare),
      status = if (shouldAccept) "Aceitar" else "Recusar",
      statusColor = if (shouldAccept) "#16A34A" else "#DC2626",
      perKm = round2(perKm),
      perHour = perHour?.let(::round2),
      perMinute = perMinute?.let(::round2),
      totalMinutes = totalMinutes,
      totalDistance = round2(totalDistance),
      minimumPerKm = round2(minimumPerKm),
      sourceApp = KmCertoRuntime.sourceLabel(sourcePackage),
      rawText = normalizedText.take(300),
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
      val totalMinutes = if (json.has("totalMinutes") && !json.isNull("totalMinutes")) json.optDouble("totalMinutes") else null
      val totalDistance = json.optDouble("totalDistance", 0.0)
      if (!totalFare.isFinite() || !perKm.isFinite()) return null

      OfferDecisionData(
        totalFare = totalFare,
        totalFareLabel = json.optString("totalFareLabel", formatCurrency(totalFare)),
        status = json.optString("status", if (perKm >= minimumPerKm) "Aceitar" else "Recusar"),
        statusColor = json.optString("statusColor", if (perKm >= minimumPerKm) "#16A34A" else "#DC2626"),
        perKm = round2(perKm),
        perHour = perHour?.let(::round2),
        perMinute = perMinute?.let(::round2),
        totalMinutes = totalMinutes,
        totalDistance = totalDistance,
        minimumPerKm = json.optDouble("minimumPerKm", minimumPerKm),
        sourceApp = json.optString("sourceApp", "Teste manual"),
        rawText = json.optString("rawText", ""),
      )
    } catch (_: Throwable) { null }
  }

  // "6,70" -> 6.70 | "7.5" -> 7.5 | "1.234,56" -> 1234.56
  private fun parsePtBrNumber(raw: String): Double {
    val s = raw.trim()
    if (s.isEmpty()) return Double.NaN
    return if (s.contains(",")) {
      s.replace(".", "").replace(",", ".").toDoubleOrNull() ?: Double.NaN
    } else {
      s.toDoubleOrNull() ?: Double.NaN
    }
  }

  // FIX: formatCurrency retorna "R$ 6,70" — usar APENAS para exibição
  fun formatCurrency(value: Double): String =
    NumberFormat.getCurrencyInstance(locale).format(value)

  private fun round2(value: Double): Double =
    kotlin.math.round(value * 100.0) / 100.0
}

class KmCertoAccessibilityService : AccessibilityService() {
  private val TAG = "KmCerto"
  private var lastSignature: String? = null
  private var lastEmissionAt: Long = 0
  private var lastToastAt: Long = 0
  private var wakeLock: PowerManager.WakeLock? = null

  // =====================================================================
  // Foreground notification para manter o serviço VIVO
  // =====================================================================
  private fun startForegroundKeepAlive() {
    try {
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
          KEEP_ALIVE_CHANNEL_ID,
          "KmCerto Monitoramento",
          NotificationManager.IMPORTANCE_LOW
        ).apply {
          description = "Mantém o KmCerto ativo para monitorar corridas."
          setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
      }
      val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(this, KEEP_ALIVE_CHANNEL_ID)
      } else {
        @Suppress("DEPRECATION") Notification.Builder(this)
      }.setContentTitle("KmCerto monitorando")
        .setContentText("Analisando ofertas de corrida automaticamente.")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()
      startForeground(KEEP_ALIVE_NOTIFICATION_ID, notification)
      Log.d(TAG, "Foreground keep-alive notification STARTED")
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to start foreground: ${e.message}")
    }
  }

  // =====================================================================
  // WakeLock parcial para evitar que a CPU durma
  // =====================================================================
  private fun acquireWakeLock() {
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "KmCerto::AccessibilityWakeLock"
      ).apply {
        acquire()
      }
      Log.d(TAG, "WakeLock ACQUIRED")
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
    }
  }

  private fun releaseWakeLock() {
    try {
      wakeLock?.let {
        if (it.isHeld) it.release()
      }
      wakeLock = null
      Log.d(TAG, "WakeLock RELEASED")
    } catch (_: Throwable) { }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    Log.d(TAG, ">>> AccessibilityService CONNECTED <<<")
    serviceInfo = AccessibilityServiceInfo().apply {
      eventTypes = AccessibilityEvent.TYPES_ALL_MASK
      feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
      flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
      notificationTimeout = 100
      packageNames = arrayOf(
        "br.com.ifood.driver.app",
        "com.app99.driver",
        "com.ubercab.driver",
      )
    }

    // Iniciar foreground + wakelock para manter vivo
    startForegroundKeepAlive()
    acquireWakeLock()

    // IMPORTANTE: Garantir que monitoring está habilitado ao conectar o serviço
    // Isso resolve o problema de quando o app React Native não chamou startMonitoring
    KmCertoRuntime.setMonitoringEnabled(this, true)

    Handler(Looper.getMainLooper()).post {
      Toast.makeText(this, "KmCerto: Serviço ATIVO e protegido!", Toast.LENGTH_LONG).show()
    }
  }

  override fun onDestroy() {
    releaseWakeLock()
    super.onDestroy()
    Log.d(TAG, ">>> AccessibilityService DESTROYED <<<")
    Handler(Looper.getMainLooper()).post {
      Toast.makeText(applicationContext, "KmCerto: Serviço foi encerrado!", Toast.LENGTH_LONG).show()
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    val packageName = event.packageName?.toString() ?: return
    if (!KmCertoRuntime.supportsPackage(packageName)) return
    if (!KmCertoRuntime.isMonitoringEnabled(this)) return

    Log.d(TAG, "EVENT from $packageName type=${event.eventType}")

    // =====================================================================
    // COLETA DE TEXTO: root + event.text + event.source
    // =====================================================================
    val allParts = mutableListOf<String>()

    // Estratégia 1: rootInActiveWindow (árvore completa)
    val root = try { rootInActiveWindow } catch (_: Throwable) { null }
    if (root != null) {
      val rootText = collectAllText(root)
      if (rootText.isNotBlank()) allParts.add(rootText)
      try { root.recycle() } catch (_: Throwable) { }
    }

    // Estratégia 2: texto do evento
    event.text?.forEach { t ->
      t?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { allParts.add(it) }
    }
    event.contentDescription?.toString()?.trim()
      ?.takeIf { it.isNotBlank() }?.let { allParts.add(it) }

    // Estratégia 3: source do evento (sub-árvore)
    val source = try { event.source } catch (_: Throwable) { null }
    if (source != null) {
      val sourceText = collectAllText(source)
      if (sourceText.isNotBlank() && sourceText !in allParts) allParts.add(sourceText)
      try { source.recycle() } catch (_: Throwable) { }
    }

    val text = allParts.joinToString(" | ")

    if (text.isBlank()) {
      Log.d(TAG, "EMPTY text from $packageName")
      return
    }

    Log.d(TAG, "CAPTURED (${text.length} chars): ${text.take(300)}")

    // Toast de debug a cada 8 segundos
    val now = System.currentTimeMillis()
    if (now - lastToastAt > 8000) {
      lastToastAt = now
      val preview = text.take(100).replace("\n", " ")
      Handler(Looper.getMainLooper()).post {
        Toast.makeText(this, "KmCerto [$packageName]: $preview", Toast.LENGTH_SHORT).show()
      }
    }

    val minimumPerKm = KmCertoRuntime.getMinimumPerKm(this)
    val parsed = KmCertoOfferParser.parse(
      context = this,
      rawText = text,
      minimumPerKm = minimumPerKm,
      sourcePackage = packageName,
    ) ?: return

    val signature = "${packageName}|${parsed.totalFareLabel}|${parsed.perKm}"
    if (signature == lastSignature && now - lastEmissionAt < 3500) return
    lastSignature = signature
    lastEmissionAt = now

    Log.d(TAG, ">>> OVERLAY: ${parsed.totalFareLabel} ${parsed.perKm}/km ${parsed.status} <<<")
    KmCertoOverlayService.show(this, parsed)
  }

  override fun onInterrupt() {
    Log.d(TAG, "AccessibilityService INTERRUPTED")
  }

  private fun collectAllText(root: AccessibilityNodeInfo): String {
    val parts = mutableListOf<String>()
    val visited = HashSet<Int>()

    fun visit(node: AccessibilityNodeInfo?) {
      if (node == null) return
      val hash = System.identityHashCode(node)
      if (hash in visited) return
      visited.add(hash)

      node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
      node.contentDescription?.toString()?.trim()
        ?.takeIf { it.isNotBlank() && it != node.text?.toString()?.trim() }
        ?.let { parts.add(it) }

      for (i in 0 until node.childCount) {
        try {
          val child = node.getChild(i)
          if (child != null) {
            visit(child)
            try { child.recycle() } catch (_: Throwable) { }
          }
        } catch (_: Throwable) { }
      }
    }

    visit(root)
    return parts.joinToString(" | ")
  }

  companion object {
    private const val KEEP_ALIVE_CHANNEL_ID = "kmcerto_keep_alive"
    private const val KEEP_ALIVE_NOTIFICATION_ID = 7072

    fun isEnabled(context: Context): Boolean {
      val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      ) ?: return false
      val expected = "${context.packageName}/${KmCertoAccessibilityService::class.java.name}"
      return TextUtils.SimpleStringSplitter(':').run {
        setString(enabled)
        any { it.equals(expected, ignoreCase = true) }
      }
    }
  }
}

// =====================================================================
// OVERLAY SERVICE — Layout no estilo do app "Rota" (referência)
// =====================================================================
// Layout:
//   ┌─────────────────────────────────────────┐
//   │  Aceitar: Valor da viagem               │  <- status + contexto
//   │  1.83/km  |  22/hr  |  0.37/min        │  <- métricas grandes
//   │  19 min | 3.9 km                        │  <- detalhes
//   └─────────────────────────────────────────┘
// =====================================================================

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
        hideOverlayInternal(); stopForegroundCompat(); stopSelf()
        return START_NOT_STICKY
      }
      ACTION_SHOW -> {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val payload = OfferDecisionData.fromJson(intent.getStringExtra(EXTRA_PAYLOAD))
          ?: return START_NOT_STICKY
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
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "KmCerto Overlay", NotificationManager.IMPORTANCE_LOW)
          .apply { description = "Canal do overlay automático do KmCerto." }
      )
    }
    val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(this)
    }.setContentTitle("KmCerto ativo")
      .setContentText("Analisando ofertas.")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setOngoing(true)
      .build()
    startForeground(NOTIFICATION_ID, notification)
  }

  private fun stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION") stopForeground(true)
    }
  }

  // =====================================================================
  // Layout do overlay no estilo "Rota" (referência do TikTok)
  // =====================================================================
  private fun showOverlayInternal(data: OfferDecisionData) {
    hideOverlayInternal()
    val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager = manager

    val isAccept = data.status.lowercase().contains("aceitar")
    val accentColor = if (isAccept) "#16A34A" else "#DC2626"
    val bgColor = "#E6111111"

    // ── Container principal ──────────────────────────────────────────────
    val container = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(16), dp(12), dp(16), dp(12))
      background = GradientDrawable().apply {
        setColor(Color.parseColor(bgColor))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(2), Color.parseColor(accentColor))
      }
    }

    // ── Linha 1: Status + contexto ───────────────────────────────────────
    val statusLine = TextView(this).apply {
      text = "${data.status}: Valor da viagem"
      setTextColor(Color.parseColor(accentColor))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setTypeface(typeface, Typeface.BOLD)
      gravity = Gravity.CENTER
    }
    container.addView(statusLine)
    container.addView(spaceView(dp(6)))

    // ── Linha 2: Métricas grandes coloridas ──────────────────────────────
    val metricsRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
    }

    metricsRow.addView(createColorMetric(
      String.format(Locale("pt", "BR"), "%.2f", data.perKm),
      "/km",
      accentColor
    ))

    if (data.perHour != null) {
      metricsRow.addView(createSeparator())
      metricsRow.addView(createColorMetric(
        String.format(Locale("pt", "BR"), "%.0f", data.perHour),
        "/hr",
        accentColor
      ))
    }

    if (data.perMinute != null) {
      metricsRow.addView(createSeparator())
      metricsRow.addView(createColorMetric(
        String.format(Locale("pt", "BR"), "%.2f", data.perMinute),
        "/min",
        accentColor
      ))
    }

    container.addView(metricsRow)
    container.addView(spaceView(dp(4)))

    // ── Linha 3: Detalhes (min | km) ─────────────────────────────────────
    val detailParts = mutableListOf<String>()
    data.totalMinutes?.let { detailParts.add("${it.toInt()} min") }
    detailParts.add(String.format(Locale("pt", "BR"), "%.1f km", data.totalDistance))

    val detailLine = TextView(this).apply {
      text = detailParts.joinToString(" | ")
      setTextColor(Color.parseColor("#CCCCCC"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      gravity = Gravity.CENTER
    }
    container.addView(detailLine)

    // ── Configurar janela do overlay ─────────────────────────────────────
    val lp = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      x = 0; y = dp(72)
      width = WindowManager.LayoutParams.MATCH_PARENT
      horizontalMargin = 0.04f
    }

    try {
      manager.addView(container, lp)
      overlayView = container
      handler.removeCallbacks(dismissRunnable)
      handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
    } catch (_: Throwable) {
      hideOverlayInternal(); stopForegroundCompat(); stopSelf()
    }
  }

  private fun createColorMetric(value: String, unit: String, color: String): LinearLayout {
    return LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(TextView(this@KmCertoOverlayService).apply {
        text = value
        setTextColor(Color.parseColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTypeface(typeface, Typeface.BOLD)
      })
      addView(TextView(this@KmCertoOverlayService).apply {
        text = unit
        setTextColor(Color.parseColor("#AAAAAA"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(4), 0, 0)
      })
    }
  }

  private fun createSeparator(): TextView {
    return TextView(this).apply {
      text = "  |  "
      setTextColor(Color.parseColor("#666666"))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
      gravity = Gravity.CENTER
    }
  }

  private fun hideOverlayInternal() {
    handler.removeCallbacks(dismissRunnable)
    overlayView?.let { try { windowManager?.removeView(it) } catch (_: Throwable) { } }
    overlayView = null
  }

  private fun spaceView(height: Int): TextView =
    TextView(this).apply { minHeight = height }

  private fun dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

  companion object {
    private const val ACTION_SHOW = "expo.modules.kmcertonative.action.SHOW_OVERLAY"
    private const val ACTION_HIDE = "expo.modules.kmcertonative.action.HIDE_OVERLAY"
    private const val EXTRA_PAYLOAD = "expo.modules.kmcertonative.extra.PAYLOAD"
    private const val AUTO_DISMISS_MS = 10_000L
    private const val CHANNEL_ID = "kmcerto_overlay"
    private const val NOTIFICATION_ID = 7071

    fun show(context: Context, payload: OfferDecisionData) {
      val intent = Intent(context, KmCertoOverlayService::class.java).apply {
        action = ACTION_SHOW
        putExtra(EXTRA_PAYLOAD, payload.toJson())
      }
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
          context.startForegroundService(intent)
        else
          context.startService(intent)
      } catch (_: Throwable) { }
    }

    fun stop(context: Context) {
      try {
        context.startService(
          Intent(context, KmCertoOverlayService::class.java).apply { action = ACTION_HIDE }
        )
      } catch (_: Throwable) { }
    }
  }
}

object KmCertoFormatters {
  private val locale = Locale("pt", "BR")
  fun decimal(value: Double): String = String.format(locale, "%.2f", value)
}
