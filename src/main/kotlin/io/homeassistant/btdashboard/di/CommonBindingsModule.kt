package io.homeassistant.btdashboard.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.data.integration.PushWebsocketSupport
import io.homeassistant.companion.android.common.util.AppVersion
import io.homeassistant.companion.android.common.util.AppVersionProvider
import io.homeassistant.companion.android.common.util.MessagingToken
import io.homeassistant.companion.android.common.util.MessagingTokenProvider

@Module
@InstallIn(SingletonComponent::class)
object CommonBindingsModule {

    @Provides
    fun provideAppVersionProvider(): AppVersionProvider =
        AppVersionProvider { AppVersion.from("1.0 (1)") }

    @Provides
    fun provideMessagingTokenProvider(): MessagingTokenProvider =
        MessagingTokenProvider { MessagingToken("") }

    @Provides
    @PushWebsocketSupport
    fun providePushWebsocketSupport(): Boolean = false
}
