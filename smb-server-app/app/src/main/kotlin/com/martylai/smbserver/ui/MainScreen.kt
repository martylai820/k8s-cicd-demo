package com.martylai.smbserver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.martylai.smbserver.data.ServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showFolderMenu by remember { mutableStateOf(false) }

    // SAF folder picker
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val path = uriToPath(context, it)
            if (path != null) viewModel.onSharePathChange(path)
        }
    }

    // Error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMB 檔案伺服器", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Status card ─────────────────────────────────────────────────
            ServerStatusCard(
                isRunning = state.isRunning,
                ipAddress = state.ipAddress,
                connectionInfo = state.connectionInfo,
                onCopy = {
                    if (state.connectionInfo.isNotEmpty()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SMB路徑", state.connectionInfo))
                        Toast.makeText(context, "已複製連線位址", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // ── Start / Stop button ─────────────────────────────────────────
            Button(
                onClick = {
                    if (state.isRunning) viewModel.stopServer(context)
                    else viewModel.startServer(context)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRunning)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = if (state.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.isRunning) "停止伺服器" else "啟動伺服器",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
            }

            HorizontalDivider()

            // ── Configuration section ───────────────────────────────────────
            Text("基本設定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // Port
            OutlinedTextField(
                value = state.editPort,
                onValueChange = viewModel::onPortChange,
                label = { Text("監聽 Port") },
                leadingIcon = { Icon(Icons.Default.Router, null) },
                supportingText = { Text("預設 2445（Port 445 需 root 權限）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRunning,
                singleLine = true
            )

            // Server name
            OutlinedTextField(
                value = state.editServerName,
                onValueChange = viewModel::onServerNameChange,
                label = { Text("伺服器名稱") },
                leadingIcon = { Icon(Icons.Default.Computer, null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRunning,
                singleLine = true
            )

            // Share name
            OutlinedTextField(
                value = state.editShareName,
                onValueChange = viewModel::onShareNameChange,
                label = { Text("分享名稱 (Share Name)") },
                leadingIcon = { Icon(Icons.Default.Share, null) },
                supportingText = { Text("連線路徑中顯示的資料夾名稱") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRunning,
                singleLine = true
            )

            // ── Folder selection ────────────────────────────────────────────
            Text("分享資料夾", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // Preset paths dropdown
            ExposedDropdownMenuBox(
                expanded = showFolderMenu,
                onExpandedChange = { if (!state.isRunning) showFolderMenu = it }
            ) {
                OutlinedTextField(
                    value = state.editSharePath,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("分享路徑") },
                    leadingIcon = { Icon(Icons.Default.Folder, null) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showFolderMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    enabled = !state.isRunning
                )
                ExposedDropdownMenu(
                    expanded = showFolderMenu,
                    onDismissRequest = { showFolderMenu = false }
                ) {
                    // Preset paths
                    ServerConfig.PRESET_PATHS.forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(preset.label, fontWeight = FontWeight.SemiBold)
                                    Text(preset.path, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                viewModel.onSharePathChange(preset.path)
                                showFolderMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Folder, null) }
                        )
                    }
                    HorizontalDivider()
                    // Custom picker
                    DropdownMenuItem(
                        text = { Text("自訂資料夾…") },
                        onClick = {
                            showFolderMenu = false
                            folderPicker.launch(null)
                        },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) }
                    )
                }
            }

            // ── Access control ──────────────────────────────────────────────
            Text("存取控制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // Read-only toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("唯讀模式", fontWeight = FontWeight.SemiBold)
                        Text("連線裝置只能讀取，不能修改檔案",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = state.editReadOnly,
                        onCheckedChange = viewModel::onReadOnlyChange,
                        enabled = !state.isRunning
                    )
                }
            }

            // Auth toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("需要帳號密碼", fontWeight = FontWeight.SemiBold)
                        Text("關閉則允許匿名連線",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = state.editRequireAuth,
                        onCheckedChange = viewModel::onRequireAuthChange,
                        enabled = !state.isRunning
                    )
                }
            }

            // Username / Password (shown only when auth is required)
            AnimatedVisibility(visible = state.editRequireAuth) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.editUsername,
                        onValueChange = viewModel::onUsernameChange,
                        label = { Text("使用者名稱") },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isRunning,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.editPassword,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text("密碼") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = viewModel::onToggleShowPassword) {
                                Icon(
                                    imageVector = if (state.showPassword) Icons.Default.VisibilityOff
                                                  else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        visualTransformation = if (state.showPassword) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isRunning,
                        singleLine = true
                    )
                }
            }

            // ── Help card ───────────────────────────────────────────────────
            HelpCard()

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Server Status Card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ServerStatusCard(
    isRunning: Boolean,
    ipAddress: String,
    connectionInfo: String,
    onCopy: () -> Unit
) {
    val containerColor = if (isRunning)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.CloudDone else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isRunning) "伺服器運行中" else "伺服器已停止",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isRunning && connectionInfo.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "連線位址（點擊複製）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .clickable(onClick = onCopy)
                        .padding(12.dp)
                ) {
                    Text(
                        text = connectionInfo,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "在 Windows: 開啟「執行」(Win+R) 貼上上方位址\n在 macOS: Finder → 前往 → 連線伺服器",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else if (!isRunning) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "設定後點擊「啟動伺服器」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Help card
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun HelpCard() {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Help, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text("使用說明", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = MaterialTheme.colorScheme.secondary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HelpItem("1️⃣", "確認手機與電腦在同一 Wi-Fi 網路")
                    HelpItem("2️⃣", "選擇要分享的資料夾")
                    HelpItem("3️⃣", "設定 Port（預設 2445，無需 root）")
                    HelpItem("4️⃣", "點擊「啟動伺服器」")
                    HelpItem("5️⃣", "複製連線位址至 Windows 或 macOS")
                    Spacer(Modifier.height(4.dp))
                    Text("⚠️ Port 445 為標準 SMB port 需要 root 權限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    Text("⚠️ 首次啟動需授予「所有檔案存取」權限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun HelpItem(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(emoji, modifier = Modifier.width(28.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Convert SAF URI to filesystem path (best-effort for common URIs)
// ─────────────────────────────────────────────────────────────────────────────
private fun uriToPath(context: Context, uri: Uri): String? {
    val path = uri.path ?: return null
    // For primary storage: content://com.android.externalstorage.documents/tree/primary:XXX
    if (uri.authority == "com.android.externalstorage.documents") {
        val docPath = path.substringAfter("/tree/")
        val segments = docPath.split(":")
        return if (segments.size >= 2) {
            val base = Environment.getExternalStorageDirectory().absolutePath
            if (segments[1].isEmpty()) base else "$base/${segments[1]}"
        } else {
            Environment.getExternalStorageDirectory().absolutePath
        }
    }
    return null
}
