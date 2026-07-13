**
package com.fenixcontrol.app

import okhttp3.MediaType

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.fenixcontrol.app.databinding.ActivityMainBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    }

    private fun addMessage(text: String, isUser: Boolean) {
        messages.add(Message(text, isUser))
        adapter.notifyItemInserted(messages.size - 1)
        binding.recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun sendMessageToAI(userMessage: String) {
        val apiKey = prefs.getString("api_key", null)
        if (apiKey.isNullOrEmpty()) {
            addMessage("Error: API key no configurada.", false)
            return
        }

        val json = JSONObject().apply {
            put("model", "cerebras/Cerebras-GPT-13B")
            val messagesArray = JSONArray()
            // Add previous messages
            for (msg in messages) {
                val obj = JSONObject()
                obj.put("role", if (msg.isUser) "user" else "assistant")
                obj.put("content", msg.text)
                messagesArray.put(obj)
            }
            // Add current user message
            val current = JSONObject()
            current.put("role", "user")
            current.put("content", userMessage)
            messagesArray.put(current)

            put("messages", messagesArray)
        }

        val requestBody = json.toString()
            .toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://api.cerebras.ai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("AI", "Network error", e)
                    runOnUiThread {
                        addMessage("Error de red: ${e.message}", false)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            runOnUiThread {
                                addMessage("Error del servidor: ${it.code}", false)
                            }
                            return
                        }
                        val respBody = it.body?.string()
                        try {
                            val respJson = JSONObject(respBody)
                            val choices = respJson.getJSONArray("choices")
                            if (choices.length() > 0) {
                                val messageObj = choices.getJSONObject(0).getJSONObject("message")
                                val content = messageObj.getString("content")
                                runOnUiThread {
                                    addMessage(content.trim(), false)
                                }
                            } else {
                                runOnUiThread {
                                    addMessage("Respuesta vacía.", false)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AI", "Parse error", e)
                            runOnUiThread {
                                addMessage("Error al parsear respuesta.", false)
                            }
                        }
                    }
                }
            })
        }
    }

    // ---------- Data classes ----------
    data class Message(val text: String, val isUser: Boolean)

    // ---------- RecyclerView Adapter ----------
    inner class MessageAdapter(private val items: List<Message>) :
        RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

        inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textView: TextView = itemView.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val textView = TextView(parent.context).apply {
                id = android.R.id.text1
                setPadding(16)
                setTextColor(Color.WHITE)
                textSize = 16f
                maxWidth = 600
            }
            val layout = ConstraintLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(textView)
            }
            return MessageViewHolder(layout)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val message = items[position]
            holder.textView.text = message.text
            val params = holder.textView.layoutParams as ConstraintLayout.LayoutParams
            if (message.isUser) {
                holder.textView.setBackgroundColor(Color.parseColor("#6200EE")) // purple_500
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.startToStart = ConstraintLayout.LayoutParams.UNSET
                holder.textView.gravity = Gravity.END
            } else {
                holder.textView.setBackgroundColor(Color.parseColor("#03DAC5")) // teal_200
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET
                holder.textView.gravity = Gravity.START
            }
            holder.textView.layoutParams = params
        }

        override fun getItemCount(): Int = items.size
    }

    // ---------- Settings Activity ----------
    class SettingsActivity : AppCompatActivity() {

        private lateinit var editApiKey: EditText
        private lateinit var buttonSave: Button
        private val prefs: SharedPreferences by lazy {
            getSharedPreferences("fenix_prefs", Context.MODE_PRIVATE)
        }

        @SuppressLint("SetTextI18n")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            // Build layout programmatically
            val root = ConstraintLayout(this).apply {
                id = View.generateViewId()
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setPadding(32)
            }

            val inputLayout = TextInputLayout(this).apply {
                id = View.generateViewId()
                hint = getString(R.string.hint_api_key)
                layoutParams = ConstraintLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
            }

            editApiKey = TextInputEditText(this).apply {
                id = View.generateViewId()
                setText(prefs.getString("api_key", ""))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            inputLayout.addView(editApiKey)

            buttonSave = MaterialButton(this).apply {
                id = View.generateViewId()
                text = getString(R.string.save)
                layoutParams = ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topToBottom = inputLayout.id
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    topMargin = 24
                }
            }

            root.addView(inputLayout)
            root.addView(buttonSave)

            setContentView(root)

            buttonSave.setOnClickListener {
                val key = editApiKey.text?.toString()?.trim()
                prefs.edit().putString("api_key", key).apply()
                finish()
            }
        }
    }

    // ---------- Accessibility Service ----------
    class MyAccessibilityService : AccessibilityService() {

        private val handler = Handler(Looper.getMainLooper())

        override fun onServiceConnected() {
            super.onServiceConnected()
            // Service configuration can be set via XML (not shown here)
        }

        override fun onAccessibilityEvent(event: AccessibilityEvent?) {
            // No continuous processing needed for this demo
        }

        override fun onInterrupt() {
            // Handle interruption
        }

        // Example: read current screen content
        fun readScreenContent(): String {
            val rootNode = rootInActiveWindow ?: return ""
            val sb = StringBuilder()
            traverseNode(rootNode, sb)
            return sb.toString()
        }

        private fun traverseNode(node: AccessibilityNodeInfo, sb: StringBuilder) {
            if (node.text != null) {
                sb.append(node.text).append("\n")
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseNode(child, sb)
                child.recycle()
            }
        }

        // Click at coordinates
        fun clickAt(x: Float, y: Float) {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
        }

        // Input text into focused node
        fun inputText(text: String) {
            val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        // Scroll forward
        fun scrollForward() {
            val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
    }
}

**