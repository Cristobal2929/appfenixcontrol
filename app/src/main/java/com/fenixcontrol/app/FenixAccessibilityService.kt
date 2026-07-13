package com.fenixcontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Servicio de accesibilidad que da a Fénix el control de la pantalla:
 * leer el contenido, pulsar en coordenadas, escribir texto y desplazar.
 *
 * El objeto `instance` permite que MainActivity (el chat) llame a estas
 * funciones. Se rellena cuando el usuario activa el servicio en los
 * Ajustes de Android, y se vacía cuando lo desactiva o se destruye.
 */
class FenixAccessibilityService : AccessibilityService() {

    companion object {
        // Referencia viva al servicio para que el chat pueda mandarle órdenes.
        var instance: FenixAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No hace falta procesar eventos en continuo para este uso.
    }

    // --- Detección de doble pulsación de SUBIR VOLUMEN ---
    private var ultimaPulsacionVol = 0L

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)
        // Solo reaccionamos al soltar la tecla de subir volumen
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_UP) {
            val ahora = System.currentTimeMillis()
            if (ahora - ultimaPulsacionVol < 500) {
                // Doble pulsación detectada (menos de medio segundo entre las dos)
                ultimaPulsacionVol = 0L
                abrirEscuchaVoz()
                return true  // consumimos el evento: no cambia el volumen en la 2ª
            }
            ultimaPulsacionVol = ahora
        }
        return super.onKeyEvent(event)
    }

    /** Abre la actividad de escucha por voz de Fénix por encima de todo. */
    private fun abrirEscuchaVoz() {
        val intent = Intent(this, VoiceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        // Nada que interrumpir.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    /** Lee todo el texto visible en la pantalla actual. */
    fun readScreenContent(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        traverseNode(rootNode, sb)
        return sb.toString().trim()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        node.text?.let { if (it.isNotBlank()) sb.append(it).append("\n") }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), sb)
        }
    }

    /** Pulsa (toque) en las coordenadas x, y de la pantalla. */
    fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Busca en la pantalla un elemento cuyo texto contenga `texto` y lo pulsa,
     * esté donde esté. Mucho más fiable que las coordenadas fijas: sirve para
     * "pulsa en Buscar", "pulsa en Aceptar", tocar un resultado, etc.
     * Devuelve true si encontró algo que pulsar.
     */
    fun clickByText(texto: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val objetivo = texto.trim().lowercase()
        val nodo = buscarNodoClicable(root, objetivo) ?: return false
        // Si el nodo en sí no es clicable, subimos al primer padre que lo sea
        var actual: AccessibilityNodeInfo? = nodo
        while (actual != null && !actual.isClickable) actual = actual.parent
        return (actual ?: nodo).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun buscarNodoClicable(node: AccessibilityNodeInfo?, objetivo: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (t.contains(objetivo) || d.contains(objetivo)) return node
        for (i in 0 until node.childCount) {
            val r = buscarNodoClicable(node.getChild(i), objetivo)
            if (r != null) return r
        }
        return null
    }

    /**
     * Enfoca el primer campo de texto editable de la pantalla y escribe.
     * Útil para buscadores: "abre Google y busca gatos" necesita meter el
     * texto en la caja de búsqueda aunque no esté enfocada aún.
     */
    fun writeInFirstField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val campo = buscarEditable(root) ?: return false
        campo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun buscarEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val r = buscarEditable(node.getChild(i))
            if (r != null) return r
        }
        return null
    }

    /** Escribe texto en el campo que tenga el foco de entrada. */
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

    /** Desplaza hacia delante el elemento desplazable que tenga el foco. */
    fun scrollForward() {
        val node = rootInActiveWindow ?: return
        val scrollable = findScrollable(node)
        scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val r = findScrollable(node.getChild(i))
            if (r != null) return r
        }
        return null
    }

    /**
     * Confirma la búsqueda en el campo editable (equivale a pulsar Intro).
     * Muchos buscadores aceptan la acción IME de "buscar" sobre el campo.
     */
    fun pressEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val campo = buscarEditable(root) ?: return false
        // ACTION_IME_ENTER existe desde API 30; usamos su valor por id de forma segura.
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            campo.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
        } else {
            campo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
}
