package dev.mrsurge.electromux.host

import android.content.Context
import android.graphics.Color
import android.view.Menu
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Reusable native presentation of executed Electron templates, not JS source parsing. */
class ElectronMenuChrome(context: Context, policy: JSONObject, private val select: (String) -> Unit) : LinearLayout(context) {
    private val title = TextView(context)
    private val button = Button(context)
    private var items = JSONArray()
    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.rgb(39, 42, 47))
        setPadding(12, 0, 8, 0)
        title.setTextColor(Color.WHITE); title.textSize = 16f; title.text = "Electromux"
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        button.text = "☰"; button.contentDescription = "Application menu"
        addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        visibility = if (policy.getBoolean("titleBar") || policy.getBoolean("menuBar")) View.VISIBLE else View.GONE
        title.visibility = if (policy.getBoolean("titleBar")) View.VISIBLE else View.GONE
        button.visibility = if (policy.getBoolean("menuBar")) View.VISIBLE else View.GONE
        button.setOnClickListener { showMenu() }
    }
    fun setWindowTitle(value: String) { title.text = value }
    fun setItems(value: JSONArray) { items = JSONArray(value.toString()) }
    private fun showMenu() {
        val popup = PopupMenu(context, button)
        val actions = mutableMapOf<Int, String>()
        var nextId = 1
        fun populate(menu: Menu, array: JSONArray, depth: Int) {
            check(depth <= 8)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                if (!item.getBoolean("visible") || item.getString("type") == "separator") continue
                val children = item.getJSONArray("submenu")
                val reason = item.optString("disabledReason").takeIf { it != "null" && it.isNotEmpty() }
                val label = item.getString("label") + (reason?.let { " — $it" } ?: "")
                val id = nextId++
                check(id <= 256)
                val view = if (children.length() > 0) {
                    menu.addSubMenu(0, id, index, label).also { populate(it, children, depth + 1) }.item
                } else menu.add(0, id, index, label).also { actions[id] = item.getString("id") }
                view.isEnabled = item.getBoolean("enabled")
            }
        }
        populate(popup.menu, items, 0)
        popup.setOnMenuItemClickListener { item ->
            actions[item.itemId]?.let { select(it); true } ?: false
        }
        popup.show()
    }
}
