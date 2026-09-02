package com.spamcut.app.di

import android.content.Context
import com.spamcut.app.data.SessionManager
import com.spamcut.app.data.api.RetrofitClient
import com.spamcut.app.data.api.SpamApiService
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.dao.PendingReportDao
import com.spamcut.app.data.db.dao.RecentContactDao
import com.spamcut.app.data.db.dao.SpamDao
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
    fun provideRecentContactDao(db: AppDatabase): RecentContactDao = db.recentContactDao()

    @Provides
    @Singleton
    fun provideApiService(): SpamApiService = RetrofitClient.service

    @Provides
    @Singleton
    fun provideSessionManager(@ApplicationContext context: Context): SessionManager =
        SessionManager(context)
}
