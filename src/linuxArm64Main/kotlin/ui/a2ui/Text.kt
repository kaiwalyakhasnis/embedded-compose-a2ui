package ui.a2ui

/** Markdown marks a model or agent may put in a Text: headings, bold, italics, code. */
internal fun plain(s: String): String = s.lines().joinToString("\n") { line ->
    line.replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^[-*]\\s+"), "\u2022 ").replace("**", "").replace("__", "").replace("`", "")
}
