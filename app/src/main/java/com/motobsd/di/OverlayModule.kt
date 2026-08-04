package com.motobsd.di

import com.motobsd.data.overlay.OverlayRepository
import com.motobsd.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OverlayModule {

    @Provides
    @Singleton
    fun provideOverlayRepository(settingsRepository: SettingsRepository): OverlayRepository {
        return OverlayRepository(settingsRepository)
    }
}
