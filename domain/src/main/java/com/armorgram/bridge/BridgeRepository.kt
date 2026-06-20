package com.armorgram.bridge

interface BridgeRepository {

    /** True if bridge mode is on and the address is the configured gateway phone. */
    fun isGateway(address: String): Boolean

    /** Threads with id<0 created by us; everything else is a real Android thread. */
    fun isVirtualThread(threadId: Long): Boolean

    /** Stable virtual threadId for (gateway, alias). Always negative to avoid colliding
     *  with Android TelephonyProvider thread ids (which are positive). */
    fun virtualThreadId(alias: String): Long

    /**
     * Parse a raw SMS body received from the gateway as a wire frame and route it.
     * - MSG → virtual chats per alias (one Message per line).
     * - SYS/ACK → "_sys" virtual chat.
     * - Chunked frames (idx/total > 0) are buffered until complete.
     *
     * Returns:
     * - `null` if the body did NOT decode as a frame — the caller should fall
     *   through to the normal SMS receive path so it stays visible.
     * - a (possibly empty) list of virtual threadIds that received a new incoming
     *   message otherwise — the body was consumed (skip the normal CP insert), and
     *   the caller should post a notification for each returned threadId. An empty
     *   list means "consumed, nothing to notify" (dup seq, incomplete chunk, sys/ack).
     */
    fun routeIncoming(subId: Int, body: String, sentTime: Long): List<Long>?

    /** Encode a user-typed message destined to a virtual conversation as a wire frame. */
    fun encodeOutgoing(virtualThreadId: Long, text: String): String?

    /** Looks up the alias for a virtual conversation. */
    fun aliasFor(virtualThreadId: Long): String?

    /** Persist an outgoing virtual message and dispatch as SMS to the gateway. */
    fun sendVirtual(subId: Int, virtualThreadId: Long, text: String): Boolean

    /**
     * Send a control command (`/wl add a3`, `/approve a3`, `/block a3`, `/hist a3 20`,
     * `/resend 100-110`, `/ping`) to the backend over SMS.
     * Returns true if dispatched.
     */
    fun sendCommand(subId: Int, command: String): Boolean
}
