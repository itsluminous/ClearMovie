package com.itsluminous.clearmovie.testing

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.itsluminous.clearmovie.data.db.ClearMovieDatabase
import com.itsluminous.clearmovie.data.media.UriGrants
import java.io.File

/** A file-backed database, so tests can close it and open it again like a new process would. */
fun fileDatabase(
    context: Context,
    file: File,
): ClearMovieDatabase =
    Room
        .databaseBuilder(context, ClearMovieDatabase::class.java, file.path)
        .addMigrations(*ClearMovieDatabase.MIGRATIONS)
        .allowMainThreadQueries()
        .build()

/** Persisted grants in memory. [persistable] lists the links whose grant can be persisted. */
class FakeUriGrants(
    val persistable: MutableSet<String> = mutableSetOf(),
) : UriGrants {
    val held = linkedSetOf<String>()
    val released = mutableListOf<String>()

    /** False stands for Android's grant limit being nearly reached: nothing more is persisted. */
    var room = true

    override fun hasRoom(): Boolean = room

    override fun persist(uri: Uri): Boolean {
        if (uri.toString() in held) return true
        if (!room || uri.toString() !in persistable) return false
        held += uri.toString()
        return true
    }

    override fun isPersisted(uri: String): Boolean = uri in held

    override fun release(uri: String) {
        held -= uri
        released += uri
    }
}
