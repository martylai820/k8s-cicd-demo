# SMB 伺服器 Android App

將 Android 手機變成 SMB v2/v3 檔案伺服器，讓同網段的 Windows / macOS / Linux 設備存取手機內的檔案。

## 功能特色

| 功能 | 說明 |
|------|------|
| SMB v2+ 協定 | 純 Kotlin 實作，支援 SMB 2.0.2、2.1、3.0、3.1.1 |
| 自訂 Port | 預設 2445（任何 port > 1024 不需要 root） |
| 驗證模式 | 可切換匿名存取或帳號/密碼（NTLMv2）|
| 資料夾選擇 | 預設常用路徑 + SAF 自訂選擇 |
| 唯讀模式 | 防止客戶端修改/刪除檔案 |
| 前景 Service | App 最小化後伺服器繼續運行 |
| 通知欄 | 顯示狀態，隨時可點擊停止 |

## 系統需求

- Android 10+ (API 29)
- 與電腦連接同一 Wi-Fi 網路

## 建置方式

### 1. 使用 Android Studio

1. 開啟 `smb-server-app/` 資料夾
2. 等待 Gradle 同步完成
3. 點擊 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. APK 位於 `app/build/outputs/apk/debug/app-debug.apk`

### 2. 使用命令列

```bash
cd smb-server-app
./gradlew assembleDebug
# APK 位於 app/build/outputs/apk/debug/
```

## 使用方法

### 手機端

1. 安裝 APK
2. 授予「所有檔案存取」權限（設定 → 應用程式 → SMB 伺服器 → 檔案存取）
3. 選擇要分享的資料夾
4. 設定 Port（預設 2445）
5. 選擇驗證模式
6. 點擊「啟動伺服器」

### Windows 端連線

```
Win + R → 輸入 \\手機IP:2445\share → Enter
```
例如：`\\192.168.1.100:2445\share`

如使用非標準 Port（非 445），Windows 格式為：
```
\\192.168.1.100@2445\share
```
或透過「對應網路磁碟機」輸入完整路徑。

### macOS 端連線

```
Finder → 前往 → 連線伺服器
smb://192.168.1.100:2445/share
```

### Linux 端連線

```bash
# 掛載
sudo mount -t cifs //192.168.1.100/share /mnt/phone \
  -o port=2445,username=android,password=yourpass,vers=2.0

# 或使用 smbclient
smbclient //192.168.1.100/share -p 2445 -U android
```

## 注意事項

- Port 445 為 SMB 標準 Port，需要 root 權限
- 建議在受信任的 Wi-Fi 網路下使用
- 匿名模式下，所有同網段設備均可存取
- 帳號密碼使用 NTLMv2 加密傳輸

## 技術架構

```
TCP Socket (指定 Port)
  └─ NetBIOS Session Service 封裝
       └─ SMB2 協定處理 (純 Kotlin)
            ├─ SPNEGO/NTLM 認證
            ├─ 檔案系統操作 (java.io.File)
            └─ Android Foreground Service
```

## 權限說明

| 權限 | 用途 |
|------|------|
| `INTERNET` | 建立 TCP 伺服器 |
| `ACCESS_WIFI_STATE` | 取得 Wi-Fi IP 位址 |
| `MANAGE_EXTERNAL_STORAGE` | 存取整體儲存空間 |
| `FOREGROUND_SERVICE` | 背景持續運行 |
| `POST_NOTIFICATIONS` | 顯示通知欄狀態 |
| `WAKE_LOCK` | 防止手機休眠中斷連線 |
