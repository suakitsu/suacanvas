package com.suakitsu.picpilot.integration

import android.content.Context
import com.suakitsu.picpilot.feature.aicreate.AiCreateGateway
import com.suakitsu.picpilot.integration.upload.ContentResolverUploadSource
import com.suakitsu.picpilot.integration.upload.OrderedMultipartFactory
import com.suakitsu.picpilot.integration.upload.UploadContentSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NativeIntegrationModule {
    @Provides
    @Singleton
    fun uploadContentSource(@ApplicationContext context: Context): UploadContentSource =
        ContentResolverUploadSource(context.contentResolver)

    @Provides
    @Singleton
    fun orderedMultipartFactory(source: UploadContentSource): OrderedMultipartFactory =
        OrderedMultipartFactory(source)

    @Provides
    @Singleton
    fun aiCreateGateway(implementation: ProductionAiCreateGateway): AiCreateGateway = implementation
}
