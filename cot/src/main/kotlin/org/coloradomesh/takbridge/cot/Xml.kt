package org.coloradomesh.takbridge.cot

/** Escape text for use in XML character data or a double-quoted attribute value. */
fun escapeXml(text: String): String {
    val out = StringBuilder(text.length + 16)
    for (ch in text) {
        when (ch) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&apos;")
            else -> out.append(ch)
        }
    }
    return out.toString()
}

private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]{1,6}|[0-9]{1,7});")

/** Reverse [escapeXml], and resolve numeric character references. Unknown entities are left as is. */
fun unescapeXml(text: String): String {
    if (!text.contains('&')) return text
    val numeric = NUMERIC_ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        val code = if (body.startsWith("x")) body.substring(1).toIntOrNull(16) else body.toIntOrNull()
        if (code != null && code > 0 && Character.isValidCodePoint(code)) String(Character.toChars(code)) else ""
    }
    return numeric
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
