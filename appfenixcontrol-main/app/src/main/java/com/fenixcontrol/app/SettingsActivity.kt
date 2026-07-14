package com.fenixcontrol.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * Pantalla de ajustes: guarda la clave API de Cerebras en SharedPreferences.
 * Layout construido en código para no depender de un XML aparte.
 */
class SettingsActivity : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("fenix_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val editApiKey = EditText(this).apply {
            hint = getString(R.string.hint_api_key)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("api_key", ""))
        }

        val buttonSave = Button(this).apply {
            text = getString(R.string.save)
        }

        root.addView(editApiKey)
        root.addView(buttonSave)
        setContentView(root)

        buttonSave.setOnClickListener {
            val key = editApiKey.text?.toString()?.trim()
            prefs.edit().putString("api_key", key).apply()
            finish()
        }
    }
}
