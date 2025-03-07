package com.omgodse.notally.viewmodels

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.print.PostPDFGenerator
import android.text.Html
import android.widget.Toast
import androidx.core.text.toHtml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.omgodse.notally.R
import com.omgodse.notally.legacy.Migrations
import com.omgodse.notally.legacy.XMLUtils
import com.omgodse.notally.miscellaneous.Operations
import com.omgodse.notally.miscellaneous.applySpans
import com.omgodse.notally.preferences.ListInfo
import com.omgodse.notally.preferences.Preferences
import com.omgodse.notally.preferences.SeekbarInfo
import com.omgodse.notally.room.*
import com.omgodse.notally.room.livedata.Content
import com.omgodse.notally.room.livedata.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BaseNoteModel(private val app: Application) : AndroidViewModel(app) {

    private val database = NotallyDatabase.getDatabase(app)
    private val labelDao = database.labelDao
    private val commonDao = database.commonDao
    private val baseNoteDao = database.baseNoteDao

    private val labelCache = HashMap<String, Content>()
    val formatter = getDateFormatter(app)

    var currentFile: File? = null

    val labels = labelDao.getAll()
    val baseNotes = Content(baseNoteDao.getFrom(Folder.NOTES), ::transform)
    val deletedNotes = Content(baseNoteDao.getFrom(Folder.DELETED), ::transform)
    val archivedNotes = Content(baseNoteDao.getFrom(Folder.ARCHIVED), ::transform)

    var folder = Folder.NOTES
        set(value) {
            if (field != value) {
                field = value
                searchResults.fetch(keyword, folder)
            }
        }
    var keyword = String()
        set(value) {
            if (field != value) {
                field = value
                searchResults.fetch(keyword, folder)
            }
        }

    val searchResults = SearchResult(viewModelScope, baseNoteDao, ::transform)

    private val pinned = Header(app.getString(R.string.pinned))
    private val others = Header(app.getString(R.string.others))

    val preferences = Preferences.getInstance(app)

    init {
        viewModelScope.launch {
            val previousNotes = Migrations.getPreviousNotes(app)
            val previousLabels = Migrations.getPreviousLabels(app)
            if (previousNotes.isNotEmpty() || previousLabels.isNotEmpty()) {
                database.withTransaction {
                    labelDao.insert(previousLabels)
                    baseNoteDao.insert(previousNotes)
                    Migrations.clearAllLabels(app)
                    Migrations.clearAllFolders(app)
                }
            }
        }
    }

    fun getNotesByLabel(label: String): Content {
        if (labelCache[label] == null) {
            labelCache[label] = Content(baseNoteDao.getBaseNotesByLabel(label), ::transform)
        }
        return requireNotNull(labelCache[label])
    }


    private fun transform(list: List<BaseNote>): List<Item> {
        if (list.isEmpty()) {
            return list
        } else {
            val firstNote = list[0]
            return if (firstNote.pinned) {
                val newList = ArrayList<Item>(list.size + 2)
                newList.add(pinned)

                val firstUnpinnedNote = list.indexOfFirst { baseNote -> !baseNote.pinned }
                list.forEachIndexed { index, baseNote ->
                    if (index == firstUnpinnedNote) {
                        newList.add(others)
                    }
                    newList.add(baseNote)
                }
                newList
            } else list
        }
    }


    fun savePreference(info: SeekbarInfo, value: Int) {
        executeAsync { preferences.savePreference(info, value) }
    }

    fun savePreference(info: ListInfo, value: String) {
        executeAsync { preferences.savePreference(info, value) }
    }


    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                baseNoteDao.checkpoint(SimpleSQLiteQuery("pragma wal_checkpoint(FULL)"))

                val source = app.getDatabasePath(NotallyDatabase.DatabaseName)

                (app.contentResolver.openOutputStream(uri) as? FileOutputStream)?.use { stream ->
                    stream.channel.truncate(0)

                    val zipStream = ZipOutputStream(stream)
                    val entry = ZipEntry(source.name)
                    zipStream.putNextEntry(entry)

                    val inputStream = FileInputStream(source)
                    inputStream.copyTo(zipStream)
                    inputStream.close()

                    zipStream.closeEntry()
                    zipStream.close()
                }
            }
            Toast.makeText(app, R.string.saved_to_device, Toast.LENGTH_LONG).show()
        }
    }


    fun importZipBackup(uri: Uri) {
        viewModelScope.launch {
            val stream = app.contentResolver.openInputStream(uri)
            if (stream != null) {
                val backupDir = getBackupPath()
                val message = withContext(Dispatchers.IO) {
                    val destination = File(backupDir, "TEMP.zip")
                    copyStreamToFile(stream, destination)

                    val zipFile = ZipFile(destination)
                    val databaseEntry = zipFile.getEntry(NotallyDatabase.DatabaseName)

                    if (databaseEntry != null) {
                        val databaseFile = File(backupDir, NotallyDatabase.DatabaseName)
                        val inputStream = zipFile.getInputStream(databaseEntry)
                        copyStreamToFile(inputStream, databaseFile)

                        try {
                            val database = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)

                            val labelCursor = database.query("Label", null, null, null, null, null, null)
                            val baseNoteCursor = database.query("BaseNote", null, null, null, null, null, null)

                            val labels = convertCursorToList(labelCursor, ::convertCursorToLabel)
                            val baseNotes = convertCursorToList(baseNoteCursor, ::convertCursorToBaseNote)

                            commonDao.importBackup(baseNotes, labels)
                            return@withContext R.string.imported_backup
                        } catch (exception: Exception) {
                            exception.printStackTrace()
                            return@withContext R.string.invalid_backup
                        }

                    } else return@withContext R.string.invalid_backup
                }
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun convertCursorToLabel(cursor: Cursor): Label {
        val value = cursor.getString(cursor.getColumnIndexOrThrow("value"))
        return Label(value)
    }

    private fun convertCursorToBaseNote(cursor: Cursor): BaseNote {
        val typeTmp = cursor.getString(cursor.getColumnIndexOrThrow("type"))
        val folderTmp = cursor.getString(cursor.getColumnIndexOrThrow("folder"))
        val colorTmp = cursor.getString(cursor.getColumnIndexOrThrow("color"))
        val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
        val pinnedTmp = cursor.getInt(cursor.getColumnIndexOrThrow("pinned"))
        val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
        val labelsTmp = cursor.getString(cursor.getColumnIndexOrThrow("labels"))
        val body = cursor.getString(cursor.getColumnIndexOrThrow("body"))
        val spansTmp = cursor.getString(cursor.getColumnIndexOrThrow("spans"))
        val itemsTmp = cursor.getString(cursor.getColumnIndexOrThrow("items"))

        val pinned = when (pinnedTmp) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("pinned must be 0 or 1")
        }

        val type = Type.valueOf(typeTmp)
        val folder = Folder.valueOf(folderTmp)
        val color = Color.valueOf(colorTmp)

        val labels = Converters.jsonToLabels(labelsTmp)
        val spans = Converters.jsonToSpans(spansTmp)
        val items = Converters.jsonToItems(itemsTmp)

        return BaseNote(0, type, folder, color, title, pinned, timestamp, labels, body, spans, items)
    }

    private fun <T> convertCursorToList(cursor: Cursor, convert: (cursor: Cursor) -> T): ArrayList<T> {
        val list = ArrayList<T>(cursor.count)
        while (cursor.moveToNext()) {
            val item = convert(cursor)
            list.add(item)
        }
        cursor.close()
        return list
    }


    private fun copyStreamToFile(inputStream: InputStream, destination: File) {
        val outputStream = FileOutputStream(destination)
        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()
    }


    fun importXmlBackup(uri: Uri) {
        viewModelScope.launch {
            val stream = app.contentResolver.openInputStream(uri)
            if (stream != null) {
                withContext(Dispatchers.IO) {
                    val backup = XMLUtils.readBackupFromStream(stream)
                    commonDao.importBackup(backup.first, backup.second)
                    stream.close()
                }
                Toast.makeText(app, R.string.imported_backup, Toast.LENGTH_LONG).show()
            }
        }
    }


    fun writeCurrentFileToUri(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                (app.contentResolver.openOutputStream(uri) as? FileOutputStream)?.use { stream ->
                    stream.channel.truncate(0)
                    stream.write(requireNotNull(currentFile).readBytes())
                }
            }
            Toast.makeText(app, R.string.saved_to_device, Toast.LENGTH_LONG).show()
        }
    }


    suspend fun getJSONFile(baseNote: BaseNote) = withContext(Dispatchers.IO) {
        val file = File(getExportedPath(), "Untitled.json")
        val json = getJSON(baseNote)
        file.writeText(json)
        file
    }

    suspend fun getTXTFile(baseNote: BaseNote, showDateCreated: Boolean) = withContext(Dispatchers.IO) {
        val file = File(getExportedPath(), "Untitled.txt")
        val text = getTXT(baseNote, showDateCreated)
        file.writeText(text)
        file
    }

    suspend fun getHTMLFile(baseNote: BaseNote, showDateCreated: Boolean) = withContext(Dispatchers.IO) {
        val file = File(getExportedPath(), "Untitled.html")
        val html = getHTML(baseNote, showDateCreated)
        file.writeText(html)
        file
    }

    fun getPDFFile(baseNote: BaseNote, showDateCreated: Boolean, onResult: PostPDFGenerator.OnResult) {
        val file = File(getExportedPath(), "Untitled.pdf")
        val html = getHTML(baseNote, showDateCreated)
        PostPDFGenerator.create(file, html, app, onResult)
    }


    fun colorBaseNote(id: Long, color: Color) = executeAsync { baseNoteDao.updateColor(id, color) }


    fun pinBaseNote(id: Long) = executeAsync { baseNoteDao.updatePinned(id, true) }

    fun unpinBaseNote(id: Long) = executeAsync { baseNoteDao.updatePinned(id, false) }


    fun deleteAllBaseNotes() = executeAsync { baseNoteDao.deleteFrom(Folder.DELETED) }

    fun restoreBaseNote(id: Long) = executeAsync { baseNoteDao.move(id, Folder.NOTES) }

    fun moveBaseNoteToDeleted(id: Long) = executeAsync { baseNoteDao.move(id, Folder.DELETED) }

    fun moveBaseNoteToArchive(id: Long) = executeAsync { baseNoteDao.move(id, Folder.ARCHIVED) }

    fun deleteBaseNoteForever(baseNote: BaseNote) = executeAsync { baseNoteDao.delete(baseNote) }

    fun updateBaseNoteLabels(labels: HashSet<String>, id: Long) = executeAsync { baseNoteDao.updateLabels(id, labels) }


    suspend fun getAllLabelsAsList() = withContext(Dispatchers.IO) { labelDao.getListOfAll() }

    fun deleteLabel(value: String) = executeAsync { commonDao.deleteLabel(value) }

    fun insertLabel(label: Label, onComplete: (success: Boolean) -> Unit) =
        executeAsyncWithCallback({ labelDao.insert(label) }, onComplete)

    fun updateLabel(oldValue: String, newValue: String, onComplete: (success: Boolean) -> Unit) =
        executeAsyncWithCallback({ commonDao.updateLabel(oldValue, newValue) }, onComplete)


    private fun getEmptyFolder(name: String): File {
        val folder = File(app.cacheDir, name)
        if (folder.exists()) {
            folder.listFiles()?.forEach { file -> file.delete() }
        } else folder.mkdir()
        return folder
    }

    private fun getBackupPath() = getEmptyFolder("backup")

    private fun getExportedPath() = getEmptyFolder("exported")


    private fun getJSON(baseNote: BaseNote): String {
        val labels = JSONArray(baseNote.labels)

        val jsonObject = JSONObject()
            .put("type", baseNote.type.name)
            .put("color", baseNote.color.name)
            .put("title", baseNote.title)
            .put("pinned", baseNote.pinned)
            .put("date-created", baseNote.timestamp)
            .put("labels", labels)

        when (baseNote.type) {
            Type.NOTE -> {
                val spans = JSONArray(baseNote.spans.map { representation -> representation.toJSONObject() })
                jsonObject.put("body", baseNote.body)
                jsonObject.put("spans", spans)
            }
            Type.LIST -> {
                val items = JSONArray(baseNote.items.map { item -> item.toJSONObject() })
                jsonObject.put("items", items)
            }
        }

        return jsonObject.toString(2)
    }

    private fun getTXT(baseNote: BaseNote, showDateCreated: Boolean) = buildString {
        val date = formatter.format(baseNote.timestamp)

        val body = when (baseNote.type) {
            Type.NOTE -> baseNote.body
            Type.LIST -> Operations.getBody(baseNote.items)
        }

        if (baseNote.title.isNotEmpty()) {
            append("${baseNote.title}\n\n")
        }
        if (showDateCreated) {
            append("$date\n\n")
        }
        append(body)
    }

    private fun getHTML(baseNote: BaseNote, showDateCreated: Boolean) = buildString {
        val date = formatter.format(baseNote.timestamp)
        val title = Html.escapeHtml(baseNote.title)

        append("<!DOCTYPE html>")
        append("<html><head>")
        append("<meta charset=\"UTF-8\"><title>$title</title>")
        append("</head><body>")
        append("<h2>$title</h2>")

        if (showDateCreated) {
            append("<p>$date</p>")
        }

        when (baseNote.type) {
            Type.NOTE -> {
                val body = baseNote.body.applySpans(baseNote.spans).toHtml()
                append(body)
            }
            Type.LIST -> {
                append("<ol>")
                baseNote.items.forEach { (body) ->
                    append("<li>${Html.escapeHtml(body)}</li>")
                }
                append("</ol>")
            }
        }
        append("</body></html>")
    }


    private fun executeAsync(function: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { function() }
    }

    companion object {

        fun getDateFormatter(context: Context): SimpleDateFormat {
            val locale = context.resources.configuration.locale
            val pattern = when (locale.language) {
                Locale.CHINESE.language,
                Locale.JAPANESE.language -> "yyyy年 MMM d日 (EEE)"
                else -> "EEE d MMM yyyy"
            }
            return SimpleDateFormat(pattern, locale)
        }
    }
}