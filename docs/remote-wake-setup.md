# 遠端喚醒（FCM）設定指南

用平板（遙控端）按一個按鈕，讓家中一直插電的手機（接收端）在區域網路內送出 Wake-on-LAN 封包，把電腦叫醒。

```
平板（遙控端）                Google FCM                 手機（接收端，在家）            電腦
按下按鈕 ──HTTP v1 API──▶  高優先級 data 訊息  ──推播──▶  onMessageReceived()  ──WoL──▶  開機
 (action=wake)                                            （不需常駐前景服務）
```

- **接收端**：用 `FirebaseMessagingService` 收到 `action=wake` 的高優先級 data 訊息後，直接呼叫 App 內建的 Wake-on-LAN 功能，向區域網路廣播魔術封包（連續送 3 輪以提高成功率）。**不使用常駐前景服務**。
- **遙控端**：用 Firebase 服務帳戶 JSON 取得 OAuth2 存取權杖，再呼叫 FCM HTTP v1 API 傳送訊息，並顯示成功或失敗。
- 同一個 APK 同時具備兩種角色，安裝到兩台裝置即可。
- **遙控端不需要在同一個區域網路**：FCM 走網際網路，平板在外面用行動網路或任何 Wi-Fi 都可以。只有「接收端手機」和「被喚醒的電腦」需要在同一個區域網路（Wake-on-LAN 只能在區網內廣播）。

---

## 一、Firebase 主控台設定

### 1. 建立 Firebase 專案

1. 開啟 <https://console.firebase.google.com>，用 Google 帳號登入。
2. 點「**建立專案**（Add project）」，輸入專案名稱（例如 `home-wake`）。
3. Google Analytics 用不到，可以關閉，然後建立專案。

### 2. 新增 Android 應用程式並下載 `google-services.json`

1. 在專案總覽頁，點 Android 圖示（**新增應用程式**）。
2. **Android 套件名稱**依照你要安裝的版本填寫：

   | 版本 | 套件名稱 |
   |---|---|
   | debug（GitHub Actions 預設編出來的） | `com.limelight.noirdebug` |
   | release | `com.limelight.noir` |

   想兩種都用的話，在同一個專案裡各新增一個 Android 應用程式。
3. 應用程式暱稱隨意，**SHA-1 不用填**，按「註冊應用程式」。
4. 下載 **`google-services.json`**。後面「新增 Firebase SDK」等步驟都**不用做**，直接按「下一步／繼續前往主控台」。
   - 若之後又新增了另一個套件名稱，請重新下載一次，新檔案會同時包含全部應用程式。

### 3. 確認 Cloud Messaging API（V1）已啟用

1. 主控台左上角齒輪 →「**專案設定**」→「**雲端通訊**（Cloud Messaging）」分頁。
2. 「**Firebase Cloud Messaging API (V1)**」應顯示為「**已啟用**」。
3. 如果顯示已停用：點右側「⋮」→「在 Google Cloud 控制台中管理 API」→ 點「**啟用**」。
4. 舊版「Cloud Messaging API（舊版）」已停用，不需要也不要使用。

### 4. 建立只能發送 FCM 訊息的服務帳戶（給遙控端）

1. 開啟 <https://console.cloud.google.com/iam-admin/serviceaccounts>，上方專案選擇器選**同一個專案**。
2. 點「**建立服務帳戶**」，名稱填 `wol-sender`，按「建立並繼續」。
3. 「授予這個服務帳戶專案存取權」選擇角色：**Firebase Cloud Messaging API 管理員**（Firebase Cloud Messaging API Admin，`roles/firebasecloudmessaging.admin`）。
   不要選「擁有者」或「編輯者」，權限最小化比較安全。完成。
4. 點進剛建立的服務帳戶 →「**金鑰**」分頁 →「**新增金鑰**」→「**建立新的金鑰**」→ 類型選 **JSON** →「建立」，瀏覽器會下載一個 `.json` 檔。
5. 把這個 JSON 檔傳到平板（USB、雲端硬碟都可以）。
   - 若找不到「建立金鑰」或被拒絕，通常是組織政策 `iam.disableServiceAccountKeyCreation` 擋住了，需由組織管理員放行。
   - 也可以在 Firebase 主控台「專案設定 → 服務帳戶 → 產生新的私密金鑰」取得金鑰，但那是 `firebase-adminsdk` 帳戶，權限大很多，**不建議**。

---

## 二、把 `google-services.json` 編進 APK

> App 在沒有 `app/google-services.json` 時仍然可以編譯，只是遠端喚醒不會有 FCM 功能（設定裡取得 token 會提示「此版本尚未設定 Firebase」）。

**方法 A：GitHub Actions（建議）**

1. 到 GitHub 倉庫 → **Settings → Secrets and variables → Actions → New repository secret**。
2. Name 填 `GOOGLE_SERVICES_JSON`，Secret 貼上 `google-services.json` 的**完整內容**，儲存。
3. 到 **Actions → Build debug APK → Run workflow**（或推送一個新的提交）重新編譯。
4. 從該次執行頁面下方的 Artifacts 下載 APK。

**方法 B：直接放進倉庫**

把檔案放到 `app/google-services.json` 並提交。檔內的 API 金鑰不是機密（它受套件名稱限制），但如果倉庫是公開的，仍建議用方法 A。

> 注意：Firebase 的套件名稱必須和你編譯的版本一致。debug 版是 `com.limelight.noirdebug`，若沒註冊會在編譯時出現 `No matching client found for package name`。

---

## 三、接收端（裝在家裡一直插電的手機）

1. 安裝含 `google-services.json` 編出的 APK，**第一次開啟**時會跳出「本機 FCM token」視窗，按「**複製**」。
   - 之後想再複製：**設定 → 遠端喚醒 → 本機 FCM token**，點一下就會取得並複製。
   - 重新安裝 App 或清除資料後 token 會改變，需要重新複製並更新到遙控端。
2. 到 **設定 → 遠端喚醒**：
   - 在「**允許喚醒的電腦 MAC 位址**」填入要喚醒的電腦，**每行一個**，例如 `AA:BB:CC:DD:EE:FF`（在電腦上執行 `ipconfig /all`，看有線網卡的「實體位址」）。
   - 這份清單同時是「白名單」：訊息指定了不在清單內的電腦會被忽略；沒有指定電腦時（例如右上角的電源按鈕），會喚醒清單中的**第一台**。
   - 打開「**啟用喚醒接收端**」。
3. 電腦端要先能被 Wake-on-LAN 喚醒：
   - BIOS／UEFI 開啟 Wake on LAN（或 PCIe 喚醒）。
   - Windows 裝置管理員 → 網路介面卡 → 內容 → 電源管理，勾選「允許這個裝置讓電腦脫離待命模式」與「只允許魔術封包讓電腦脫離待命模式」。
   - 電腦建議用有線網路，並與手機在同一個區域網路。
4. 手機設定：
   - **設定 → 應用程式 → 本 App → 電池**，設為「**不受限制**」（或「不要最佳化」）。
   - 保持 Wi-Fi 連線；**不要在「強制停止」後期待收到推播**（被強制停止的 App 收不到 FCM，開啟一次 App 即可恢復）。

## 四、遙控端（平板）

1. 到 **設定 → 遠端喚醒**：
   - 「**接收端 FCM token**」：貼上剛才從手機複製的 token。
   - 「**Firebase 服務帳戶 JSON**」：點一下，選擇第一部分第 4 步下載的 JSON 檔。成功後會顯示專案名稱和服務帳戶 email。
2. 兩項都設定好之後，主畫面右上角（「+」按鈕左邊）會出現一個**電源圖示**按鈕。
3. 點按鈕會傳送喚醒指令（喚醒接收端清單中的第一台），並以提示訊息顯示「喚醒指令已送出」或失敗原因。
   也可以在設定裡點「傳送喚醒指令」測試。
4. **只喚醒指定的某一台**：在主畫面**長按**那台電腦，選「**遠端喚醒（FCM）**」。
   - 這個選項和原本的「傳送 Wake-On-LAN 請求」放在一起，只有電腦處於離線或未知狀態、且已設定好遙控端時才會出現。
   - 平板必須曾經連上過那台電腦（這樣才記得它的 MAC 位址），否則會提示「無法喚醒電腦，因為未儲存 MAC 位址」。
   - 該電腦的 MAC 位址必須已加入接收端的允許清單。
5. 想清除金鑰時，點「移除服務帳戶 JSON」。

---

## 五、疑難排解

| 顯示的訊息 | 可能原因與處理方式 |
|---|---|
| 驗證失敗或權限不足（`PERMISSION_DENIED`、HTTP 403） | 服務帳戶沒有「Firebase Cloud Messaging API 管理員」角色；或 FCM API (V1) 沒啟用；或服務帳戶和 App 不是同一個專案。 |
| 驗證失敗（`invalid_grant`、HTTP 400/401） | **平板的日期時間不準**（請開啟自動日期與時間）；或該金鑰已在 Cloud Console 被刪除。 |
| 接收端 token 無效（`UNREGISTERED`、HTTP 404） | token 貼錯、含多餘字元，或接收端重新安裝／清除資料後 token 已改變。請重新複製。 |
| 網路連線失敗 | 平板沒有網路，或連不到 `oauth2.googleapis.com`、`fcm.googleapis.com`。 |
| 顯示「已送出」但電腦沒醒 | 接收端沒開啟「啟用喚醒接收端」、MAC 位址錯誤、電腦沒開啟 WoL、手機和電腦不在同一個區域網路，或手機被省電機制限制。可用 `adb logcat \| grep RemoteWake` 查看接收端日誌。 |

## 六、運作細節與限制

- 訊息內容：`data = {"action": "wake", "ts": "<毫秒時間>", "mac": "<指定電腦的 MAC，可省略>"}`，`android.priority = HIGH`，`android.ttl = 60s`（超過 60 秒仍未送達就丟棄，避免過期的喚醒指令突然生效）。
- 接收端對同一台電腦 3 秒內的重複喚醒訊息會被忽略；每次收到訊息會連續送 3 輪魔術封包，間隔 0.3 秒。
- 魔術封包使用 App 內建的 Wake-on-LAN 功能，送到 `255.255.255.255`（區域網路廣播）。
- 高優先級 FCM 訊息可在手機 Doze（省電）模式下喚醒 App 並暫時允許連網，因此不需要前景服務；但 Google 可能對長期大量傳送高優先級訊息降級，請只在需要時按。
- 服務帳戶 JSON 儲存在 App 私有儲存區（`SharedPreferences`），已排除在雲端備份與裝置轉移之外，但**沒有額外加密**。
  - 請務必使用只有 FCM 發送權限的專用服務帳戶。
  - 遺失平板時，到 Cloud Console 的服務帳戶「金鑰」分頁刪除該金鑰即可。
  - 接收端 token 也請勿公開；要送出喚醒指令，需要同時擁有 token 與服務帳戶金鑰。
