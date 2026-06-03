package com.lwl.bookreader.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** 应用单例数据库。 */
@Database(entities = {Book.class, Bookmark.class, Note.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract BookDao bookDao();

    public abstract BookmarkDao bookmarkDao();

    public abstract NoteDao noteDao();

    private static volatile AppDatabase instance;

    /** v1 -> v2:新增书签、笔记表(保留已有图书数据)。 */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `bookmark` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, "
                    + "`chapterProgress` REAL NOT NULL, `snippet` TEXT, "
                    + "`createTime` INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS `note` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, "
                    + "`chapterProgress` REAL NOT NULL, `text` TEXT, "
                    + "`createTime` INTEGER NOT NULL)");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "bookreader.db")
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return instance;
    }
}
