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
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.List;

/**
 * Local copy of the notes, plus the changes made offline that the server has not seen yet. Title, content and tags
 * are kept encrypted in {@code payload}; ids, timestamps, flags and sync state stay readable so they can be queried.
 *
 * Unsent changes live here, so this database is no longer disposable: every version bump needs a real
 * {@link Migration} that keeps them. Never use a destructive migration (except on downgrade, which cannot happen
 * in normal use).
 */
@Database(entities = {CacheDatabase.CachedNote.class, CacheDatabase.CacheMeta.class, CacheDatabase.IdAlias.class},
        version = 2, exportSchema = false)
abstract class CacheDatabase extends RoomDatabase {

    static final String FILE_NAME = "supanote_cache.db";

    @Entity(tableName = "notes")
    static class CachedNote {
        @PrimaryKey public long id;
        /** Position in the server's order (0 is the top; notes made offline are above it, below 0). */
        public long position;
        @ColumnInfo(name = "created_at") public String createdAt;
        @ColumnInfo(name = "updated_at") public String updatedAt;
        @ColumnInfo(name = "deleted_at") public String deletedAt;
        public boolean pinned;
        public boolean archived;
        @NonNull public String payload = "";

        /** Which parts changed since the server's copy; see {@link PendingChange}. 0 when the note is in sync. */
        @ColumnInfo(name = "dirty_mask", defaultValue = "0") public int dirtyMask;
        /** The user deleted the note for good; the delete has not been uploaded yet. */
        @ColumnInfo(name = "pending_delete", defaultValue = "0") public boolean pendingDelete;
        /** The server refused the upload; the note waits for the user. */
        @ColumnInfo(name = "sync_failed", defaultValue = "0") public boolean syncFailed;
        /** The server's updated_at that the text edit was made on top of. */
        @ColumnInfo(name = "base_updated_at") public String baseUpdatedAt;
        @ColumnInfo(name = "client_id") public String clientId;
        @ColumnInfo(defaultValue = "0") public long version;
    }

    @Entity(tableName = "meta")
    static class CacheMeta {
        @PrimaryKey @NonNull public String key = "";
        public String value;
    }

    /** Where a note went when its temporary id was replaced by the server's, for screens that still hold the old one. */
    @Entity(tableName = "id_alias")
    static class IdAlias {
        @PrimaryKey @ColumnInfo(name = "old_id") public long oldId;
        @ColumnInfo(name = "new_id") public long newId;
    }

    @Dao
    interface CacheDao {
        @Query("SELECT * FROM notes")
        List<CachedNote> everything();

        @Query("SELECT * FROM notes WHERE pending_delete = 0 ORDER BY position ASC")
        List<CachedNote> visible();

        @Query("SELECT * FROM notes WHERE id = :id")
        CachedNote get(long id);

        /** Notes with something to upload. Failed ones wait for the user. */
        @Query("SELECT * FROM notes WHERE sync_failed = 0 AND (dirty_mask != 0 OR pending_delete = 1 OR id <= -2)")
        List<CachedNote> pending();

        @Query("SELECT COUNT(*) FROM notes WHERE dirty_mask != 0 OR pending_delete = 1 OR id <= -2 OR sync_failed = 1")
        int pendingCount();

        @Query("SELECT position FROM notes WHERE id = :id")
        Long positionOf(long id);

        @Query("SELECT MIN(position) FROM notes")
        Long minPosition();

        @Query("SELECT MIN(id) FROM notes")
        Long minId();

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void insert(CachedNote note);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void insertAll(List<CachedNote> notes);

        @Query("UPDATE notes SET position = :position WHERE id = :id")
        void setPosition(long id, long position);

        @Query("UPDATE notes SET sync_failed = :failed WHERE id = :id")
        void setFailed(long id, boolean failed);

        @Query("DELETE FROM notes WHERE id = :id")
        void delete(long id);

        @Query("DELETE FROM notes WHERE deleted_at IS NOT NULL")
        void deleteTrashed();

        @Query("DELETE FROM notes")
        void deleteAll();

        @Query("SELECT new_id FROM id_alias WHERE old_id = :id")
        Long aliasOf(long id);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void putAlias(IdAlias alias);

        @Query("DELETE FROM id_alias")
        void deleteAliases();

        @Query("SELECT value FROM meta WHERE `key` = :key")
        String meta(String key);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void putMeta(CacheMeta meta);

        @Query("DELETE FROM meta")
        void deleteMeta();
    }

    abstract CacheDao dao();

    /** Version 1 held only a read-only copy. Version 2 adds what is needed to edit offline and upload later. */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE notes ADD COLUMN dirty_mask INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE notes ADD COLUMN pending_delete INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE notes ADD COLUMN sync_failed INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE notes ADD COLUMN base_updated_at TEXT");
            db.execSQL("ALTER TABLE notes ADD COLUMN client_id TEXT");
            db.execSQL("ALTER TABLE notes ADD COLUMN version INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS id_alias (old_id INTEGER NOT NULL, new_id INTEGER NOT NULL, PRIMARY KEY(old_id))");
        }
    };

    static CacheDatabase open(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), CacheDatabase.class, FILE_NAME)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build();
    }
}
