package dev.obdmaru.scanner

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class ReportProvider: ContentProvider() {
    override fun onCreate()=true
    private fun file(uri: Uri): File {
        require(uri.pathSegments.size==1)
        val root=File(requireNotNull(context).filesDir,"sessions").canonicalFile
        val file=File(root,requireNotNull(uri.lastPathSegment)).canonicalFile
        require(file.parentFile==root && file.extension in setOf("txt","jsonl") && file.isFile)
        return file
    }
    override fun getType(uri: Uri)=if(file(uri).extension=="txt")"text/plain" else "application/x-ndjson"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode=="r"); return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f=file(uri); val cols=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(cols.map{when(it){OpenableColumns.DISPLAY_NAME->f.name;OpenableColumns.SIZE->f.length();else->null}}) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri?=throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?)=throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?)=throw UnsupportedOperationException()
}
