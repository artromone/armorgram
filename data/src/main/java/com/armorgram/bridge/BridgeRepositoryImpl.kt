package com.armorgram.bridge

import android.provider.Telephony.Sms
import com.armorgram.manager.KeyManager
import com.armorgram.model.Conversation
import com.armorgram.model.Message
import com.armorgram.model.Recipient
import com.armorgram.util.Preferences
import com.klinker.android.send_message.SmsManagerFactory
import io.realm.Realm
import io.realm.RealmList
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the SMS-over-wire bridge state on the Android side.
 *
 * Virtual threadIds are negative 48-bit hashes of (gatewayPhone, alias) so they
 * cannot collide with positive Android TelephonyProvider thread ids. Conversations
 * and Messages live in Realm only; the Android SMS content provider is bypassed
 * entirely for bridged messages.
 */
@Singleton
class BridgeRepositoryImpl @Inject constructor(
    private val prefs: Preferences,
    private val messageIds: KeyManager
) : BridgeRepository {

    override fun isGateway(address: String): Boolean {
        if (!prefs.bridgeEnabled.get()) return false
        val gw = prefs.bridgeGatewayPhone.get().normalizePhone()
        if (gw.isEmpty()) return false
        return address.normalizePhone() == gw
    }

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
        if (virtualThreadId >= 0) return null
        // Cheap lookup: virtual conversations store the alias as the (sole) Recipient.address.
        return Realm.getDefaultInstance().use { realm ->
            realm.where(Conversation::class.java)
                .equalTo("id", virtualThreadId)
                .findFirst()
                ?.recipients
                ?.firstOrNull()
                ?.address
        }
    }

    override fun routeIncoming(subId: Int, body: String, sentTime: Long): Boolean {
        val frame = Wire.decode(body) ?: return false
        when (frame.kind) {
            Wire.Kind.MSG -> {
                val lines = Wire.decodeMsgLines(frame.body)
                if (lines.isEmpty()) return false
                lines.forEach { line ->
                    val display = if (line.sender.isNotEmpty()) "${line.sender}: ${line.text}" else line.text
                    insertVirtualIncoming(subId, line.alias, display, sentTime, frame.seq)
                }
            }
            Wire.Kind.NEW, Wire.Kind.SYS, Wire.Kind.ACK -> {
                // Park system frames under a reserved "_sys" virtual chat for now.
                insertVirtualIncoming(subId, "_sys", "[${frame.kind.tag}] ${frame.body}", sentTime, frame.seq)
            }
            else -> {
                Timber.w("ignoring inbound frame kind=${frame.kind}")
            }
        }
        return true
    }

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
        if (virtualThreadId >= 0) return false
        val gateway = prefs.bridgeGatewayPhone.get()
        if (gateway.isEmpty()) return false
        val encoded = encodeOutgoing(virtualThreadId, text) ?: return false
        val alias = aliasFor(virtualThreadId) ?: return false

        // Persist as outbound virtual message in Realm
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

        // Dispatch as real SMS to the gateway with the wire-encoded payload
        try {
            val smsManager = subId.takeIf { it != -1 }
                ?.let(SmsManagerFactory::createSmsManager)
                ?: android.telephony.SmsManager.getDefault()
            val parts = smsManager.divideMessage(encoded) ?: arrayListOf(encoded)
            if (parts.size == 1) {
                smsManager.sendTextMessage(gateway, null, encoded, null, null)
            } else {
                smsManager.sendMultipartTextMessage(gateway, null, parts, null, null)
            }
        } catch (t: Throwable) {
            Timber.e(t, "bridge outbound dispatch failed")
            return false
        }
        Timber.i("bridge outbound: thread=$virtualThreadId alias=$alias bytes=${encoded.length}")
        return true
    }

    // ─── internals ──────────────────────────────────────────────────────────

    private fun nextOutSeq(): Long {
        val pref = prefs.bridgeOutSeq
        val next = pref.get() + 1
        pref.set(next)
        return next
    }

    private fun insertVirtualIncoming(subId: Int, alias: String, text: String, sentTime: Long, frameSeq: Long) {
        val threadId = virtualThreadId(alias)
        val recipientId = -(threadId xor 0x5A5A5A5AL) and Long.MAX_VALUE // stable, positive
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                // Upsert Recipient
                var recipient = r.where(Recipient::class.java).equalTo("id", recipientId).findFirst()
                if (recipient == null) {
                    recipient = r.createObject(Recipient::class.java, recipientId)
                    recipient.address = alias
                }
                // Upsert Conversation
                var convo = r.where(Conversation::class.java).equalTo("id", threadId).findFirst()
                if (convo == null) {
                    convo = r.createObject(Conversation::class.java, threadId)
                    convo!!.recipients = RealmList(recipient!!)
                }
                // Insert Message
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
}

private fun String.normalizePhone(): String =
    filter { it.isDigit() || it == '+' }.removePrefix("+")
