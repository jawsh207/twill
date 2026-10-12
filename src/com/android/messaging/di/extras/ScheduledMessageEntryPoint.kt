package com.android.messaging.di.extras

import com.android.messaging.data.scheduled.ScheduledMessageScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ScheduledMessageEntryPoint {
    fun scheduledMessageScheduler(): ScheduledMessageScheduler
}
