package com.martylai.smbserver.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.martylai.smbserver.data.ServerConfig
import com.martylai.smbserver.data.ServerConfigRepository
import com.martylai.smbserver.service.SmbServerService
import com.martylai.smbserver.service.toParcel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface

data class UiState(
    val config: ServerConfig = ServerConfig.DEFAULT,
    val isRunning: Boolean = false,
    val ipAddress: String = "",
    val connectionInfo: String = "",
    val errorMessage: String? = null,
    // Editing state (mirrors config, used in form)
    val editPort: String = ServerConfig.DEFAULT.port.toString(),
    val editShareName: String = ServerConfig.DEFAULT.shareName,
    val editServerName: String = ServerConfig.DEFAULT.serverName,
    val editSharePath: String = ServerConfig.DEFAULT.sharePath,
    val editRequireAuth: Boolean = ServerConfig.DEFAULT.requireAuth,
    val editUsername: String = ServerConfig.DEFAULT.username,
    val editPassword: String = ServerConfig.DEFAULT.password,
    val editReadOnly: Boolean = ServerConfig.DEFAULT.readOnly,
    val showPassword: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ServerConfigRepository(application)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Load persisted config
        viewModelScope.launch {
            repo.config.collect { config ->
                _state.update { it.copy(
                    config = config,
                    editPort       = config.port.toString(),
                    editShareName  = config.shareName,
                    editServerName = config.serverName,
                    editSharePath  = config.sharePath,
                    editRequireAuth = config.requireAuth,
                    editUsername   = config.username,
                    editPassword   = config.password,
                    editReadOnly   = config.readOnly
                ) }
            }
        }

        // Poll server state and IP
        viewModelScope.launch {
            while (true) {
                val running = SmbServerService.isRunning
                val port    = SmbServerService.currentPort
                val ip      = getWifiIpAddress(application)
                _state.update { it.copy(
                    isRunning = running,
                    ipAddress = ip,
                    connectionInfo = if (running && ip.isNotEmpty())
                        "smb://$ip:$port/${_state.value.config.shareName}"
                    else ""
                ) }
                delay(1000)
            }
        }
    }

    // ─── Form field updates ───────────────────────────────────────────────────
    fun onPortChange(v: String)       = _state.update { it.copy(editPort = v) }
    fun onShareNameChange(v: String)  = _state.update { it.copy(editShareName = v) }
    fun onServerNameChange(v: String) = _state.update { it.copy(editServerName = v) }
    fun onSharePathChange(v: String)  = _state.update { it.copy(editSharePath = v) }
    fun onRequireAuthChange(v: Boolean) = _state.update { it.copy(editRequireAuth = v) }
    fun onUsernameChange(v: String)   = _state.update { it.copy(editUsername = v) }
    fun onPasswordChange(v: String)   = _state.update { it.copy(editPassword = v) }
    fun onReadOnlyChange(v: Boolean)  = _state.update { it.copy(editReadOnly = v) }
    fun onToggleShowPassword()        = _state.update { it.copy(showPassword = !it.showPassword) }
    fun clearError()                  = _state.update { it.copy(errorMessage = null) }

    // ─── Start server ─────────────────────────────────────────────────────────
    fun startServer(context: Context) {
        val st = _state.value
        val port = st.editPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _state.update { it.copy(errorMessage = "請輸入有效的 port (1-65535)") }
            return
        }
        if (st.editSharePath.isBlank()) {
            _state.update { it.copy(errorMessage = "請選擇分享資料夾") }
            return
        }
        if (st.editRequireAuth && st.editUsername.isBlank()) {
            _state.update { it.copy(errorMessage = "請輸入使用者名稱") }
            return
        }
        if (st.editRequireAuth && st.editPassword.isBlank()) {
            _state.update { it.copy(errorMessage = "請輸入密碼") }
            return
        }

        val config = ServerConfig(
            port        = port,
            shareName   = st.editShareName.ifBlank { "share" },
            serverName  = st.editServerName.ifBlank { "AndroidSMB" },
            sharePath   = st.editSharePath,
            requireAuth = st.editRequireAuth,
            username    = st.editUsername,
            password    = st.editPassword,
            readOnly    = st.editReadOnly
        )

        viewModelScope.launch { repo.save(config) }

        val intent = Intent(context, SmbServerService::class.java).apply {
            action = SmbServerService.ACTION_START
            putExtra(SmbServerService.EXTRA_CONFIG, config.toParcel())
        }
        context.startForegroundService(intent)
    }

    // ─── Stop server ──────────────────────────────────────────────────────────
    fun stopServer(context: Context) {
        val intent = Intent(context, SmbServerService::class.java).apply {
            action = SmbServerService.ACTION_STOP
        }
        context.startService(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun getWifiIpAddress(context: Context): String {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return ""
            val caps = cm.getNetworkCapabilities(network) ?: return ""
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return ""
        } catch (_: Exception) {}

        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { ni -> ni.inetAddresses.toList() }
                ?.firstOrNull { addr ->
                    !addr.isLoopbackAddress &&
                    addr.hostAddress?.contains(':') == false &&  // IPv4 only
                    addr.isSiteLocalAddress
                }
                ?.hostAddress ?: ""
        } catch (_: Exception) { "" }
    }
}
