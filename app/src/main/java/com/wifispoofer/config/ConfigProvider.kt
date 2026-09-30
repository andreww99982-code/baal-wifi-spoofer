package com.wifispoofer.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Запасной канал передачи кэша между процессами — на случай, если
 * XSharedPreferences в вашей версии LSPosed не работает (в LSPosed 2.2+
 * механизм "new XSharedPreferences" объявлен к удалению). ContentProvider
 * — это обычный системный Binder IPC, он не зависит от прав на файл и
 * от того, включена ли в LSPosed поддержка XSharedPreferences.
 *
 * exported=true и без permission — любое приложение на устройстве может
 * прочитать текущий JSON кэша сетей и scope. Это ожидаемо для сценария
 * модуля (хуки как раз и должны его читать), но помните: то же самое
 * может сделать и любое другое установленное приложение.
 */
class ConfigProvider : ContentProvider() {

    companion object {
        const val COL_VALUE = "value"
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = null

    /**
     * uri.lastPathSegment задаёт, что вернуть: "networks" или "scope".
     * Один текстовый столбец COL_VALUE с сырым JSON.
     */
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf(COL_VALUE))
        val prefs = ctx.getSharedPreferences(ConfigManager.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val value = when (uri.lastPathSegment) {
            "networks" -> prefs.getString("cached_networks", "") ?: ""
            "scope" -> prefs.getString("target_scope", "[]") ?: "[]"
            "connected_index" -> prefs.getInt("connected_index", 0).toString()
            else -> ""
        }
        val cursor = MatrixCursor(arrayOf(COL_VALUE))
        cursor.addRow(arrayOf(value))
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
