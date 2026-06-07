package com.armorgram.bridge

import android.provider.Telephony.Sms
import android.telephony.SmsManager
import com.armorgram.manager.KeyManager
import com.armorgram.model.Conversation
import com.armorgram.model.Message
import com.armorgram.model.Recipient
import com.armorgram.util.Preferences
import com.klinker.android.send_message.SmsManagerFactory
import io.realm.Realm
import io.realm.RealmList
import timber.log.Timber
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the SMS-over-wire bridge state on the Android side.
 *
 * Virtual threadIds are negative 48-bit hashes of (gatewayPhone, alias) so they
 * cannot collide with positive Android TelephonyProvider thread ids. Virtual
 * conversations and messages live in Realm only — Android's SMS content provider
 * is bypassed entirely for bridged traffic.
 *
 * Cross-frame state (seq dedup, multipart chunk reassembly) is kept in memory.
 * Persisting across process restarts is intentionally skipped: backend can
 * resend on demand via the `/resend` command.
 */
@Singleton
class BridgeRepositoryImpl @Inject constructor(
    private val prefs: Preferences,
    private val messageIds: KeyManager
) : BridgeRepository {

    // ─── public state used by routing ───────────────────────────────────────

    /** Reserved alias used as a system chat for non-message frames (NEW/SYS/ACK
     *  carbons, plus anything from the gateway that doesn't decode as a frame). */
    private val sysAlias = "_sys"

    /** LRU of recently-seen inbound seqs (dedup window). Drop dups silently. */
    private val seenSeqs = ArrayDeque<Long>(SEEN_SEQS_LIMIT)
    private val seenSeqsSet = HashSet<Long>(SEEN_SEQS_LIMIT)
    private val seenLock = Any()

    /** Per-seqBase buffer for chunked frames. seqBase = seq - idx + 1. */
    private val chunkBuf = HashMap<Long, ChunkAccum>()
    private val chunkLock = Any()

    private data class ChunkAccum(
        val total: Int,
        val kind: Wire.Kind,
        val firstSentTime: Long,
        val firstSubId: Int,
        val parts: Array<String?>,
        var arrived: Int = 0
    )

    // ─── identity / config ──────────────────────────────────────────────────

    override fun isGateway(address: String): Boolean {
        if (!prefs.bridgeEnabled.get()) return false
        val gw = prefs.bridgeGatewayPhone.get().normalizePhone()
        if (gw.isEmpty()) return false
        return address.normalizePhone() == gw
    }

    override fun isVirtualThread(threadId: Long): Boolean = threadId < 0L

    override fun virtualThreadId(alias: String): Long {
        val key = prefs.bridgeGatewayPhone.get().normalizePhone() + "|" + alias.toLowerCase()
        // FNV-1a 64-bit; mask to 48 bits then negate so positive Android ids never collide.
        var h = -0x340d631b7bdddcdbL
        for (c in key) {
            h = h xor c.toLong()
            h *= 0x100000001b3L
        }
        val masked = h and 0x0000_FFFF_FFFF_FFFFL
        return -masked
    }

    override fun aliasFor(virtualThreadId: Long): String? {
        if (!isVirtualThread(virtualThreadId)) return null
        return Realm.getDefaultInstance().use { realm ->
            realm.where(Conversation::class.java)
                .equalTo("id", virtualThreadId)
                .findFirst()
                ?.recipients
                ?.firstOrNull()
                ?.address
        }
    }

    // ─── inbound ────────────────────────────────────────────────────────────

    override fun routeIncoming(subId: Int, body: String, sentTime: Long): Boolean {
        // Non-frame SMS from the gateway falls through to the normal QKSMS receive
        // path so it lands in the real gateway thread and is visible like any
        // regular SMS. We only consume frames we recognize.
        val frame = Wire.decode(body) ?: return false
        if (!noteSeqFresh(frame.seq)) {
            Timber.d("bridge: drop dup seq=${frame.seq}")
            return true
        }
        // Chunked? Buffer until complete.
        if (frame.total > 1) {
            val ready = bufferChunk(subId, sentTime, frame) ?: return true
            dispatchFrame(ready.subId, ready.frame, ready.sentTime)
            return true
        }
        dispatchFrame(subId, frame, sentTime)
        return true
    }

    private fun dispatchFrame(subId: Int, frame: Wire.Frame, sentTime: Long) {
        when (frame.kind) {
            Wire.Kind.MSG -> {
                val lines = Wire.decodeMsgLines(frame.body)
                if (lines.isEmpty()) {
                    insertVirtualIncoming(subId, sysAlias, "[malformed-msg] ${frame.body}", sentTime, frame.seq)
                    return
                }
                lines.forEach { line ->
                    val display = if (line.sender.isNotEmpty()) "${line.sender}: ${line.text}" else line.text
                    insertVirtualIncoming(subId, line.alias, display, sentTime, frame.seq)
                }
            }
            Wire.Kind.NEW -> {
                val parsed = NewFrame.parse(frame.body)
                if (parsed != null) {
                    val name = parsed.username.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: parsed.alias
                    upsertVirtualConversation(parsed.alias, name)
                    insertVirtualIncoming(subId, parsed.alias,
                        "[new chat] ${parsed.preview}", sentTime, frame.seq)
                    insertVirtualIncoming(subId, sysAlias,
                        "?NEW ${parsed.alias} $name ${parsed.preview}", sentTime, frame.seq)
                } else {
                    insertVirtualIncoming(subId, sysAlias, "?NEW ${frame.body}", sentTime, frame.seq)
                }
            }
            Wire.Kind.SYS -> insertVirtualIncoming(subId, sysAlias, "sys ${frame.body}", sentTime, frame.seq)
            Wire.Kind.ACK -> insertVirtualIncoming(subId, sysAlias, "ack ${frame.body}", sentTime, frame.seq)
            else -> Timber.w("ignoring inbound frame kind=${frame.kind}")
        }
    }

    private fun noteSeqFresh(seq: Long): Boolean = synchronized(seenLock) {
        if (seenSeqsSet.contains(seq)) return false
        seenSeqsSet.add(seq)
        seenSeqs.addLast(seq)
        while (seenSeqs.size > SEEN_SEQS_LIMIT) {
            seenSeqsSet.remove(seenSeqs.removeFirst())
        }
        true
    }

    private data class ReadyFrame(val subId: Int, val frame: Wire.Frame, val sentTime: Long)

    private fun bufferChunk(subId: Int, sentTime: Long, f: Wire.Frame): ReadyFrame? = synchronized(chunkLock) {
        if (f.idx < 1 || f.idx > f.total) {
            Timber.w("bridge: malformed chunk idx=${f.idx}/${f.total} seq=${f.seq}")
            return null
        }
        val seqBase = f.seq - f.idx + 1
        val acc = chunkBuf.getOrPut(seqBase) {
            // bound the cache so a malicious sender can't OOM us
            while (chunkBuf.size >= CHUNK_BUFFER_LIMIT) {
                val firstKey = chunkBuf.keys.iterator().next()
                chunkBuf.remove(firstKey)
            }
            ChunkAccum(
                total = f.total,
                kind = f.kind,
                firstSentTime = sentTime,
                firstSubId = subId,
                parts = arrayOfNulls(f.total)
            )
        }
        if (acc.parts[f.idx - 1] == null) {
            acc.parts[f.idx - 1] = f.body
            acc.arrived++
        }
        if (acc.arrived < acc.total) return null
        chunkBuf.remove(seqBase)
        val joined = acc.parts.joinToString("") { it ?: "" }
        return ReadyFrame(acc.firstSubId, Wire.Frame(seqBase, 0, 0, acc.kind, joined), acc.firstSentTime)
    }

    // ─── outbound ───────────────────────────────────────────────────────────

    override fun encodeOutgoing(virtualThreadId: Long, text: String): String? {
        val alias = aliasFor(virtualThreadId) ?: return null
        val seq = nextOutSeq()
        val frame = Wire.Frame(
            seq = seq,
            kind = Wire.Kind.MSG,
            body = Wire.encodeMsgLines(listOf(Wire.MsgLine(alias = alias, text = text)))
        )
        return Wire.encode(frame)
    }

    override fun sendVirtual(subId: Int, virtualThreadId: Long, text: String): Boolean {
        if (!isVirtualThread(virtualThreadId)) return false
        val gateway = prefs.bridgeGatewayPhone.get()
        if (gateway.isEmpty()) return false
        val encoded = encodeOutgoing(virtualThreadId, text) ?: return false
        val alias = aliasFor(virtualThreadId) ?: return false

        // Persist as outbound virtual message in Realm.
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val convo = r.where(Conversation::class.java).equalTo("id", virtualThreadId).findFirst()
                    ?: return@executeTransaction
                val msg = r.createObject(Message::class.java, messageIds.newId())
                msg.threadId = virtualThreadId
                msg.address = alias
                msg.body = text
                msg.date = System.currentTimeMillis()
                msg.subId = subId
                msg.boxId = Sms.MESSAGE_TYPE_OUTBOX
                msg.type = "sms"
                msg.read = true
                msg.seen = true
                convo.lastMessage = msg
            }
        }

        return dispatchSms(subId, gateway, encoded, "thread=$virtualThreadId alias=$alias")
    }

    override fun sendCommand(subId: Int, command: String): Boolean {
        val gateway = prefs.bridgeGatewayPhone.get()
        if (gateway.isEmpty()) return false
        val cmd = command.trim().let { if (it.startsWith("/")) it else "/$it" }
        val seq = nextOutSeq()
        val frame = Wire.Frame(seq = seq, kind = Wire.Kind.CMD, body = cmd)
        val encoded = Wire.encode(frame)

        // Echo into _sys so the user sees what was sent.
        insertVirtualOutgoing(subId, sysAlias, "cmd $cmd", seq)

        return dispatchSms(subId, gateway, encoded, "cmd=$cmd seq=$seq")
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun dispatchSms(subId: Int, to: String, body: String, ctx: String): Boolean {
        try {
            val smsManager = subId.takeIf { it != -1 }
                ?.let(SmsManagerFactory::createSmsManager)
                ?: SmsManager.getDefault()
            val parts = smsManager.divideMessage(body) ?: arrayListOf(body)
            if (parts.size == 1) {
                smsManager.sendTextMessage(to, null, body, null, null)
            } else {
                smsManager.sendMultipartTextMessage(to, null, parts, null, null)
            }
        } catch (t: Throwable) {
            Timber.e(t, "bridge dispatch failed ($ctx)")
            return false
        }
        Timber.i("bridge dispatch: $ctx bytes=${body.length}")
        return true
    }

    private fun nextOutSeq(): Long {
        val pref = prefs.bridgeOutSeq
        val next = pref.get() + 1
        pref.set(next)
        return next
    }

    private fun upsertVirtualConversation(alias: String, displayName: String) {
        val threadId = virtualThreadId(alias)
        val recipientId = stableRecipientId(threadId)
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                var recipient = r.where(Recipient::class.java).equalTo("id", recipientId).findFirst()
                if (recipient == null) {
                    recipient = r.createObject(Recipient::class.java, recipientId)
                    recipient.address = alias
                }
                var convo = r.where(Conversation::class.java).equalTo("id", threadId).findFirst()
                if (convo == null) {
                    convo = r.createObject(Conversation::class.java, threadId)
                    convo!!.recipients = RealmList(recipient!!)
                }
                if (displayName.isNotBlank()) {
                    convo!!.name = displayName
                }
            }
        }
    }

    private fun insertVirtualIncoming(subId: Int, alias: String, text: String, sentTime: Long, frameSeq: Long) {
        val threadId = virtualThreadId(alias)
        val recipientId = stableRecipientId(threadId)
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                var recipient = r.where(Recipient::class.java).equalTo("id", recipientId).findFirst()
                if (recipient == null) {
                    recipient = r.createObject(Recipient::class.java, recipientId)
                    recipient.address = alias
                }
                var convo = r.where(Conversation::class.java).equalTo("id", threadId).findFirst()
                if (convo == null) {
                    convo = r.createObject(Conversation::class.java, threadId)
                    convo!!.recipients = RealmList(recipient!!)
                }
                val msg = r.createObject(Message::class.java, messageIds.newId())
                msg.threadId = threadId
                msg.address = alias
                msg.body = text
                msg.date = System.currentTimeMillis()
                msg.dateSent = sentTime
                msg.subId = subId
                msg.boxId = Sms.MESSAGE_TYPE_INBOX
                msg.type = "sms"
                msg.read = false
                msg.seen = false
                convo!!.lastMessage = msg
            }
        }
        Timber.i("bridge inbound: thread=$threadId alias=$alias seq=$frameSeq")
    }

    private fun insertVirtualOutgoing(subId: Int, alias: String, text: String, frameSeq: Long) {
        val threadId = virtualThreadId(alias)
        val recipientId = stableRecipientId(threadId)
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                var recipient = r.where(Recipient::class.java).equalTo("id", recipientId).findFirst()
                if (recipient == null) {
                    recipient = r.createObject(Recipient::class.java, recipientId)
                    recipient.address = alias
                }
                var convo = r.where(Conversation::class.java).equalTo("id", threadId).findFirst()
                if (convo == null) {
                    convo = r.createObject(Conversation::class.java, threadId)
                    convo!!.recipients = RealmList(recipient!!)
                }
                val msg = r.createObject(Message::class.java, messageIds.newId())
                msg.threadId = threadId
                msg.address = alias
                msg.body = text
                msg.date = System.currentTimeMillis()
                msg.subId = subId
                msg.boxId = Sms.MESSAGE_TYPE_OUTBOX
                msg.type = "sms"
                msg.read = true
                msg.seen = true
                convo!!.lastMessage = msg
            }
        }
        Timber.i("bridge outbound-echo: thread=$threadId alias=$alias seq=$frameSeq")
    }

    private fun stableRecipientId(threadId: Long): Long =
        -(threadId xor 0x5A5A5A5AL) and Long.MAX_VALUE

    companion object {
        private const val SEEN_SEQS_LIMIT = 256
        private const val CHUNK_BUFFER_LIMIT = 16
    }
}

private fun String.normalizePhone(): String =
    filter { it.isDigit() || it == '+' }.removePrefix("+")

/** `?NEW alias @username "preview"` */
private data class NewFrame(val alias: String, val username: String, val preview: String) {
    companion object {
        private val re = Regex("""\?NEW\s+([a-z0-9_]+)\s+@?([A-Za-z0-9_]*)\s*"?([^"]*)"?""")
        fun parse(body: String): NewFrame? {
            val m = re.find(body) ?: return null
            return NewFrame(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
    }
}
