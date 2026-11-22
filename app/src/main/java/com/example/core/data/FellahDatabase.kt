package com.example.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        FarmerDetailsEntity::class,
        BuyerDetailsEntity::class,
        RegionEntity::class,
        CategoryEntity::class,
        ProductEntity::class,
        OfferEntity::class,
        BuyRequestEntity::class,
        DealEntity::class,
        DealStatusHistoryEntity::class,
        RatingEntity::class,
        NotificationEntity::class,
        SavedFilterEntity::class,
        ReferencePriceEntity::class,
        ProductProposalEntity::class,
        ReportEntity::class,
        AnalyticsEventEntity::class,
        SyncQueueEntity::class,
        FavoriteOfferEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class FellahDatabase : RoomDatabase() {
    abstract fun dao(): FellahDao

    companion object {
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS favorite_offers (userId TEXT NOT NULL, offerId TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(userId, offerId))")
            }
        }

        @Volatile
        private var INSTANCE: FellahDatabase? = null

        fun getInstance(context: Context): FellahDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FellahDatabase::class.java,
                    "fellah_market_db"
                ).addMigrations(MIGRATION_3_4).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
