# NotiStack for LINE (LineNotification-EX)

NotiStack 是一款專為改善 Android 上 LINE 通知體驗設計的增強輔助工具。

本專案堅持 **Local-First（本機優先）** 原則，`AndroidManifest.xml` 中**絕不宣告 INTERNET 網路存取權限**，在系統層級杜絕任何對話或隱私外洩的疑慮。

---

## 核心特性 (Phase 1 現狀)

- **模組化架構**：採用 Clean Architecture 分層設計，通知監聽、資料解析、帳號識別與 UI 完全解耦。
- **雙開 LINE 帳號自動辨識**：
  - 透過 `sbn.getUser()` 與 `sbn.getUid()` 複合鍵，完美識別主帳號 (User 0) 與雙開分身（例如 Samsung Dual Messenger 的 User 95、小米雙開的 User 999、Android Work Profile 等）。
- **結構化通知解析**：
  - 自動提取聊天室名稱、群組識別、發送者、內文、時間戳與 RemoteInput 直接回覆 Actions。
  - 監聽系統通知移除事件並轉換為人類可讀原因碼（例如捕捉 `REASON_APP_CANCEL` 標記已讀）。
- **實機驗證 UI**：
  - Jetpack Compose Material 3 現代化介面。
  - 整合權限狀態檢測與一鍵系統授權導向。
  - 即時日誌串流與內建「發送模擬通知測試」按鈕。

---

## 系統需求與技術棧

- **OS 相容性**：Android 8.0 ~ Android 15 (minSdk 26, targetSdk 34)
- **開發語言**：Kotlin 2.0+ (100% Coroutines & StateFlow)
- **UI 框架**：Jetpack Compose (Material 3)
- **建置工具**：Gradle 8.10.2 + Android Gradle Plugin 8.5.2
- **Java 版本**：Java 17 (JBR 17/21 推薦)

---

## 開發者指引

1. 使用 Android Studio 開啟專案根目錄。
2. 等待 Gradle Sync 完成。
3. 連接 Android 裝置並執行 `Run 'app'`。
4. 於 App 內授予「通知存取權限」即可開始使用。
