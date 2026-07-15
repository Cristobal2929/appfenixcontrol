package com.fenixcontrol.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Actividad transparente que se lanza con la doble pulsación de subir volumen.
 * Abre el micrófono, entiende la orden por voz y la ejecuta sobre la app que
 * el usuario tenga delante (a través del FenixAccessibilityService).
 *
 * Como el servicio de accesibilidad sigue viendo la pantalla de fondo, Fénix
 * puede leer y actuar sobre CUALQUIER app, no solo sobre su propio chat.
 * También permite activar/parar el modo encuestas por voz, para poder
 * arrancarlo estando encima de la encuesta sin tener que abrir el chat.
 *
 * CAPTURA DE SONIDO:
 * En lugar de lanzar el diálogo de Google (RecognizerIntent), que fallaba con
 * frecuencia (dependía de una app externa, del modelo offline y de un popup que
 * se portaba mal sobre esta ventana translúcida), ahora se abre el micrófono
 * directamente en la propia app con SpeechRecognizer + RecognitionListener.
 * Es mucho más fiable y no depende de ninguna pantalla intermedia.
 */
class VoiceActivity : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("fenix_prefs", Context.MODE_PRIVATE)
    }

    private var recognizer: SpeechRecognizer? = null
    private var estado: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    // Lanzador de solicitud de permiso de micrófono en tiempo de ejecución.
    private val permisoLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) {
            empezarAEscuchar()
        } else {
            Toast.makeText(
                this,
                "Fénix necesita el micrófono para escucharte. Actívalo en Ajustes > Apps > Fénix > Permisos.",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(crearPanelEscucha())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            empezarAEscuchar()
        } else {
            permisoLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Pequeño panel translúcido en el centro para que se vea que Fénix está
     * escuchando (con SpeechRecognizer no hay diálogo del sistema que lo indique).
     */
    private fun crearPanelEscucha(): FrameLayout {
        val fondo = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
        }
        estado = TextView(this).apply {
            text = "🎙  Fénix te escucha…"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(48, 36, 48, 36)
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#CC1E1E1E"))
            }
        }
        fondo.addView(
            estado,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )
        return fondo
    }

    /** Abre el micrófono con SpeechRecognizer y escucha una orden. */
    private fun empezarAEscuchar() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Este móvil no tiene reconocimiento de voz", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(escuchaListener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Dejamos que use el motor online (el que suele funcionar). No forzamos
            // offline: era una de las causas de que fallara en muchos móviles.
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2500)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el micrófono", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val escuchaListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            estado?.text = "🎙  Habla ahora…"
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            estado?.text = "⏳  Procesando…"
        }

        override fun onResults(results: Bundle?) {
            val textos = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val dicho = textos?.firstOrNull()?.trim()
            if (!dicho.isNullOrEmpty()) {
                ejecutarOrden(dicho)
            } else {
                Toast.makeText(this@VoiceActivity, "No entendí, prueba otra vez más despacio", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val textos = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            textos?.firstOrNull()?.let { if (it.isNotBlank()) estado?.text = it }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "No te oí bien. Prueba otra vez, más cerca del micro."
                SpeechRecognizer.ERROR_AUDIO -> "Problema con el micrófono."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Falta el permiso de micrófono. Actívalo en Ajustes."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Sin conexión para reconocer la voz."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "El reconocedor estaba ocupado, prueba otra vez."
                else -> "No se pudo entender (error $error)."
            }
            Toast.makeText(this@VoiceActivity, msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        recognizer = null
    }

    /**
     * Interpreta la orden dicha y actúa sobre la app en primer plano.
     * Reutiliza el servicio de accesibilidad para leer, pulsar, escribir...
     */
    private fun ejecutarOrden(orden: String) {
        val service = FenixAccessibilityService.instance
        val lower = orden.lowercase()

        if (service == null) {
            Toast.makeText(this, "Activa el control de pantalla primero", Toast.LENGTH_LONG).show()
            return
        }

        // PARAR MODO ENCUESTAS: "para la encuesta" / "detén la encuesta"...
        if (lower.contains("para la encuesta") || lower.contains("detén la encuesta") ||
            lower.contains("detener encuesta") || lower.contains("para encuesta")
        ) {
            EncuestaManager.detener("Detenido por voz.")
            Toast.makeText(this, "⏹ Modo encuestas detenido", Toast.LENGTH_SHORT).show()
            return
        }

        // ACTIVAR MODO ENCUESTAS: "activa el modo encuestas" / "rellena la encuesta"...
        // Reutiliza la clave y el último perfil guardados desde el botón 📋 del chat,
        // así se puede arrancar por voz sin tener que abrir Fénix y tapar la encuesta.
        if (lower.contains("encuesta")) {
            if (EncuestaManager.estaActivo()) {
                Toast.makeText(this, "El modo encuestas ya está activo", Toast.LENGTH_SHORT).show()
                return
            }
            val apiKey = prefs.getString("api_key", null)
            val perfil = prefs.getString("perfil_encuesta", null)
            if (apiKey.isNullOrEmpty()) {
                Toast.makeText(this, "Configura tu clave de Cerebras en Ajustes primero", Toast.LENGTH_LONG).show()
                return
            }
            if (perfil.isNullOrEmpty()) {
                Toast.makeText(
                    this,
                    "Define primero un perfil desde el botón 📋 del chat; una vez guardado, podrás activarlo por voz",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            EncuestaManager.iniciar(this, apiKey, perfil)
            Toast.makeText(this, "📋 Modo encuestas iniciado (máx. 500 pasos)", Toast.LENGTH_SHORT).show()
            return
        }

        // LEER lo que hay en la app de delante y decirlo en voz alta / toast
        if (lower.contains("lee") || lower.contains("leer") || lower.contains("qué pone") || lower.contains("que pone")) {
            val contenido = service.readScreenContent()
            val corto = if (contenido.length > 300) contenido.take(300) + "..." else contenido
            Toast.makeText(this, if (corto.isBlank()) "No hay texto visible" else corto, Toast.LENGTH_LONG).show()
            return
        }

        // ESCRIBIR en el campo activo (útil para responder en WhatsApp)
        val writeRegex = Regex("""(?:escribe|responde|contesta)\s+(.+)""", RegexOption.IGNORE_CASE)
        writeRegex.find(orden)?.let { m ->
            service.inputText(m.groupValues[1].trim())
            Toast.makeText(this, "Escrito", Toast.LENGTH_SHORT).show()
            return
        }

        // DESLIZAR
        if (lower.contains("desliza") || lower.contains("baja") || lower.contains("desplaza")) {
            service.scrollForward()
            Toast.makeText(this, "Desplazado", Toast.LENGTH_SHORT).show()
            return
        }

        // ABRIR una app
        val openRegex = Regex("""abre?\s+(?:el\s+|la\s+)?(.+)""", RegexOption.IGNORE_CASE)
        openRegex.find(orden)?.let { m ->
            val nombre = m.groupValues[1].trim().lowercase()
            val pkgs = mapOf(
                "whatsapp" to "com.whatsapp",
                "telegram" to "org.telegram.messenger",
                "instagram" to "com.instagram.android",
                "youtube" to "com.google.android.youtube",
                "chrome" to "com.android.chrome",
                "gmail" to "com.google.android.gm"
            )
            val pkg = pkgs.entries.firstOrNull { nombre.contains(it.key) }?.value
            if (pkg != null) {
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    startActivity(it)
                    // Igual que en el chat: si piden split o estamos en el flujo
                    // de encuestas, activamos pantalla dividida tras el arranque.
                    val quiereSplit = lower.contains("split") || lower.contains(" en dos") ||
                        lower.contains("dos plano") || lower.contains("pantalla dividida") ||
                        lower.contains("encuesta")
                    if (quiereSplit) {
                        handler.postDelayed({
                            val ok = FenixAccessibilityService.instance?.activarSplitScreen() ?: false
                            Toast.makeText(
                                this,
                                if (ok) "🔳 Activando pantalla dividida..." else "⚠️ No pude activar la pantalla dividida",
                                Toast.LENGTH_SHORT
                            ).show()
                        }, 900)
                    }
                    return
                }
            }
            Toast.makeText(this, "No encontré esa app", Toast.LENGTH_SHORT).show()
            return
        }

        // Si no es una orden de control, la mostramos (podrías mandarla al chat)
        Toast.makeText(this, "Orden no reconocida: $orden", Toast.LENGTH_SHORT).show()
    }
}
