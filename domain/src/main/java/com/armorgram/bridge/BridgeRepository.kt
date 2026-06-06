package com.armorgram.bridge

interface BridgeRepository {

    /** True if bridge mode is on and the address is the configured gateway phone. */
    fun isGateway(address: String): Boolean

    /** Stable virtual threadId for (gateway, alias). Always negative to avoid colliding
     *  with Android TelephonyProvider thread ids (which are positive). */
    fun virtualThreadId(alias: String): Long

    /**
     * Parse a raw SMS body received from the gateway as a wire frame and create
     * virtual Realm conversations/messages for each contained MsgLine.
     * Returns true if the body was consumed (caller should skip the normal
     * insert-as-real-SMS path). False means body did not parse as a frame.
     */
    fun routeIncoming(subId: Int, body: String, sentTime: Long): Boolean

    /**
     * Encode a user-typed message destined to a virtual conversation as a wire
     * frame. Returns the encoded text to be sent as SMS to the gateway.
     * Increments and persists the outbound seq counter.
     */
    fun encodeOutgoing(virtualThreadId: Long, text: String): String?

    /** Looks up the alias for a virtual conversation. */
    fun aliasFor(virtualThreadId: Long): String?

    /**
     * Persist an outgoing virtual message in Realm and dispatch it as a real SMS
     * (encoded as a wire frame) to the configured gateway phone. Returns true if
     * the dispatch was attempted; false means the threadId is not virtual or the
     * gateway isn't configured.
     */
    fun sendVirtual(subId: Int, virtualThreadId: Long, text: String): Boolean
}
