package io.github.danprav04.polyglotbooks.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
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
    private val bookDao: BookDao
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = bookDao.getAllBooks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            bookDao.deleteBookById(bookId)
        }
    }
}
