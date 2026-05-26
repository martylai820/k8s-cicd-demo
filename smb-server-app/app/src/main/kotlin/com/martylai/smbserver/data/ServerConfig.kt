package com.martylai.smbserver.data

import android.os.Environment

/**
 * SMB server configuration.
 * Stored persistently via DataStore (see ServerConfigRepository).
 */
data class ServerConfig(
    /** TCP port to listen on. Default 2445 (445 requires root on Android). */
    val port: Int = 2445,
    /** Name of the SMB share (appears in Windows Explorer path). */
    val shareName: String = "share",
    /** SMB server name (NetBIOS / display name). */
    val serverName: String = "AndroidSMB",
    /** Local filesystem path to expose as the share root. */
    val sharePath: String = Environment.getExternalStorageDirectory()?.absolutePath
        ?: "/storage/emulated/0",
    /** Whether to require username+password authentication (false = anonymous). */
    val requireAuth: Boolean = false,
    /** SMB username for authenticated mode. */
    val username: String = "android",
    /** SMB password for authenticated mode. */
    val password: String = "",
    /** Whether to expose the share as read-only. */
    val readOnly: Boolean = false
) {
    companion object {
        val DEFAULT = ServerConfig()

        // Preset share paths
        val PRESET_PATHS = listOf(
            PresetPath("整體儲存空間", Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"),
            PresetPath("下載", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath ?: "/storage/emulated/0/Download"),
            PresetPath("DCIM / 相機", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?.absolutePath ?: "/storage/emulated/0/DCIM"),
            PresetPath("文件", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.absolutePath ?: "/storage/emulated/0/Documents"),
            PresetPath("音樂", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.absolutePath ?: "/storage/emulated/0/Music"),
            PresetPath("圖片", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)?.absolutePath ?: "/storage/emulated/0/Pictures"),
            PresetPath("影片", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)?.absolutePath ?: "/storage/emulated/0/Movies"),
        )
    }

    data class PresetPath(val label: String, val path: String)
}
