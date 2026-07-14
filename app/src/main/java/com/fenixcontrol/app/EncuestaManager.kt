package com.fenixcontrol.app

import android.content.Context
import android.widget.Toast
import okhttp3.OkHttpClient

/**
 * Punto único (singleton) de control del modo encuestas, independiente de
 * cualquier Activity concreta. Esto permite arrancarlo y pararlo tanto desde
 * el botón 📋 del chat como por voz (doble pulsación de volumen) mientras
 * Fénix está detrás de la encuesta, sin que el chat tenga que estar abierto
 * ni en primer plano.
 *
 * El feedback se muestra con Toast sobre la app en primer plano (la propia
 * encuesta), ya que el chat de Fénix normalmente no será visible mientras
 * el agente trabaja.
 */
object EncuestaManager {
    private var agente: EncuestaAgent? = null
    private val client = OkHttpClient()

    fun estaActivo(): Boolean = agente?.estaActivo() == true

    fun iniciar(context: Context, apiKey: String, perfil: String) {
        if (estaActivo()) return
        val appContext = context.applicationContext
        agente = EncuestaAgent(
            client = client,
            apiKey = apiKey,
            perfil = perfil,
            onLog = { texto -> Toast.makeText(appContext, texto, Toast.LENGTH_SHORT).show() },
            onFin = { texto ->
                Toast.makeText(appContext, texto, Toast.LENGTH_LONG).show()
                agente = null
            }
        ).also { it.iniciar() }
    }

    fun detener(motivo: String = "Detenido por el usuario.") {
        agente?.detener(motivo)
        agente = null
    }
}
