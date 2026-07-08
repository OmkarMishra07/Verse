package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "liked_songs")
data class LikedSong(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val duration: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlist_songs")
data class PlaylistSong(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val duration: String,
    val displayOrder: Int
)

@Entity(tableName = "recently_played")
data class RecentlyPlayed(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val duration: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Dao
interface SongDao {
    // Liked Songs
    @Query("SELECT * FROM liked_songs ORDER BY addedAt DESC")
    fun getLikedSongs(): Flow<List<LikedSong>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedSong(song: LikedSong)

    @Delete
    suspend fun deleteLikedSong(song: LikedSong)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE videoId = :videoId)")
    fun isLiked(videoId: String): Flow<Boolean>

    // Playlists
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getPlaylists(): Flow<List<Playlist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    // Playlist Songs
    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY displayOrder ASC")
    fun getSongsForPlaylist(playlistId: Long): Flow<List<PlaylistSong>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(song: PlaylistSong)

    @Delete
    suspend fun deletePlaylistSong(song: PlaylistSong)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun deleteSongsForPlaylist(playlistId: Long)

    @Query("SELECT MAX(displayOrder) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getMaxOrderForPlaylist(playlistId: Long): Int?

    // Recently Played
    @Query("SELECT * FROM recently_played ORDER BY playedAt DESC LIMIT 20")
    fun getRecentlyPlayed(): Flow<List<RecentlyPlayed>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentlyPlayed(song: RecentlyPlayed)

    @Query("DELETE FROM liked_songs")
    suspend fun clearLikedSongs()

    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Query("DELETE FROM playlist_songs")
    suspend fun clearPlaylistSongs()

    @Query("DELETE FROM recently_played")
    suspend fun clearRecentlyPlayed()

    @Query("""
        DELETE FROM playlist_songs 
        WHERE id NOT IN (
            SELECT MIN(id) 
            FROM playlist_songs 
            GROUP BY playlistId, videoId
        )
    """)
    suspend fun removeDuplicatePlaylistSongs()

    @Transaction
    suspend fun clearAllData() {
        clearLikedSongs()
        clearPlaylists()
        clearPlaylistSongs()
        clearRecentlyPlayed()
    }
}

@Database(
    entities = [LikedSong::class, Playlist::class, PlaylistSong::class, RecentlyPlayed::class],
    version = 1,
    exportSchema = false
)
abstract class SongDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object {
        @Volatile
        private var INSTANCE: SongDatabase? = null

        fun getDatabase(context: android.content.Context): SongDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SongDatabase::class.java,
                    "song_database"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
