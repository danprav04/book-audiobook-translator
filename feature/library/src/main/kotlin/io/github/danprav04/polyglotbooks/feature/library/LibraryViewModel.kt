package io.github.danprav04.polyglotbooks.feature.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryFilterTab {
    ALL, AUDIOBOOKS, EBOOKS
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = bookDao.getAllBooks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun importUris(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            val importer = SafLibraryImporter(context.applicationContext, bookDao, chapterDao)
            importer.importUris(uris)
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            bookDao.deleteBookById(bookId)
        }
    }
}
