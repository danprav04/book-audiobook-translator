package io.github.danprav04.polyglotbooks.feature.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    internal var uriImporterOverride: (suspend (Context, List<Uri>) -> List<String>)? = null
    internal var folderUriCollector: ((Context, Uri) -> List<Uri>)? = null

    fun clearError() {
        _errorMessage.value = null
    }

    fun importUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        _errorMessage.value = null
        val appContext = context.applicationContext ?: context
        viewModelScope.launch {
            try {
                val imported = uriImporterOverride?.invoke(appContext, uris)
                    ?: SafLibraryImporter(appContext, bookDao, chapterDao).importUris(uris)
                if (imported.isEmpty()) {
                    DiagnosticLogBuffer.record(
                        tag = "LibraryVM",
                        message = "URI import returned 0 items (selected=${uris.size})",
                        level = "WARN"
                    )
                    _errorMessage.value = "Could not import selected file(s). Check file format or permissions."
                } else {
                    DiagnosticLogBuffer.record(
                        tag = "LibraryVM",
                        message = "Imported ${imported.size} book(s) from ${uris.size} file(s)",
                        level = "INFO"
                    )
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                DiagnosticLogBuffer.record(
                    tag = "LibraryVM",
                    message = "Error importing selected files",
                    level = "ERROR",
                    throwable = e
                )
                runCatching { android.util.Log.e("LibraryViewModel", "Error importing uris", e) }
                _errorMessage.value = e.message?.takeIf { it.isNotBlank() } ?: "Failed to import selected file(s)."
            }
        }
    }

    fun importFolder(context: Context, treeUri: Uri) {
        _errorMessage.value = null
        val appContext = context.applicationContext ?: context
        viewModelScope.launch {
            try {
                val fileUris = folderUriCollector?.invoke(appContext, treeUri)
                    ?: collectFolderDocumentUris(appContext, treeUri)

                if (fileUris.isNotEmpty()) {
                    val imported = uriImporterOverride?.invoke(appContext, fileUris)
                        ?: SafLibraryImporter(appContext, bookDao, chapterDao).importUris(fileUris)
                    if (imported.isEmpty()) {
                        DiagnosticLogBuffer.record(
                            tag = "LibraryVM",
                            message = "Folder import returned 0 items (candidates=${fileUris.size})",
                            level = "WARN"
                        )
                        _errorMessage.value = "Could not import supported files from the selected folder."
                    } else {
                        DiagnosticLogBuffer.record(
                            tag = "LibraryVM",
                            message = "Imported ${imported.size} book(s) from folder (candidates=${fileUris.size})",
                            level = "INFO"
                        )
                    }
                } else {
                    DiagnosticLogBuffer.record(
                        tag = "LibraryVM",
                        message = "No supported files found in selected folder",
                        level = "WARN"
                    )
                    _errorMessage.value = "No supported ebooks or audiobooks found in selected folder."
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                DiagnosticLogBuffer.record(
                    tag = "LibraryVM",
                    message = "Error importing folder tree",
                    level = "ERROR",
                    throwable = e
                )
                runCatching { android.util.Log.e("LibraryViewModel", "Error importing folder tree", e) }
                _errorMessage.value = e.message?.takeIf { it.isNotBlank() } ?: "Failed to import selected folder."
            }
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            try {
                bookDao.deleteBookById(bookId)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                DiagnosticLogBuffer.record(
                    tag = "LibraryVM",
                    message = "Error deleting book id=$bookId",
                    level = "ERROR",
                    throwable = e
                )
                _errorMessage.value = e.message?.takeIf { it.isNotBlank() } ?: "Failed to delete book."
            }
        }
    }

    private fun collectFolderDocumentUris(context: Context, treeUri: Uri): List<Uri> {
        runCatching {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        }

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
                if (mimeType != android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                    fileUris.add(childUri)
                }
            }
        }
        return fileUris
    }
}
