package com.armorgram.feature.conversationinfo

import com.armorgram.model.MmsPart
import com.armorgram.model.Recipient
import io.realm.RealmList

sealed class ConversationInfoItem {

    data class ConversationInfoRecipient(val value: Recipient) : ConversationInfoItem()

    data class ConversationInfoSettings(
        val name: String,
        val recipients: RealmList<Recipient>,
        val archived: Boolean,
        val blocked: Boolean
    ) : ConversationInfoItem()

    data class ConversationInfoMedia(val value: MmsPart) : ConversationInfoItem()

}
