package com.yue.moku

import android.app.Application
import androidx.room.Room
import com.yue.moku.data.AppDatabase
import com.yue.moku.data.SettingsRepository
import com.yue.moku.data.UpdateRepository
import com.yue.moku.network.ChatApiClient

class MoKuApplication : Application() {
    val container by lazy {
        AppContainer(
            database = Room.databaseBuilder(this, AppDatabase::class.java, "moku.db")
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
                .fallbackToDestructiveMigration(true)
                .build(),
            settings = SettingsRepository(this),
            api = ChatApiClient(),
            update = UpdateRepository(),
        )
    }
}

data class AppContainer(
    val database: AppDatabase,
    val settings: SettingsRepository,
    val api: ChatApiClient,
    val update: UpdateRepository,
)
