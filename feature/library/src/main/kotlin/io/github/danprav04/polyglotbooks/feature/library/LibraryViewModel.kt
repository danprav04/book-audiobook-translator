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
            try {
                val importer = SafLibraryImporter(context.applicationContext, bookDao, chapterDao)
                importer.importUris(uris)
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "Error importing uris", e)
            }
        }
    }

    fun importFolder(context: Context, treeUri: Uri) {
        viewModelScope.launch {
            try {
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)

                val docId = if (android.provider.DocumentsContract.isDocumentUri(context, treeUri)) {
                    android.provider.DocumentsContract.getDocumentId(treeUri)
                } else {
                    android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                }

                val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val fileUris = mutableListOf<Uri>()

                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeCol = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val childDocId = cursor.getString(idCol)
                        val mimeType = cursor.getString(mimeCol)
                        // Ignore sub-directories, collect files
                        if (mimeType != android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            val childUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                            fileUris.add(childUri)
                        }
                    }
                }

                if (fileUris.isNotEmpty()) {
                    val importer = SafLibraryImporter(context.applicationContext, bookDao, chapterDao)
                    importer.importUris(fileUris)
                }
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "Error importing folder tree", e)
            }
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            bookDao.deleteBookById(bookId)
        }
    }
}
