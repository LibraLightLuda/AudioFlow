package app.audioflow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

internal data class LibraryNotice(val text: String, val undo: (suspend () -> Unit)? = null)

internal class LibraryModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AudioFlowApp
    private val dao = app.store.dao
    val notices = MutableSharedFlow<LibraryNotice>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun change(action: suspend () -> Unit) { viewModelScope.launch {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            app.reportFailure("로컬 저장", failure)
        }
    } }
    fun removeHistory(id: String) = change {
        val row = dao.track(id) ?: return@change
        if (row.playedAt == 0L) return@change
        dao.removeHistory(id)
        notices.emit(LibraryNotice("재생 기록에서 제외했어요.") { dao.restoreHistory(row) })
    }
    fun removeFromPlaylist(listId: Long, id: String) = change {
        val entry = dao.playlistEntry(listId, id) ?: return@change
        dao.removeFromPlaylist(listId, id)
        notices.emit(LibraryNotice("플레이리스트에서 제외했어요.") { dao.insertPlaylistEntry(entry) })
    }
    fun toggleFavorite(track: Track) = change {
        val previous = dao.track(track.id)
        dao.toggleFavorite(track)
        if (previous != null && previous.favoriteAt > 0) notices.emit(LibraryNotice("즐겨찾기에서 제외했어요.") { dao.restoreFavorite(previous) })
    }
}
