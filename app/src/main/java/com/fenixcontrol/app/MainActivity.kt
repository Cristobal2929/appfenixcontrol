package com.fenixcontrol.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fenixcontrol.app.databinding.ActivityMainBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val messages = mutableListOf<Message>()
    private lateinit var adapter: MessageAdapter
    private val client = OkHttpClient()
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("fenix_prefs", Context.MODE_PRIVATE)
    }

    // Vuelve aquí cuando el usuario sale de la pantalla de "Mostrar sobre
    // otras apps". Si ya dio el permiso, encendemos la burbuja.
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            encenderBurbuja()
        } else {
            addMessage("Necesito el permiso \"Mostrar sobre otras apps\" para poder poner la burbuja.", false)
        }
    }

    // Lanzador del reconocimiento de voz de Android. Al terminar, mete lo
    // dictado como si el usuario lo hubiera escrito y lo procesa.
        private val voiceLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
            ) { result ->
            if (result.resultCode == RESULT_OK) {
                val textos = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val dicho = textos?.firstOrNull()?.trim()
                if (!dicho.isNullOrEmpty()) {
                    binding.editTextMessage.setText(dicho)
                    binding.editTextMessage.setSelection(dicho.length)
                    enviarMensaje(dicho)
                } else {
                    addMessage("No entendí nada, prueba de nuevo un poco más despacio y cerca del micro.", false)
                }
            } else {
                Log.w("Voz", "Reconocimiento de voz cancelado o sin resultado (resultCode=${result.resultCode})")
                addMessage("No he entendido bien. Prueba otra vez, o descarga el reconocimiento de voz sin conexión en Ajustes de Google para que falle menos.", false)
            }
        }

        private fun enviarMensaje(texto: String) {
            addMessage(texto, true)
            binding.editTextMessage.text?.clear()
            sendMessageToAI(texto)
        }
    /** Pide el permiso de superposición si falta, y si ya está, enciende la burbuja. */
    private fun pedirPermisoYEncenderBurbuja() {
        if (Settings.canDrawOverlays(this)) {
            encenderBurbuja()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun encenderBurbuja() {
        val intent = Intent(this, BotonFlotanteService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.buttonBurbuja.text = "🛑"
    }

    private fun apagarBurbuja() {
        stopService(Intent(this, BotonFlotanteService::class.java))
        binding.buttonBurbuja.text = "🔥"
    }

    private fun escucharVoz() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora...")
            // Preferir el motor offline si está descargado: más rápido y no
            // depende de la red, así falla menos con "No lo he entendido".
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Da más margen de silencio antes de cortar el audio, para frases
            // dichas más despacio o con alguna pausa.
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2500)
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 3000)
            // Pide varias alternativas por si la primera transcripción falla.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            addMessage("Este móvil no tiene reconocimiento de voz disponible.", false)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MessageAdapter(messages)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.buttonSend.setOnClickListener {
            val text = binding.editTextMessage.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                enviarMensaje(text)    
            }
        }

        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Botón para abrir los ajustes de accesibilidad de Android, donde el
        // usuario activa a mano el servicio de control (Android obliga a esto).
        binding.buttonAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.buttonVoice.setOnClickListener {
            escucharVoz()
        }

        binding.buttonBurbuja.setOnClickListener {
            if (BotonFlotanteService.activo) {
                apagarBurbuja()
            } else {
                pedirPermisoYEncenderBurbuja()
            }
        }

        binding.buttonEncuesta.setOnClickListener {
            if (EncuestaManager.estaActivo()) {
                EncuestaManager.detener()
                binding.buttonEncuesta.text = "📋"
            } else {
                mostrarDialogoEncuesta()
            }
        }

        // Mensaje de bienvenida con instrucciones
        addMessage(
            "Soy Fénix. Configura tu clave de Cerebras en Ajustes ⚙️ y activa el " +
            "control de pantalla con el botón 🖐. Puedes hablarme con el botón 🎤. " +
            "✨ Nuevo: con DOBLE PULSACIÓN de SUBIR VOLUMEN me abres estés donde " +
            "estés y me hablas (\"lee\", \"responde hola\", \"abre WhatsApp\", " +
            "\"desliza\"). Sé hacer: abrir apps, leer pantalla, pulsar, escribir, " +
            "deslizar, y responder tus preguntas. 📋 Modo encuestas: primero " +
            "pulsa 📋 aquí UNA VEZ para guardar tu perfil de respuesta. Después, " +
            "abre la encuesta y actívalo con doble pulsación de volumen + " +
            "\"activa el modo encuestas\" (para pararlo: \"para la encuesta\"). " +
            "Los avisos del agente aparecen como mensajes flotantes sobre la " +
            "propia encuesta, no aquí en el chat.",
            false
        )
    }

    /**
     * Pide al usuario el perfil de respuesta (edad, país, opiniones...) y,
     * con eso, arranca el agente autónomo de modo encuestas sobre la app que
     * tenga abierta delante en ese momento.
     */
    private fun mostrarDialogoEncuesta() {
        val apiKey = prefs.getString("api_key", null)
        if (apiKey.isNullOrEmpty()) {
            addMessage("Error: configura tu clave de Cerebras en Ajustes ⚙️.", false)
            return
        }
        if (FenixAccessibilityService.instance == null) {
            addMessage(
                "El control de pantalla no está activo. Pulsa el botón de " +
                "accesibilidad y actívalo en Ajustes de Android.", false
            )
            return
        }

        val input = EditText(this).apply {
            hint = getString(R.string.hint_perfil_encuesta)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefs.getString("perfil_encuesta", ""))
            setPadding(48, 32, 48, 32)
        }

        // El perfil puede ser largo (varias líneas de datos demográficos).
        // Sin límite de altura, el EditText empuja los botones del diálogo
        // fuera de la pantalla. Lo metemos en un ScrollView con altura máxima
        // fija para que siempre se vean "Iniciar"/"Cancelar".
        val contenedorScroll = ScrollView(this).apply {
            addView(
                input,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val alturaMaximaPx = (280 * resources.displayMetrics.density).toInt()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, alturaMaximaPx)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.iniciar_encuesta))
            .setMessage(
                "Abre primero la encuesta en su app o navegador, deja esta ventana " +
                "encima y describe cómo debo responder (ej: \"Hombre, 34 años, " +
                "España, freelance, opiniones neutras y positivas sobre marcas\")."
            )
            .setView(contenedorScroll)
            .setPositiveButton(getString(R.string.iniciar_encuesta)) { _, _ ->
                val perfil = input.text?.toString()?.trim().orEmpty()
                if (perfil.isEmpty()) {
                    addMessage("Necesito un perfil de respuesta para empezar.", false)
                    return@setPositiveButton
                }
                prefs.edit().putString("perfil_encuesta", perfil).apply()
                iniciarModoEncuesta(apiKey, perfil)
            }
            .setNegativeButton(getString(R.string.detener_encuesta), null)
            .show()
    }

    private fun iniciarModoEncuesta(apiKey: String, perfil: String) {
        binding.buttonEncuesta.text = "⏹"
        EncuestaManager.iniciar(this, apiKey, perfil)
        // Importante: el agente actúa sobre la app que esté delante. Si nos
        // quedamos aquí abiertos, tapamos la encuesta y no se ve nada avanzar.
        // Mandamos Fénix a segundo plano automáticamente para que reaparezca
        // la encuesta (la tarea anterior en la pila) y el agente trabaje sobre
        // ella sin que el usuario tenga que cambiar de app a mano.
        addMessage("📋 Modo encuestas iniciado. Vuelvo a segundo plano...", false)
        binding.root.postDelayed({ moveTaskToBack(true) }, 400)
    }

    override fun onResume() {
        super.onResume()
        // Por si el modo encuestas se activó o paró por voz mientras el chat
        // no estaba en primer plano, sincronizamos el texto del botón.
        binding.buttonEncuesta.text = if (EncuestaManager.estaActivo()) "⏹" else "📋"
        binding.buttonBurbuja.text = if (BotonFlotanteService.activo) "🛑" else "🔥"
    }

    override fun onDestroy() {
        super.onDestroy()
        // OJO: no detenemos el modo encuestas aquí a propósito. Debe poder
        // seguir corriendo aunque el usuario cierre o abandone este chat,
        // ya que normalmente estará usándolo con la encuesta en primer plano.
    }

    private fun addMessage(text: String, isUser: Boolean) {
        messages.add(Message(text, isUser))
        adapter.notifyItemInserted(messages.size - 1)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    /**
     * Antes de mandar el mensaje a la IA, intentamos ejecutar órdenes directas
     * de control (pulsar, escribir, leer, deslizar). Si el mensaje es una orden
     * de control, la ejecuta el AccessibilityService y NO se gasta una llamada
     * a la IA. Si no lo es, va a la IA como chat normal.
     */
    private fun sendMessageToAI(userMessage: String) {
        if (tryLocalCommand(userMessage)) return

        val apiKey = prefs.getString("api_key", null)
        if (apiKey.isNullOrEmpty()) {
            addMessage("Error: configura tu clave de Cerebras en Ajustes ⚙️.", false)
            return
        }

        val json = JSONObject().apply {
            // Modelo real de Cerebras (el que existe de verdad). Editable en el futuro.
            put("model", "gpt-oss-120b")
            val messagesArray = JSONArray()
            // Instrucción de sistema: enseña a la IA a devolver órdenes de control.
            val system = JSONObject()
            system.put("role", "system")
            system.put(
                "content",
                "Eres Fénix, un asistente que controla el móvil. Si el usuario " +
                "pide una acción en la pantalla, responde SOLO con una orden en una " +
                "de estas formas exactas: CLICK:x,y  |  WRITE:texto  |  READ  |  SCROLL. " +
                "Para todo lo demás, responde con normalidad como asistente."
            )
            messagesArray.put(system)
            for (msg in messages) {
                val obj = JSONObject()
                obj.put("role", if (msg.isUser) "user" else "assistant")
                obj.put("content", msg.text)
                messagesArray.put(obj)
            }
            put("messages", messagesArray)
        }

        val requestBody = json.toString()
            .toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://api.cerebras.ai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("AI", "Network error", e)
                runOnUiThread { addMessage("Error de red: ${e.message}", false) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val cuerpo = try { it.body?.string()?.take(200) } catch (e: Exception) { null }
                        runOnUiThread {
                            addMessage("Error del servidor: ${it.code}${if (!cuerpo.isNullOrBlank()) " - $cuerpo" else ""}", false)
                        }
                        return
                    }
                    val respBody = it.body?.string()
                    try {
                        val respJson = JSONObject(respBody ?: "")
                        val choices = respJson.getJSONArray("choices")
                        if (choices.length() > 0) {
                            val messageObj = choices.getJSONObject(0).getJSONObject("message")
                            val content = messageObj.getString("content").trim()
                            runOnUiThread {
                                // Si la IA devolvió una orden de control, la ejecutamos.
                                if (!tryLocalCommand(content)) {
                                    addMessage(content, false)
                                }
                            }
                        } else {
                            runOnUiThread { addMessage("Respuesta vacía.", false) }
                        }
                    } catch (e: Exception) {
                        Log.e("AI", "Parse error", e)
                        runOnUiThread { addMessage("Error al parsear respuesta.", false) }
                    }
                }
            }
        })
    }

    /**
     * Interpreta órdenes de control y las ejecuta a través del
     * AccessibilityService. Devuelve true si el texto era una orden.
     * Acepta formatos de la IA (CLICK:x,y) y lenguaje natural sencillo.
     */
    private fun tryLocalCommand(raw: String): Boolean {
        val service = FenixAccessibilityService.instance
        val texto = raw.trim()
        val lower = texto.lowercase()

        // Comprueba que el servicio esté activo antes de intentar controlar.
        fun requireService(): Boolean {
            if (service == null) {
                addMessage(
                    "El control de pantalla no está activo. Pulsa el botón de " +
                    "accesibilidad y actívalo en Ajustes de Android.", false
                )
                return false
            }
            return true
        }

        // CLICK:x,y  o  "pulsa en x,y" / "clic en x,y"
        val clickRegex = Regex("""(?:click:|pulsa\s+en\s+|clic\s+en\s+|toca\s+en\s+)\s*(\d+)\s*,\s*(\d+)""")
        clickRegex.find(lower)?.let { m ->
            if (!requireService()) return true
            val x = m.groupValues[1].toFloat()
            val y = m.groupValues[2].toFloat()
            service!!.clickAt(x, y)
            addMessage("👆 Pulsado en $x, $y", false)
            return true
        }

        // WRITE:texto  o  "escribe texto"
        val writeRegex = Regex("""(?:write:|escribe\s+)(.+)""", RegexOption.IGNORE_CASE)
        writeRegex.find(texto)?.let { m ->
            if (!requireService()) return true
            val t = m.groupValues[1].trim()
            service!!.inputText(t)
            addMessage("⌨️ Escrito: $t", false)
            return true
        }

        // READ  o  "lee pantalla" / "leer pantalla"
        if (lower == "read" || lower.contains("lee pantalla") || lower.contains("leer pantalla")) {
            if (!requireService()) return true
            val contenido = service!!.readScreenContent()
            addMessage(
                if (contenido.isBlank()) "No hay texto visible en la pantalla."
                else "📖 En la pantalla:\n$contenido", false
            )
            return true
        }

        // SCROLL  o  "desliza" / "scroll"
        if (lower == "scroll" || lower.contains("desliza") || lower.contains("desplaza")) {
            if (!requireService()) return true
            service!!.scrollForward()
            addMessage("↕️ Desplazado", false)
            return true
        }

        // ABRIR APP  o  "abre X" / "abrir X"  (no necesita accesibilidad)
        val openRegex = Regex("""(?:open:|abre\s+(?:el\s+|la\s+)?|abrir\s+(?:el\s+|la\s+)?)(.+)""", RegexOption.IGNORE_CASE)
        openRegex.find(texto)?.let { m ->
            val nombreApp = m.groupValues[1].trim()
            // "abre X en split" / "abre X en dos" / "abre X en pantalla dividida"
            // fuerza pantalla dividida; para el flujo de encuestas también la
            // activamos siempre, porque ahí conviene ver la app y Fénix a la vez.
            val quiereSplit = lower.contains("split") || lower.contains(" en dos") ||
                lower.contains("dos plano") || lower.contains("pantalla dividida") ||
                lower.contains("encuesta")
            if (abrirApp(nombreApp)) {
                addMessage("📲 Abriendo $nombreApp...", false)
                if (quiereSplit) {
                    // Pequeña espera para que la app termine de abrir antes
                    // de pedir el modo split; si no, Android puede ignorarlo.
                    binding.root.postDelayed({
                        val ok = FenixAccessibilityService.instance?.activarSplitScreen() ?: false
                        addMessage(
                            if (ok) "🔳 Activando pantalla dividida... si Android te pide elegir la segunda app, tócala tú."
                            else "⚠️ No pude activar la pantalla dividida (¿está encendido el control de pantalla en Ajustes?).",
                            false
                        )
                    }, 900)
                }
            } else {
                addMessage("No encontré la app \"$nombreApp\" instalada.", false)
            }
            return true
        }

        // CLICK POR TEXTO: "pulsa en Buscar" / "toca Aceptar" (no coordenadas)
        val clickTextRegex = Regex("""(?:pulsa\s+en\s+|clic\s+en\s+|toca\s+(?:en\s+)?|dale\s+a\s+)(?!\d+\s*,)(.+)""", RegexOption.IGNORE_CASE)
        clickTextRegex.find(texto)?.let { m ->
            if (!requireService()) return true
            val etiqueta = m.groupValues[1].trim()
            if (service!!.clickByText(etiqueta)) {
                addMessage("👆 Pulsado en \"$etiqueta\"", false)
            } else {
                addMessage("No encontré \"$etiqueta\" en la pantalla.", false)
            }
            return true
        }

        // BUSCAR: "busca gatos" → escribe en la caja de búsqueda y pulsa Enter
        val searchRegex = Regex("""busca[r]?\s+(.+)""", RegexOption.IGNORE_CASE)
        searchRegex.find(texto)?.let { m ->
            if (!requireService()) return true
            val consulta = m.groupValues[1].trim()
            if (service!!.writeInFirstField(consulta)) {
                // pequeña espera y pulsar "Intro" del teclado
                binding.root.postDelayed({
                    service!!.pressEnter()
                }, 400)
                addMessage("🔍 Buscando \"$consulta\"...", false)
            } else {
                addMessage("No encontré una caja de búsqueda en la pantalla.", false)
            }
            return true
        }

        return false
    }
     private fun abrirApp(nombre: String): Boolean {
        val n = nombre.lowercase()
        // Paquetes de apps muy comunes
        val conocidas = mapOf(
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "google" to "com.google.android.googlequicksearchbox",
            "camara" to "android.media.action.IMAGE_CAPTURE",
            "cámara" to "android.media.action.IMAGE_CAPTURE",
            "ajustes" to "android.settings.SETTINGS",
            "youtube music" to "com.google.android.apps.youtube.music"
        )

        // Caso especial: cámara y ajustes se abren por acción del sistema
        if (n.contains("camara") || n.contains("cámara")) {
            return try {
                startActivity(android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
                true
            } catch (e: Exception) { false }
        }
        if (n.contains("ajustes") || n.contains("configuracion") || n.contains("configuración")) {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)); return true
        }

        // Busca en la lista de conocidas
        val pkgConocido = conocidas.entries.firstOrNull { n.contains(it.key) }?.value
        if (pkgConocido != null) {
            val intent = packageManager.getLaunchIntentForPackage(pkgConocido)
            if (intent != null) { startActivity(intent); return true }
        }

        // Si no, busca cualquier app instalada cuyo nombre visible contenga el texto
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        for (app in apps) {
            val etiqueta = pm.getApplicationLabel(app).toString().lowercase()
            if (etiqueta.contains(n)) {
                val intent = pm.getLaunchIntentForPackage(app.packageName)
                if (intent != null) { startActivity(intent); return true }
            }
        }
        return false
    }

    data class Message(val text: String, val isUser: Boolean)

    inner class MessageAdapter(private val items: List<Message>) :
        RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

        inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textView: TextView = itemView as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val textView = TextView(parent.context).apply {
                setPadding(24, 16, 24, 16)
                textSize = 16f
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return MessageViewHolder(textView)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val message = items[position]
            val prefix = if (message.isUser) "🧑 " else "🔥 "
            holder.textView.text = prefix + message.text
        }

        override fun getItemCount(): Int = items.size
    }
}
