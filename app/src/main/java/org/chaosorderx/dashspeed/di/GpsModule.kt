package org.chaosorderx.dashspeed.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object GpsModule
// GpsManager is @Singleton with @Inject constructor — no explicit @Provides needed.
