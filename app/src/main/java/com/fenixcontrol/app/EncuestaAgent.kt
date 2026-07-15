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
    private val onFin: (String) -> Unit,
    private val onPausado: (String) -> Unit
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

    // Pausa "pidiendo ayuda": la IA se ha topado con algo que no sabe resolver
    // sola (ej. un captcha, una pregunta que requiere un dato que no está en
    // el perfil, un paso de verificación) y necesita que el usuario intervenga
    // a mano. El ciclo se detiene sin perder el estado; reanudar() lo retoma.
    private var pausado = false

    // Reintentos consecutivos del PASO ACTUAL (red/servidor/parseo). No paran
    // el modo encuestas: solo evitan martillar la API en bucle cerrado sin
    // pausa cuando algo falla varias veces seguidas.
    private var reintentosPasoActual = 0
    private val MAX_REINTENTOS_ANTES_DE_DESPLAZAR = 3

    // Texto de la última pantalla leída; se usa para verificar un FIN antes
    // de aceptarlo (ver ejecutarAccion). Evita que la IA corte la encuesta
    // por una lectura equivocada de una pantalla intermedia.
    private var ultimoTextoPantalla = ""

    // Índice del botón 👉 detectado en el último listado (Continuar/Siguiente/
    // Enviar), si lo hay. Sirve de respaldo cuando la IA dice "continuar" o
    // "avanza" sin dar el número exacto, para no perder el turno por eso.
    private var ultimoIndiceContinuar: Int? = null

    // Cuántas veces seguidas la IA ha dicho FIN sin que la pantalla lo
    // confirme. Si insiste varias veces lo aceptamos como red de seguridad,
    // pero el umbral es alto a propósito: preferimos seguir de más que
    // cortar una encuesta a medias.
    private var finsSinConfirmarSeguidos = 0
    private val MAX_FINS_SIN_CONFIRMAR = 4

    private val palabrasFinConfirmado = listOf(
        "gracias por completar", "gracias por tu participación", "gracias por participar",
        "encuesta ha finalizado", "encuesta finalizada", "encuesta completada",
        "cuestionario ha finalizado", "hemos recibido tus respuestas", "respuestas enviadas",
        "thank you for completing", "thank you for your participation", "survey complete",
        "survey has been completed", "submission received", "response has been recorded",
        "you have completed", "quota", "screened out", "no calificas", "no cumples con el perfil"
    )

    // Avisos típicos de "preguntas en tabla/matriz" (una fila por franja
    // horaria, tema, marca, etc.) donde aún falta responder alguna fila.
    // Si la pantalla muestra alguno de estos textos, NO se pulsa el botón
    // de avanzar aunque la IA lo pida: casi seguro que hay una fila
    // desplegable sin responder que la IA no ha visto todavía.
    private val palabrasFaltaResponderFila = listOf(
        "seleccione una respuesta por fila", "seleccione una respuesta para cada fila",
        "debe responder todas las filas", "falta responder", "respuesta por fila",
        "select an answer for each row", "please answer all rows", "answer for each row"
    )

    fun estaActivo(): Boolean = activo
    fun estaPausado(): Boolean = pausado

    fun iniciar() {
        if (activo) return
        activo = true
        pausado = false
        pasos = 0
        reintentosPasoActual = 0
        finsSinConfirmarSeguidos = 0
        onLog("📋 Modo encuestas iniciado. No se detendrá solo: sigue hasta $MAX_PASOS pasos o hasta que lo pares tú.")
        ejecutarCiclo()
    }

    fun detener(motivo: String = "Detenido por el usuario.") {
        if (!activo) return
        activo = false
        pausado = false
        handler.removeCallbacksAndMessages(null)
        onFin("⏹ Modo encuestas detenido. $motivo")
    }

    /** Pausa el ciclo pidiendo intervención del usuario (captcha, dato que falta, etc.). */
    private fun pausar(motivo: String) {
        if (!activo || pausado) return
        pausado = true
        handler.removeCallbacksAndMessages(null)
        onPausado("⏸ Encuesta en pausa: $motivo. Toca la burbuja para retomar cuando lo resuelvas.")
    }

    /** Retoma el ciclo tras una pausa pidiendo ayuda. */
    fun reanudar() {
        if (!activo || !pausado) return
        pausado = false
        onLog("▶️ Reanudando modo encuestas...")
        ejecutarCiclo()
    }

    /** Reintenta o continúa el ciclo sin nunca llamar a detener() por un error puntual. */
    private fun seguirTrasError(mensaje: String) {
        if (!activo || pausado) return
        reintentosPasoActual++
        onLog("⚠️ $mensaje (reintento $reintentosPasoActual)")
        if (reintentosPasoActual >= MAX_REINTENTOS_ANTES_DE_DESPLAZAR) {
            // Varios fallos seguidos en la misma pantalla: no nos quedamos
            // atascados pidiendo lo mismo a la IA, probamos a desplazar para
            // que la siguiente vuelta tenga contenido distinto.
            reintentosPasoActual = 0
            FenixAccessibilityService.instance?.scrollForward()
        }
        handler.postDelayed({ ejecutarCiclo() }, PAUSA_MS)
    }

    private fun ejecutarCiclo() {
        if (!activo || pausado) return
        if (pasos >= MAX_PASOS) {
            detener("Se alcanzó el límite de $MAX_PASOS pasos. Revisa la pantalla por si la encuesta ya terminó.")
            return
        }
        val service = FenixAccessibilityService.instance
        if (service == null) {
            // Sin el control de pantalla no hay nada que hacer, pero no
            // damos por muerta la encuesta: reintentamos por si el usuario
            // reactiva el servicio de accesibilidad al vuelo.
            seguirTrasError("El control de pantalla no está activo. Actívalo en Ajustes de Android.")
            return
        }
        pasos++

        val elementos: List<FenixAccessibilityService.ElementoUI>
        val textoPantalla: String
        try {
            elementos = service.listarElementosInteractivos()
            textoPantalla = service.readScreenContent()
            ultimoTextoPantalla = textoPantalla
        } catch (e: Exception) {
            Log.e("EncuestaAgent", "Error leyendo la pantalla", e)
            seguirTrasError("No pude leer la pantalla (${e.message ?: "error desconocido"}).")
            return
        }

        // Si no hay nada con qué interactuar, no gastamos una llamada a la IA
        // preguntando qué hacer: desplazamos directamente. Así la encuesta
        // nunca se queda "colgada" esperando una decisión sobre una pantalla
        // vacía o a medio cargar.
        if (elementos.isEmpty()) {
            service.scrollForward()
            onLog("↕️ Sin elementos visibles, desplazando para buscar más...")
            if (activo && !pausado) handler.postDelayed({ ejecutarCiclo() }, PAUSA_MS)
            return
        }
        reintentosPasoActual = 0

        val palabrasContinuar = listOf(
            "continuar", "siguiente", "next", "enviar", "submit", "finalizar",
            "aceptar", "confirmar", "enviar respuestas", "terminar"
        )
        ultimoIndiceContinuar = elementos.firstOrNull { e ->
            e.tipo == "boton" && palabrasContinuar.any { e.texto.lowercase().contains(it) }
        }?.indice
        val listado = elementos.joinToString("\n") { e ->
            val marca = if (e.tipo == "marcado") " [ya marcado]" else ""
            val esContinuar = e.indice == ultimoIndiceContinuar
            val etiqueta = if (esContinuar) " 👉[botón para avanzar de página]" else ""
            "${e.indice}: (${e.tipo}) ${e.texto}$marca$etiqueta"
        }

        pedirSiguienteAccion(textoPantalla, listado) { accion ->
            if (!activo) return@pedirSiguienteAccion
            ejecutarAccion(service, accion) {
                if (activo && !pausado) handler.postDelayed({ ejecutarCiclo() }, PAUSA_MS)
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
            campos de texto). Los elementos marcados con 👉 son botones para avanzar
            de página (Continuar/Siguiente/Enviar). Responde SOLO con una o varias de
            estas órdenes exactas separadas por punto y coma ";", sin nada más de texto:
              MARCAR:<numero>              -> pulsa/marca un único elemento
              MARCAR:<n1>,<n2>,<n3>        -> pulsa/marca varios elementos a la vez
              ESCRIBIR:<numero>:<texto>    -> escribe <texto> en el campo con ese número
              CONTINUAR                    -> pulsa el botón 👉 de avanzar de página
              DESPLAZAR                    -> baja la pantalla para ver más opciones
              FIN:<motivo corto>           -> la encuesta ha terminado de verdad (pantalla
                                               de agradecimiento/confirmación final)
              AYUDA:<motivo corto>         -> SOLO si te topas con algo que de verdad no
                                               puedes resolver solo (captcha, verificación
                                               por SMS/email, un dato personal real que no
                                               está en el perfil, un error bloqueante de la
                                               app). Pausa y espera a que el usuario
                                               intervenga a mano.

            Reglas MUY IMPORTANTES:
            1. SIEMPRE debes elegir una respuesta y avanzar; nunca te quedes sin hacer
               nada. Si una pregunta no está cubierta en el perfil, elige la opción más
               neutral/razonable y sigue adelante. No pares a "pensar". AYUDA es un
               último recurso para bloqueos reales (captcha, verificación externa),
               no una salida fácil para preguntas simplemente difíciles.
            2. Si la pregunta permite elegir varias opciones (checkboxes, "selecciona
               todas las que apliquen"), marca en el MISMO turno TODAS las opciones que
               correspondan al perfil usando MARCAR:<n1>,<n2>,<n3> antes de avanzar de
               página. No avances con la pregunta a medio responder.
            3. Algunas pantallas son una TABLA/MATRIZ con varias filas (ej: franjas
               horarias, varios temas o marcas, cada una con su propia pregunta). A
               veces cada fila está colapsada y hay que tocarla primero para
               desplegarla y ver sus opciones. Estrategia: si ves una fila con texto
               pero sin opciones visibles debajo, márcala para desplegarla; en el
               turno siguiente responde esa fila; luego repite con la siguiente fila
               sin desplegar. Sigue así fila por fila. Si el texto de la pantalla dice
               algo como "seleccione una respuesta por fila" o similar, es que TODAVÍA
               falta responder alguna fila: no pulses el botón de avanzar aunque lo
               veas, sigue buscando la fila que falta (puede requerir DESPLAZAR para
               verla) hasta que todas tengan una opción marcada.
            4. Cuando ya hayas respondido todo lo visible en la pantalla, busca el
               elemento marcado con 👉 y púlsalo (MARCAR:<numero> o CONTINUAR) para
               pasar a la siguiente página. IMPORTANTE: en cuanto termines de
               responder TODO lo pendiente en la pantalla, añade también CONTINUAR en
               el mismo turno separado por ";". Ejemplos: "MARCAR:2;CONTINUAR" o
               "ESCRIBIR:0:34;CONTINUAR". No dejes el "pulsar continuar" para el turno
               siguiente si ya no queda nada más que responder en esa pantalla: es un
               fallo grave que la respuesta se quede sin enviar.
            5. Si no ves ningún 👉 ni preguntas nuevas tras responder, usa DESPLAZAR
               antes de rendirte.
            6. Solo responde FIN si el texto de la pantalla indica claramente que la
               encuesta ya se completó (agradecimiento, confirmación de envío, etc.).
               No respondas FIN solo porque una pregunta te resulte difícil.
            7. Elige solo números que existan en la lista. Nunca reveles datos
               personales reales del usuario, solo el perfil dado.
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
                // Un timeout o corte de red puntual NO debe matar el modo
                // encuestas entero: reintentamos el mismo paso.
                handler.post { seguirTrasError("Error de red: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val cuerpo = try { it.body?.string()?.take(200) } catch (e: Exception) { null }
                        // Igual con errores del servidor (429 rate-limit, 500,
                        // etc.): reintentamos en vez de rendirnos del todo.
                        handler.post {
                            seguirTrasError("Error del servidor: ${it.code}${if (!cuerpo.isNullOrBlank()) " - $cuerpo" else ""}")
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
                        // Respuesta rara de la IA: reintentamos en lugar de parar.
                        handler.post { seguirTrasError("No entendí la respuesta de la IA.") }
                    }
                }
            }
        })
    }

    private fun ejecutarAccion(service: FenixAccessibilityService, accionCruda: String, onDone: () -> Unit) {
        // La IA puede encadenar varias órdenes en un turno, ej:
        // "ESCRIBIR:0:34;CONTINUAR". Las ejecutamos en orden, una a una,
        // con una pequeña pausa tras ESCRIBIR para que el teclado en
        // pantalla termine de cerrarse antes del siguiente toque.
        val partes = accionCruda.split(";").map { it.trim() }.filter { it.isNotBlank() }
        ejecutarPartes(service, partes, 0, onDone)
    }

    private fun ejecutarPartes(
        service: FenixAccessibilityService,
        partes: List<String>,
        i: Int,
        onDone: () -> Unit
    ) {
        if (i >= partes.size || !activo || pausado) {
            onDone()
            return
        }
        val parte = partes[i]
        try {
            ejecutarUnaOrden(service, parte)
        } catch (e: Exception) {
            Log.e("EncuestaAgent", "Error ejecutando \"$parte\"", e)
            onLog("⚠️ No pude ejecutar \"$parte\" (${e.message ?: "error"}), sigo adelante.")
        }
        if (!activo || pausado) {
            onDone()
            return
        }
        if (i + 1 < partes.size) {
            val esperaTecladoCierre = parte.startsWith("ESCRIBIR", ignoreCase = true)
            val pausa = if (esperaTecladoCierre) 450L else 120L
            handler.postDelayed({ ejecutarPartes(service, partes, i + 1, onDone) }, pausa)
        } else {
            onDone()
        }
    }

    private fun ejecutarUnaOrden(service: FenixAccessibilityService, accion: String) {
        Regex("""AYUDA:?\s*(.*)""", RegexOption.IGNORE_CASE).find(accion)?.let { m ->
            val motivo = m.groupValues[1].ifBlank { "necesita intervención manual" }
            pausar(motivo)
            return
        }

        Regex("""FIN:?\s*(.*)""", RegexOption.IGNORE_CASE).find(accion)?.let { m ->
            val motivo = m.groupValues[1].ifBlank { "sin motivo indicado" }
            val pantalla = ultimoTextoPantalla.lowercase()
            val confirmadoPorPantalla = palabrasFinConfirmado.any { pantalla.contains(it) }
            if (confirmadoPorPantalla) {
                detener("Encuesta finalizada: $motivo")
                return
            }
            // La IA dice que terminó pero el texto de la pantalla no lo
            // confirma: probablemente se ha equivocado o hay una pregunta
            // rara que no supo interpretar. NO paramos; forzamos un
            // desplazamiento para que la siguiente vuelta vea contenido
            // distinto, y solo nos rendimos tras varios FIN seguidos sin
            // confirmar (red de seguridad para no quedarnos en bucle eterno).
            finsSinConfirmarSeguidos++
            if (finsSinConfirmarSeguidos >= MAX_FINS_SIN_CONFIRMAR) {
                detener("La IA insistió $finsSinConfirmarSeguidos veces en que la encuesta terminó (\"$motivo\"), aunque la pantalla no lo confirma con claridad. Revísala por si acaso.")
                return
            }
            onLog("⚠️ La IA dijo FIN (\"$motivo\") pero la pantalla no lo confirma, sigo (intento $finsSinConfirmarSeguidos/$MAX_FINS_SIN_CONFIRMAR)...")
            service.scrollForward()
            return
        }
        finsSinConfirmarSeguidos = 0

        // "CONTINUAR" a secas (sin número): usa el botón 👉 detectado en el
        // último listado. Evita que se pierda el turno si la IA no da el
        // número exacto del botón de avanzar.
        if (Regex("""^(CONTINUAR|CONTINUE|AVANZAR|SIGUIENTE)\s*$""", RegexOption.IGNORE_CASE).matches(accion)) {
            val pantalla = ultimoTextoPantalla.lowercase()
            if (palabrasFaltaResponderFila.any { pantalla.contains(it) }) {
                onLog("⚠️ La página avisa de que falta responder alguna fila; no pulso Continuar todavía, sigo buscando qué falta.")
                service.scrollForward()
                return
            }
            val idx = ultimoIndiceContinuar
            if (idx == null) {
                onLog("⚠️ La IA dijo CONTINUAR pero no detecté ningún botón de avanzar en esta pantalla.")
                return
            }
            val ok = service.pulsarElementoPorIndice(idx)
            onLog(if (ok) "➡️ Continuar pulsado (elemento $idx)" else "⚠️ No pude pulsar Continuar (elemento $idx)")
            return
        }

        Regex("""MARCAR:\s*([\d,\s]+)""", RegexOption.IGNORE_CASE).find(accion)?.let { m ->
            val indices = m.groupValues[1].split(",")
                .mapNotNull { it.trim().toIntOrNull() }
            // Si entre los índices a marcar está el propio botón de avanzar,
            // aplicamos la misma comprobación: no avanzar si la página avisa
            // de que aún faltan filas por responder.
            val incluyeContinuar = ultimoIndiceContinuar != null && indices.contains(ultimoIndiceContinuar)
            if (incluyeContinuar) {
                val pantalla = ultimoTextoPantalla.lowercase()
                if (palabrasFaltaResponderFila.any { pantalla.contains(it) }) {
                    onLog("⚠️ La página avisa de que falta responder alguna fila; no pulso Continuar todavía.")
                    val restantes = indices.filter { it != ultimoIndiceContinuar }
                    if (restantes.isNotEmpty()) service.pulsarVariosElementosPorIndice(restantes)
                    return
                }
            }
            if (indices.size <= 1) {
                val idx = indices.firstOrNull() ?: return
                val ok = service.pulsarElementoPorIndice(idx)
                onLog(if (ok) "☑️ Marcado elemento $idx" else "⚠️ No pude marcar el elemento $idx")
            } else {
                val ok = service.pulsarVariosElementosPorIndice(indices)
                onLog("☑️ Marcados $ok de ${indices.size} elementos: ${indices.joinToString()}")
            }
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
