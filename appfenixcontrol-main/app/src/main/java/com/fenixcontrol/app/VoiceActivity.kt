package com.fenixcontrol.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Actividad transparente que se lanza con la doble pulsación de subir volumen.
 * Abre el micrófono, entiende la orden por voz y la ejecuta sobre la app que
 * el usuario tenga delante (a través del FenixAccessibilityService).
 *
 * Como el servicio de accesibilidad sigue viendo la pantalla de fondo, Fénix
 * puede leer y actuar sobre CUALQUIER app, no solo sobre su propio chat.
 * También permite activar/parar el modo encuestas por voz, para poder
 * arrancarlo estando encima de la encuesta sin tener que abrir el chat.
 */
class VoiceActivity : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("fenix_prefs", Context.MODE_PRIVATE)
    }

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val textos = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val dicho = textos?.firstOrNull()?.trim()
        if (!dicho.isNullOrEmpty()) {
            ejecutarOrden(dicho)
        } else {
            Toast.makeText(this, "No entendí, prueba otra vez más despacio", Toast.LENGTH_SHORT).show()
        }
        finish()  // cerramos la actividad transparente al terminar
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fénix te escucha...")
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2500)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Sin reconocimiento de voz", Toast.LENGTH_SHORT).show()
            finish()
        }
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
                    startActivity(it); return
                }
            }
            Toast.makeText(this, "No encontré esa app", Toast.LENGTH_SHORT).show()
            return
        }

        // Si no es una orden de control, la mostramos (podrías mandarla al chat)
        Toast.makeText(this, "Orden no reconocida: $orden", Toast.LENGTH_SHORT).show()
    }
}
