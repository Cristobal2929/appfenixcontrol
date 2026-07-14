package com.fenixcontrol.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Agente autónomo de "modo encuestas": en bucle, lee los elementos
 * interactivos de la pantalla actual (radios, checkboxes, botones, campos),
 * se los manda a la IA junto con el perfil de respuesta que definió el
 * usuario, y ejecuta la acción que la IA elija (marcar una opción, escribir
 * texto, avanzar de página, etc.) a través del FenixAccessibilityService.
 *
 * Pensado para encuestas remuneradas (Workana/Upwork/paneles de encuestas):
 * el usuario define un perfil (edad, país, preferencias...) y el agente
 * responde de forma consistente con ese perfil, pantalla a pantalla, sin
 * intervención manual. Incluye un tope de pasos y un botón de parada porque
 * es una acción con efectos reales (envía respuestas) y no debe quedar
 * corriendo sin control.
 */
class EncuestaAgent(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val perfil: String,
    private val onLog: (String) -> Unit,
    private val onFin: (String) -> Unit
) {
    companion object {
        private const val MODEL = "gpt-oss-120b"
        private const val URL = "https://api.cerebras.ai/v1/chat/completions"
        private const val PAUSA_MS = 2200L
        private const val MAX_PASOS = 500
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pasos = 0
    private var activo = false

    fun estaActivo(): Boolean = activo

    fun iniciar() {
        if (activo) return
        activo = true
        pasos = 0
        onLog("📋 Modo encuestas iniciado. Máximo $MAX_PASOS pasos, se puede detener en cualquier momento.")
        ejecutarCiclo()
    }

    fun detener(motivo: String = "Detenido por el usuario.") {
        if (!activo) return
        activo = false
        handler.removeCallbacksAndMessages(null)
        onFin("⏹ Modo encuestas detenido. $motivo")
    }

    private fun ejecutarCiclo() {
        if (!activo) return
        val service = FenixAccessibilityService.instance
        if (service == null) {
            detener("El control de pantalla no está activo (actívalo en Ajustes de Android).")
            return
        }
        if (pasos >= MAX_PASOS) {
            detener("Se alcanzó el límite de $MAX_PASOS pasos. Revisa la pantalla por si la encuesta ya terminó.")
            return
        }
        pasos++

        val elementos = service.listarElementosInteractivos()
        val textoPantalla = service.readScreenContent()

        val listado = if (elementos.isEmpty()) {
            "(no se detectaron elementos interactivos; puede que la pantalla solo tenga texto o haya que desplazar)"
        } else {
            elementos.joinToString("\n") { e ->
                val marca = if (e.tipo == "marcado") " [ya marcado]" else ""
                "${e.indice}: (${e.tipo}) ${e.texto}$marca"
            }
        }

        pedirSiguienteAccion(textoPantalla, listado) { accion ->
            if (!activo) return@pedirSiguienteAccion
            ejecutarAccion(service, accion)
            if (activo) {
                handler.postDelayed({ ejecutarCiclo() }, PAUSA_MS)
            }
        }
    }

    private fun pedirSiguienteAccion(
        textoPantalla: String,
        listado: String,
        callback: (String) -> Unit
    ) {
        val system = """
            Eres el agente de Fénix en "modo encuestas". Tu trabajo es rellenar una
            encuesta en una app o navegador del móvil, paso a paso, respondiendo de
            forma consistente con este perfil de respuesta que definió el usuario:

            $perfil

            En cada turno te doy el texto visible de la pantalla y una lista numerada
            de elementos con los que puedes interactuar (botones, casillas, radios,
            campos de texto). Responde SOLO con UNA de estas órdenes exactas, sin nada
            más de texto:
              MARCAR:<numero>            -> pulsa/marca el elemento con ese número
              ESCRIBIR:<numero>:<texto>  -> escribe <texto> en el campo con ese número
              DESPLAZAR                  -> baja la pantalla para ver más opciones
              FIN:<motivo corto>         -> la encuesta ha terminado o no puedes continuar

            Reglas: elige solo números que existan en la lista. Si una pregunta no
            está en el perfil, responde con la opción más neutral/razonable. Nunca
            reveles datos personales reales del usuario, solo el perfil dado.
        """.trimIndent()

        val user = "TEXTO DE LA PANTALLA:\n$textoPantalla\n\nELEMENTOS:\n$listado"

        val json = JSONObject().apply {
            put("model", MODEL)
            val messagesArray = JSONArray()
            messagesArray.put(JSONObject().apply { put("role", "system"); put("content", system) })
            messagesArray.put(JSONObject().apply { put("role", "user"); put("content", user) })
            put("messages", messagesArray)
            put("temperature", 0.3)
        }

        val requestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EncuestaAgent", "Error de red", e)
                handler.post { detener("Error de red: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val cuerpo = try { it.body?.string()?.take(200) } catch (e: Exception) { null }
                        handler.post {
                            detener("Error del servidor: ${it.code}${if (!cuerpo.isNullOrBlank()) " - $cuerpo" else ""}")
                        }
                        return
                    }
                    try {
                        val respJson = JSONObject(it.body?.string() ?: "")
                        val content = respJson.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message")
                            .getString("content").trim()
                        handler.post { callback(content) }
                    } catch (e: Exception) {
                        Log.e("EncuestaAgent", "Error al parsear", e)
                        handler.post { detener("No entendí la respuesta de la IA.") }
                    }
                }
            }
        })
    }

    private fun ejecutarAccion(service: FenixAccessibilityService, accionCruda: String) {
        val accion = accionCruda.trim()

        Regex("""FIN:?\s*(.*)""", RegexOption.IGNORE_CASE).find(accion)?.let { m ->
            detener("Encuesta finalizada: ${m.groupValues[1].ifBlank { "sin motivo indicado" }}")
            return
        }

        Regex("""MARCAR:\s*(\d+)""", RegexOption.IGNORE_CASE).find(accion)?.let { m ->
            val idx = m.groupValues[1].toInt()
            val ok = service.pulsarElementoPorIndice(idx)
            onLog(if (ok) "☑️ Marcado elemento $idx" else "⚠️ No pude marcar el elemento $idx")
            return
        }

        Regex("""ESCRIBIR:\s*(\d+)\s*:\s*(.*)""", RegexOption.IGNORE_CASE).find(accion)?.let { m ->
            val idx = m.groupValues[1].toInt()
            val texto = m.groupValues[2].trim()
            val ok = service.escribirEnElementoPorIndice(idx, texto)
            onLog(if (ok) "⌨️ Escrito \"$texto\" en el campo $idx" else "⚠️ No pude escribir en el campo $idx")
            return
        }

        if (accion.contains("DESPLAZAR", ignoreCase = true)) {
            service.scrollForward()
            onLog("↕️ Desplazado")
            return
        }

        onLog("🤔 Respuesta no reconocida de la IA: $accion")
    }
}
