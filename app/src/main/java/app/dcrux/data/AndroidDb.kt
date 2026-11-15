package app.dcrux.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Opens the app's database on Android, bringing an older one up to date step by step. */
fun openAndroidDb(context: Context): AppDb =
    Room.databaseBuilder(context, AppDb::class.java, "dcrux.db")
        .addMigrations(*DB_MIGRATIONS.map { (from, sql) ->
            object : Migration(from, from + 1) {
                override fun migrate(db: SupportSQLiteDatabase) = sql.forEach(db::execSQL)
            }
        }.toTypedArray())
        .build()
