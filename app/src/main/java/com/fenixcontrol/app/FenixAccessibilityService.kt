package com.fenixcontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Bundle
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
}
