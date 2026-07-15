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
    fun estaPausadoPidiendoAyuda(): Boolean = agente?.estaPausado() == true

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
                // Además del Toast, lo dice en voz alta: así te enteras de que
                // la encuesta terminó (o se detuvo) sin tener que mirar.
                FenixVoz.hablar(appContext, texto)
                agente = null
            },
            onPausado = { texto ->
                // Aviso largo y repetido: si el usuario no está mirando el
                // móvil en ese instante, un solo Toast corto se puede perder.
                // Por eso, además del Toast, lo dice en voz alta e interrumpe
                // cualquier otra cosa que estuviera sonando: es el momento en
                // que Fénix te necesita de verdad.
                Toast.makeText(appContext, texto, Toast.LENGTH_LONG).show()
                FenixVoz.hablar(appContext, texto, interrumpir = true)
            }
        ).also { it.iniciar() }
    }

    /** Retoma el modo encuestas tras una pausa pidiendo ayuda (toque largo en la burbuja). */
    fun reanudar(context: Context) {
        val a = agente
        if (a == null || !a.estaPausado()) {
            Toast.makeText(
                context.applicationContext,
                "No hay ninguna encuesta pausada esperando ayuda.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        a.reanudar()
    }

    fun detener(motivo: String = "Detenido por el usuario.") {
        agente?.detener(motivo)
        agente = null
    }
}
