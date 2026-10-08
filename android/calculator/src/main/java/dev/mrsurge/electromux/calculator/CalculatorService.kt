package dev.mrsurge.electromux.calculator

import dev.mrsurge.electromux.node.EmbeddedConsumerSpec
import dev.mrsurge.electromux.node.EmbeddedElectronService
import org.json.JSONArray

class CalculatorService : EmbeddedElectronService() {
    override val consumer by lazy {
        val inventory = JSONArray(assets.open("calculator-resources.json").bufferedReader().use { it.readText() })
        val resources = (0 until inventory.length()).map { inventory.getString(it) }.toSet()
        EmbeddedConsumerSpec("embedded_node/calculator.mjs", setOf("electron.start", "electron.menu.select"),
            setOf("electron.effect"), resources)
    }
}
