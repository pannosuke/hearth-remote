package net.pcg.hearth.remote.protocol

/**
 * Steps a terrestrial subtitle button sends on Sharp IP control.
 *
 * Subtitle opens a drawer already focused on Display Style. Enter opens
 * the options. On moves down once to the guessed On Screen row. Off moves
 * up once to the guessed Off row. Enter confirms, then Back closes the
 * drawer. Both sequences are editable:
 *
 * IRCO 0267
 * WAIT 900
 * IRCO 0152
 *
 * WAIT pauses in milliseconds and does not use the socket. Any other
 * 4-letter command is sent as a Sharp command with a parameter of at most
 * 4 characters. A blank value, or a value that is not a sequence, runs
 * [fallback].
 */
internal data class MacroStep(val command: String, val param: String)

internal object SubtitleMacro {
    val ON: String = sequence(
        "IRCO ${SharpIpCommands.SUBTITLE}",
        "WAIT 900",
        "IRCO ${SharpIpCommands.ENTER}",
        "WAIT 600",
        "IRCO ${SharpIpCommands.DOWN}",
        "IRCO ${SharpIpCommands.ENTER}",
        "WAIT 500",
        "IRCO ${SharpIpCommands.BACK}"
    )
    val OFF: String = sequence(
        "IRCO ${SharpIpCommands.SUBTITLE}",
        "WAIT 900",
        "IRCO ${SharpIpCommands.ENTER}",
        "WAIT 600",
        "IRCO ${SharpIpCommands.UP}",
        "IRCO ${SharpIpCommands.ENTER}",
        "WAIT 500",
        "IRCO ${SharpIpCommands.BACK}"
    )
    private const val MAX_WAIT_MS = 10_000L

    fun parse(text: String?, fallback: String = ON): List<MacroStep> {
        parseLines(text)?.let { return it }
        if (text?.trim() != fallback.trim()) {
            parseLines(fallback)?.let { return it }
        }
        return parseLines(ON).orEmpty()
    }

    /** The v0.2.0 one-key macro. A saved copy of it is not a tuned routine. */
    fun isLegacySinglePress(text: String): Boolean {
        val compact = text.trim().replace(" ", "").replace("\t", "")
        return compact.equals("IRCO${SharpIpCommands.SUBTITLE}", ignoreCase = true)
    }

    private fun sequence(vararg lines: String): String = lines.joinToString("\n")

    private fun parseLines(text: String?): List<MacroStep>? {
        val source = text?.trim().orEmpty()
        if (source.isEmpty()) return null
        val steps = mutableListOf<MacroStep>()
        for (raw in source.split('\n', ',')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val step = parseLine(line) ?: return null
            steps.add(step)
        }
        return steps.takeIf { it.isNotEmpty() }
    }

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
