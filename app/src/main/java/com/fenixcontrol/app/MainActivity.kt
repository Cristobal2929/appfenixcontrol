package com.fenixcontrol.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
                addMessage(text, true)
                binding.editTextMessage.text?.clear()
                sendMessageToAI(text)
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

        // Mensaje de bienvenida con instrucciones
        addMessage(
            "Soy Fénix. Configura tu clave de Cerebras en Ajustes ⚙️ y activa el " +
            "control de pantalla con el botón de accesibilidad. Luego pídeme cosas " +
            "como: leer pantalla, pulsa en 500,800, escribe hola, o desliza.",
            false
        )
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
                        runOnUiThread { addMessage("Error del servidor: ${it.code}", false) }
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
