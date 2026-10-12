package com.android.messaging.di.extras

import com.android.messaging.data.appsettings.repository.AppPreferenceReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal object ExtrasProvidesModule {

    @Provides
    fun provideAppPreferenceReader(): AppPreferenceReader = AppPreferenceReader.Default
}
