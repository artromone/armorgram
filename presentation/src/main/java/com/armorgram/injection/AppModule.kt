/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.armorgram.injection

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.lifecycle.ViewModelProvider
import com.f2prateek.rx.preferences2.RxSharedPreferences
import com.armorgram.blocking.BlockingClient
import com.armorgram.blocking.BlockingManager
import com.armorgram.common.ViewModelFactory
import com.armorgram.common.util.BillingManagerImpl
import com.armorgram.common.util.NotificationManagerImpl
import com.armorgram.common.util.ShortcutManagerImpl
import com.armorgram.feature.conversationinfo.injection.ConversationInfoComponent
import com.armorgram.feature.themepicker.injection.ThemePickerComponent
import com.armorgram.listener.ContactAddedListener
import com.armorgram.listener.ContactAddedListenerImpl
import com.armorgram.manager.ActiveConversationManager
import com.armorgram.manager.ActiveConversationManagerImpl
import com.armorgram.manager.AlarmManager
import com.armorgram.manager.AlarmManagerImpl
import com.armorgram.manager.AnalyticsManager
import com.armorgram.manager.AnalyticsManagerImpl
import com.armorgram.manager.BillingManager
import com.armorgram.manager.ChangelogManager
import com.armorgram.manager.ChangelogManagerImpl
import com.armorgram.manager.KeyManager
import com.armorgram.manager.KeyManagerImpl
import com.armorgram.manager.NotificationManager
import com.armorgram.manager.PermissionManager
import com.armorgram.manager.PermissionManagerImpl
import com.armorgram.manager.RatingManager
import com.armorgram.manager.ReferralManager
import com.armorgram.manager.ReferralManagerImpl
import com.armorgram.manager.ShortcutManager
import com.armorgram.manager.WidgetManager
import com.armorgram.manager.WidgetManagerImpl
import com.armorgram.mapper.CursorToContact
import com.armorgram.mapper.CursorToContactGroup
import com.armorgram.mapper.CursorToContactGroupImpl
import com.armorgram.mapper.CursorToContactGroupMember
import com.armorgram.mapper.CursorToContactGroupMemberImpl
import com.armorgram.mapper.CursorToContactImpl
import com.armorgram.mapper.CursorToConversation
import com.armorgram.mapper.CursorToConversationImpl
import com.armorgram.mapper.CursorToMessage
import com.armorgram.mapper.CursorToMessageImpl
import com.armorgram.mapper.CursorToPart
import com.armorgram.mapper.CursorToPartImpl
import com.armorgram.mapper.CursorToRecipient
import com.armorgram.mapper.CursorToRecipientImpl
import com.armorgram.mapper.RatingManagerImpl
import com.armorgram.repository.BackupRepository
import com.armorgram.repository.BackupRepositoryImpl
import com.armorgram.repository.BlockingRepository
import com.armorgram.repository.BlockingRepositoryImpl
import com.armorgram.repository.ContactRepository
import com.armorgram.repository.ContactRepositoryImpl
import com.armorgram.repository.ConversationRepository
import com.armorgram.bridge.BridgeRepository
import com.armorgram.bridge.BridgeRepositoryImpl
import com.armorgram.repository.ConversationRepositoryImpl
import com.armorgram.repository.MessageRepository
import com.armorgram.repository.MessageRepositoryImpl
import com.armorgram.repository.ScheduledMessageRepository
import com.armorgram.repository.ScheduledMessageRepositoryImpl
import com.armorgram.repository.SyncRepository
import com.armorgram.repository.SyncRepositoryImpl
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module(subcomponents = [
    ConversationInfoComponent::class,
    ThemePickerComponent::class])
class AppModule(private var application: Application) {

    @Provides
    @Singleton
    fun provideContext(): Context = application

    @Provides
    fun provideContentResolver(context: Context): ContentResolver = context.contentResolver

    @Provides
    @Singleton
    fun provideSharedPreferences(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    @Provides
    @Singleton
    fun provideRxPreferences(preferences: SharedPreferences): RxSharedPreferences {
        return RxSharedPreferences.create(preferences)
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
    }

    @Provides
    fun provideViewModelFactory(factory: ViewModelFactory): ViewModelProvider.Factory = factory

    // Listener

    @Provides
    fun provideContactAddedListener(listener: ContactAddedListenerImpl): ContactAddedListener = listener

    // Manager

    @Provides
    fun provideBillingManager(manager: BillingManagerImpl): BillingManager = manager

    @Provides
    fun provideActiveConversationManager(manager: ActiveConversationManagerImpl): ActiveConversationManager = manager

    @Provides
    fun provideAlarmManager(manager: AlarmManagerImpl): AlarmManager = manager

    @Provides
    fun provideAnalyticsManager(manager: AnalyticsManagerImpl): AnalyticsManager = manager

    @Provides
    fun blockingClient(manager: BlockingManager): BlockingClient = manager

    @Provides
    fun changelogManager(manager: ChangelogManagerImpl): ChangelogManager = manager

    @Provides
    fun provideKeyManager(manager: KeyManagerImpl): KeyManager = manager

    @Provides
    fun provideNotificationsManager(manager: NotificationManagerImpl): NotificationManager = manager

    @Provides
    fun providePermissionsManager(manager: PermissionManagerImpl): PermissionManager = manager

    @Provides
    fun provideRatingManager(manager: RatingManagerImpl): RatingManager = manager

    @Provides
    fun provideShortcutManager(manager: ShortcutManagerImpl): ShortcutManager = manager

    @Provides
    fun provideReferralManager(manager: ReferralManagerImpl): ReferralManager = manager

    @Provides
    fun provideWidgetManager(manager: WidgetManagerImpl): WidgetManager = manager

    // Mapper

    @Provides
    fun provideCursorToContact(mapper: CursorToContactImpl): CursorToContact = mapper

    @Provides
    fun provideCursorToContactGroup(mapper: CursorToContactGroupImpl): CursorToContactGroup = mapper

    @Provides
    fun provideCursorToContactGroupMember(mapper: CursorToContactGroupMemberImpl): CursorToContactGroupMember = mapper

    @Provides
    fun provideCursorToConversation(mapper: CursorToConversationImpl): CursorToConversation = mapper

    @Provides
    fun provideCursorToMessage(mapper: CursorToMessageImpl): CursorToMessage = mapper

    @Provides
    fun provideCursorToPart(mapper: CursorToPartImpl): CursorToPart = mapper

    @Provides
    fun provideCursorToRecipient(mapper: CursorToRecipientImpl): CursorToRecipient = mapper

    // Repository

    @Provides
    fun provideBackupRepository(repository: BackupRepositoryImpl): BackupRepository = repository

    @Provides
    fun provideBlockingRepository(repository: BlockingRepositoryImpl): BlockingRepository = repository

    @Provides
    fun provideContactRepository(repository: ContactRepositoryImpl): ContactRepository = repository

    @Provides
    fun provideConversationRepository(repository: ConversationRepositoryImpl): ConversationRepository = repository

    @Provides
    fun provideMessageRepository(repository: MessageRepositoryImpl): MessageRepository = repository

    @Provides
    fun provideScheduledMessagesRepository(repository: ScheduledMessageRepositoryImpl): ScheduledMessageRepository = repository

    @Provides
    fun provideSyncRepository(repository: SyncRepositoryImpl): SyncRepository = repository

    @Provides
    fun provideBridgeRepository(repository: BridgeRepositoryImpl): BridgeRepository = repository

}