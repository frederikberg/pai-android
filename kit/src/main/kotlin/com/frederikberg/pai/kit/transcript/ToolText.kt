package com.frederikberg.pai.kit.transcript

/** Small text rules a tool's name, its output and the paths it mentions are shown with. */
object ToolText {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val LINE_NUMBER = Regex("^\\s*\\d+→(.*)$")

    /** `mcp__server__tool` reads `server: tool`; any other name is shown as it is. */
    fun toolName(name: String): String {
        if (!name.startsWith("mcp__")) return name
        val parts = name.removePrefix("mcp__").split("__")
        return if (parts.size >= 2) "${parts[0]}: ${parts.drop(1).joinToString(".")}" else name
    }

    /** Colour in output would compete with the one red signal worth finding, and a slice could cut an escape in half. */
    fun stripAnsi(text: String): String = if ('\u001B' in text) ANSI.replace(text, "") else text

    /** A `Read` result is shown without the `  141→` prefixes. */
    fun stripLineNumbers(text: String): String =
        text.split('\n').joinToString("\n") { line -> LINE_NUMBER.matchEntire(line)?.groupValues?.get(1) ?: line }

    /** A path under a home directory the session's own machine wrote, shortened the way a shell prompt does. */
    fun abbreviatingHome(path: String): String {
        for (root in listOf("/home/", "/Users/")) {
            if (!path.startsWith(root)) continue
            val rest = path.removePrefix(root)
            val slash = rest.indexOf('/')
            return if (slash < 0) "~" else "~" + rest.substring(slash)
        }
        return path
    }

    fun firstLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}
