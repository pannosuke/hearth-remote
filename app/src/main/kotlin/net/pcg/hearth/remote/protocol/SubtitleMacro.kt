package net.pcg.hearth.remote.protocol

/**
 * Steps the subtitle button sends on Sharp IP control.
 *
 * The default is one key, IRCO 0267. A saved macro can be several lines,
 * so a menu routine can be entered once it is known:
 *
 * IRCO 0267
 * WAIT 400
 * IRCO 01E4
 *
 * WAIT pauses in milliseconds and does not use the socket. Any other
 * 4-letter command is sent as a Sharp command with a parameter of at most
 * 4 characters. A blank value, or a value that is not a sequence, runs the
 * default single press.
 */
internal data class MacroStep(val command: String, val param: String)

internal object SubtitleMacro {
    const val DEFAULT = "IRCO 0267"
    private const val MAX_WAIT_MS = 10_000L

    fun parse(text: String?): List<MacroStep> {
        val source = text?.trim().orEmpty().ifEmpty { DEFAULT }
        val steps = mutableListOf<MacroStep>()
        for (raw in source.split('\n', ',')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val step = parseLine(line) ?: return defaultSteps()
            steps.add(step)
        }
        return steps.ifEmpty { defaultSteps() }
    }

    private fun defaultSteps(): List<MacroStep> = listOf(MacroStep("IRCO", SharpIpCommands.SUBTITLE))

    private fun parseLine(line: String): MacroStep? {
        val compact = line.replace(" ", "").replace("\t", "")
        if (compact.length < 4) return null
        val command = compact.take(4).uppercase()
        val rest = compact.drop(4)
        if (command == "WAIT") {
            val ms = rest.toLongOrNull() ?: return null
            if (ms !in 0..MAX_WAIT_MS) return null
            return MacroStep("WAIT", ms.toString())
        }
        if (command.any { !it.isLetter() } || rest.length > 4) return null
        return MacroStep(command, rest)
    }
}
