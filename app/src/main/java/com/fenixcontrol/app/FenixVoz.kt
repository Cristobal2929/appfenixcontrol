package com.fenixcontrol.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Voz de Fénix (texto-a-voz). Una sola instancia de TextToSpeech compartida por
 * toda la app, para que hable tanto el modo encuestas —cuando se atasca y pide
 * ayuda o cuando termina— como el chat de voz cuando le hablas y te contesta.
 *
 * La inicialización de TextToSpeech es ASÍNCRONA: si pides hablar antes de que
 * el motor esté listo (por ejemplo, la primera frase justo al abrir la app), el
 * texto se guarda y se dice en cuanto el motor arranca, así no se pierde.
 *
 * No necesita permisos ni nada en el Manifest: TextToSpeech usa el motor de voz
 * del propio móvil (normalmente el de Google).
 */
object FenixVoz {
    private var tts: TextToSpeech? = null
    private var listo = false

    // Frases que se pidieron antes de que el motor estuviera listo; se sueltan
    // en cuanto arranca, en el mismo orden.
    private val pendientes = ArrayDeque<String>()

    /** Arranca el motor si aún no lo está. Es seguro llamarlo varias veces. */
    private fun asegurarIniciado(context: Context) {
        if (tts != null) return
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { motor ->
                    val r = motor.setLanguage(Locale("es", "ES"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // Si no hay español instalado, no reventamos: usamos el
                        // idioma por defecto del móvil.
                        motor.language = Locale.getDefault()
                    }
                    motor.setSpeechRate(1.0f)
                    listo = true
                    // Suelta lo que se quedó en cola mientras el motor arrancaba.
                    while (pendientes.isNotEmpty()) {
                        decir(pendientes.removeFirst(), enCola = true)
                    }
                }
            } else {
                Log.e("FenixVoz", "No se pudo iniciar TextToSpeech (status=$status)")
            }
        }
    }

    /**
     * Dice [texto] en voz alta. Si el motor aún no está listo, lo guarda para
     * decirlo en cuanto arranque. Limpia emojis y símbolos sueltos para que la
     * voz no los deletree.
     *
     * @param interrumpir si es true, corta lo que esté diciendo y dice esto ya
     *        (útil para avisos importantes como "necesito ayuda"). Si es false,
     *        se pone en cola detrás de lo que ya esté sonando.
     */
    fun hablar(context: Context, texto: String, interrumpir: Boolean = false) {
        asegurarIniciado(context)
        val limpio = limpiar(texto)
        if (limpio.isBlank()) return
        if (!listo) {
            pendientes.addLast(limpio)
            return
        }
        decir(limpio, enCola = !interrumpir)
    }

    private fun decir(texto: String, enCola: Boolean) {
        val modo = if (enCola) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        tts?.speak(texto, modo, null, "fenix-${System.currentTimeMillis()}")
    }

    /** Corta lo que esté diciendo en este momento (deja el motor vivo). */
    fun callar() {
        tts?.stop()
    }

    /** Libera el motor por completo. Llamar al cerrar la app si se quiere. */
    fun apagar() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        listo = false
        pendientes.clear()
    }

    // Deja solo letras (con acentos y ñ), números, espacios y signos de
    // puntuación corrientes. Cualquier emoji o símbolo raro se sustituye por un
    // espacio, para que el motor no lo lea letra a letra ni diga "cara sonriente".
    private fun limpiar(texto: String): String {
        return texto
            .replace(Regex("[^\\p{L}\\p{N}\\s.,;:¿?¡!()\"'%€/+\\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
