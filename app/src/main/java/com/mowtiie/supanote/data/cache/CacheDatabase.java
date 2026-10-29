package com.mowtiie.supanote.data.cache;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.util.List;

/**
 * Local copy of the notes. Title, content and tags are kept encrypted in {@code payload}; ids, timestamps and
 * flags stay readable so they can be queried. The cache can always be rebuilt from the server, so a schema
 * change just drops it.
 */
@Database(entities = {CacheDatabase.CachedNote.class, CacheDatabase.CacheMeta.class}, version = 1, exportSchema = false)
abstract class CacheDatabase extends RoomDatabase {

    static final String FILE_NAME = "supanote_cache.db";

    @Entity(tableName = "notes")
    static class CachedNote {
        @PrimaryKey public long id;
        /** Position in the server's order (0 is the top). */
        public long position;
        @ColumnInfo(name = "created_at") public String createdAt;
        @ColumnInfo(name = "updated_at") public String updatedAt;
        @ColumnInfo(name = "deleted_at") public String deletedAt;
        public boolean pinned;
        public boolean archived;
        @NonNull public String payload = "";
    }

    @Entity(tableName = "meta")
    static class CacheMeta {
        @PrimaryKey @NonNull public String key = "";
        public String value;
    }

    @Dao
    interface CacheDao {
        @Query("SELECT * FROM notes ORDER BY position ASC")
        List<CachedNote> all();

        @Query("SELECT position FROM notes WHERE id = :id")
        Long positionOf(long id);

        @Query("SELECT MIN(position) FROM notes")
        Long minPosition();

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void insert(CachedNote note);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void insertAll(List<CachedNote> notes);

        @Query("DELETE FROM notes WHERE id = :id")
        void delete(long id);

        @Query("DELETE FROM notes WHERE deleted_at IS NOT NULL")
        void deleteTrashed();

        @Query("DELETE FROM notes")
        void deleteAll();

        @Query("SELECT value FROM meta WHERE `key` = :key")
        String meta(String key);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void putMeta(CacheMeta meta);

        @Query("DELETE FROM meta")
        void deleteMeta();
    }

    abstract CacheDao dao();

    static CacheDatabase open(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), CacheDatabase.class, FILE_NAME)
                .fallbackToDestructiveMigration(true)
                .build();
    }
}
