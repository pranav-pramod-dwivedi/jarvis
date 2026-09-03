package com.pr4nav.jarvis.tools.catalog

import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema

object CalculationConverterTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "calc_math_evaluate",
            description = "Evaluates an arithmetic mathematical expression.",
            argumentSchema = schema(
                prop("expression", "string", "Math expression (e.g. '24 * 15', '144 / 12', '50 + 75')"),
                required = listOf("expression")
            ),
            execute = { _, args ->
                val expr = args.optString("expression", "")
                val clean = expr.replace("x", "*").replace("÷", "/")
                val res = evaluateSimpleMath(clean)
                ok("🧮 $expr = $res.", mapOf("result" to res))
            }
        ))

        reg(CanonicalToolDef(
            name = "calc_tip_calculate",
            description = "Calculates tip amount and bill split per person.",
            argumentSchema = schema(
                prop("billAmount", "number", "Total bill amount"),
                prop("tipPercent", "number", "Tip percentage (default 15)"),
                prop("splitPeople", "integer", "Number of people splitting (default 1)"),
                required = listOf("billAmount")
            ),
            execute = { _, args ->
                val bill = args.optDouble("billAmount", 100.0)
                val tipPct = args.optDouble("tipPercent", 15.0)
                val people = args.optInt("splitPeople", 1).coerceAtLeast(1)
                val tip = bill * (tipPct / 100.0)
                val total = bill + tip
                val perPerson = total / people
                val msg = "💵 Tip: %.2f (%.0f%%) | Total: %.2f | Per Person: %.2f".format(tip, tipPct, total, perPerson)
                ok(msg, mapOf("total" to total, "perPerson" to perPerson))
            }
        ))

        reg(CanonicalToolDef(
            name = "calc_unit_convert",
            description = "Converts units of measurement (e.g. km to miles, kg to lbs, celsius to fahrenheit).",
            argumentSchema = schema(
                prop("value", "number", "Value to convert"),
                prop("fromUnit", "string", "Source unit ('km', 'miles', 'kg', 'lbs', 'c', 'f')"),
                prop("toUnit", "string", "Target unit ('km', 'miles', 'kg', 'lbs', 'c', 'f')"),
                required = listOf("value", "fromUnit", "toUnit")
            ),
            execute = { _, args ->
                val v = args.optDouble("value", 1.0)
                val from = args.optString("fromUnit", "").lowercase()
                val to = args.optString("toUnit", "").lowercase()
                val converted = when {
                    from == "km" && to == "miles" -> v * 0.621371
                    from == "miles" && to == "km" -> v * 1.60934
                    from == "kg" && to == "lbs" -> v * 2.20462
                    from == "lbs" && to == "kg" -> v / 2.20462
                    from == "c" && to == "f" -> (v * 9.0 / 5.0) + 32.0
                    from == "f" && to == "c" -> (v - 32.0) * 5.0 / 9.0
                    else -> v
                }
                ok("📏 $v $from = %.2f $to.".format(converted), mapOf("converted" to converted))
            }
        ))
    }

    private fun evaluateSimpleMath(expr: String): String {
        val tokens = expr.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.size == 3) {
            val a = tokens[0].toDoubleOrNull() ?: return "error"
            val op = tokens[1]
            val b = tokens[2].toDoubleOrNull() ?: return "error"
            val res = when (op) {
                "+" -> a + b
                "-" -> a - b
                "*" -> a * b
                "/" -> if (b != 0.0) a / b else return "divide by zero"
                "%" -> a % b
                else -> return "unknown operator"
            }
            return if (res % 1.0 == 0.0) res.toLong().toString() else "%.2f".format(res)
        }
        return expr
    }
}
