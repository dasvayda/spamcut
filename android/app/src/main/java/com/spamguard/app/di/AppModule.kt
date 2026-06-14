package com.spamguard.app.di

import android.content.Context
import com.spamguard.app.data.SessionManager
import com.spamguard.app.data.api.RetrofitClient
import com.spamguard.app.data.api.SpamApiService
import com.spamguard.app.data.db.AppDatabase
import com.spamguard.app.data.db.dao.PendingReportDao
import com.spamguard.app.data.db.dao.SpamDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    fun provideSpamDao(db: AppDatabase): SpamDao = db.spamDao()

    @Provides
    fun providePendingReportDao(db: AppDatabase): PendingReportDao = db.pendingReportDao()

    @Provides
    @Singleton
    fun provideApiService(): SpamApiService = RetrofitClient.service

    @Provides
    @Singleton
    fun provideSessionManager(@ApplicationContext context: Context): SessionManager =
        SessionManager(context)
}
