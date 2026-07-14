package com.fenixcontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Burbuja flotante siempre visible por encima de todas las apps (como la de
 * Messenger). Al tocarla, abre la escucha de voz de Fénix (VoiceActivity).
 * Se puede arrastrar por la pantalla.
 *
 * Necesita el permiso "Mostrar sobre otras apps" (SYSTEM_ALERT_WINDOW), que se
 * pide desde el chat. Ese mismo permiso es lo que además deja a Fénix abrir la
 * escucha desde cualquier sitio sin que Android lo bloquee.
 *
 * Es un servicio en primer plano (con notificación permanente) para que Android
 * no lo mate mientras estás en otras apps.
 */
class BotonFlotanteService : Service() {

    companion object {
        // Permite al chat saber si la burbuja ya está puesta.
        var activo = false
            private set

        private const val CANAL_ID = "fenix_burbuja"
        private const val NOTIF_ID = 1001
        const val ACCION_PARAR = "com.fenixcontrol.app.PARAR_BURBUJA"
    }

    private lateinit var windowManager: WindowManager
    private var burbuja: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        crearBurbuja()
        activo = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Botón "Ocultar" de la notificación.
        if (intent?.action == ACCION_PARAR) {
            stopSelf()
            return START_NOT_STICKY
        }
        arrancarEnPrimerPlano()
        return START_STICKY
    }

    /** Dibuja la burbuja y la hace arrastrable + pulsable. */
    private fun crearBurbuja() {
        val vista = TextView(this).apply {
            text = "🔥"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val lado = (56 * resources.displayMetrics.density).toInt()
            width = lado
            height = lado
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E64A148C")) // morado Fénix semitransparente
                setStroke(2, Color.parseColor("#FFD54F"))
            }
        }

        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            tipo,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        // Arrastrar vs. tocar: si apenas se mueve el dedo, contamos "tap".
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var movido = false

        vista.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    movido = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) movido = true
                    lp.x = initialX + dx
                    lp.y = initialY + dy
                    windowManager.updateViewLayout(vista, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!movido) abrirEscucha()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(vista, lp)
            burbuja = vista
            params = lp
        } catch (e: Exception) {
            // Sin permiso de superposición no se puede pintar; nos apagamos.
            stopSelf()
        }
    }

    /** Abre la escucha de voz de Fénix por encima de todo. */
    private fun abrirEscucha() {
        val intent = Intent(this, VoiceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun arrancarEnPrimerPlano() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CANAL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CANAL_ID,
                        "Burbuja de Fénix",
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
            }
        }

        val pararIntent = Intent(this, BotonFlotanteService::class.java).apply {
            action = ACCION_PARAR
        }
        val pararPending = PendingIntent.getService(
            this, 0, pararIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = androidx.core.app.NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("Fénix activo")
            .setContentText("Toca la burbuja 🔥 para hablarme")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(0, "Ocultar", pararPending)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        burbuja?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        burbuja = null
        activo = false
    }
}
