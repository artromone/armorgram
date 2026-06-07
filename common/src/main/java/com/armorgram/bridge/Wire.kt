package com.armorgram.bridge

/**
 * Wire format mirror of the Go backend's internal/wire/frame.go.
 *
 * One SMS == one Frame.
 *
 *   #SEQ[.IDX/TOTAL] KIND BODY
 *
 * KIND ∈ { msg, new, ack, sys, cmd, resend }.
 * MSG bodies carry one or more lines: ">alias[|sender]: text".
 */
object Wire {

    enum class Kind(val tag: String) {
        MSG("msg"), NEW("new"), ACK("ack"), SYS("sys"), CMD("cmd"), RESEND("resend");

        companion object {
            fun parse(s: String): Kind? = values().firstOrNull { it.tag == s }
        }
    }

    data class Frame(
        val seq: Long,
        val idx: Int = 0,
        val total: Int = 0,
        val kind: Kind,
        val body: String
    )

    data class MsgLine(
        val alias: String,
        val sender: String = "",
        val text: String
    )

    private val headerRe = Regex("""^#(\d+)(?:\.(\d+)/(\d+))?\s+(\S+)\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val msgLineRe = Regex("""^>([a-z0-9]+)(?:\|([^:]+))?:\s?(.*)$""")

    fun encode(f: Frame): String {
        val sb = StringBuilder()
        sb.append('#').append(f.seq)
        if (f.total > 1) sb.append('.').append(f.idx).append('/').append(f.total)
        sb.append(' ').append(f.kind.tag).append(' ').append(f.body)
        return sb.toString()
    }

    fun decode(s: String): Frame? {
        val m = headerRe.matchEntire(s.trim()) ?: return null
        val seq = m.groupValues[1].toLongOrNull() ?: return null
        val idx = m.groupValues[2].toIntOrNull() ?: 0
        val total = m.groupValues[3].toIntOrNull() ?: 0
        val kind = Kind.parse(m.groupValues[4]) ?: return null
        return Frame(seq, idx, total, kind, m.groupValues[5])
    }

    fun encodeMsgLines(lines: List<MsgLine>): String = lines.joinToString("\n") { l ->
        buildString {
            append('>')
            append(l.alias)
            if (l.sender.isNotEmpty()) append('|').append(l.sender)
            append(": ").append(l.text)
        }
    }

    fun decodeMsgLines(body: String): List<MsgLine> =
        body.split('\n').mapNotNull { ln ->
            val m = msgLineRe.matchEntire(ln) ?: return@mapNotNull null
            MsgLine(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }

    fun chunk(body: String, maxLen: Int): List<String> {
        if (body.length <= maxLen) return listOf(body)
        val out = mutableListOf<String>()
        var rest = body
        while (rest.length > maxLen) {
            out += rest.substring(0, maxLen)
            rest = rest.substring(maxLen)
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Header byte budget (worst case w/ chunk markers). */
    fun headerOverhead(kind: Kind): Int = 4 + kind.tag.length + 12
}
