package com.motobsd.di

import android.content.Context
import com.motobsd.audio.SoundManager
import com.motobsd.data.ble.BleRepository
import com.motobsd.data.ble.BleRepositoryImpl
import com.motobsd.data.ble.BleScanner
import com.motobsd.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun provideBleScanner(@ApplicationContext context: Context): BleScanner {
        return BleScanner(context)
    }

    @Provides
    @Singleton
    fun provideBleRepository(
        @ApplicationContext context: Context,
        scanner: BleScanner,
        settingsRepository: SettingsRepository,
    ): BleRepository {
        return BleRepositoryImpl(context, scanner, settingsRepository)
    }

    @Provides
    @Singleton
    fun provideSoundManager(): SoundManager {
        return SoundManager()
    }
}
