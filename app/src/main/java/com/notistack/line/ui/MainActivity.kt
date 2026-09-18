package com.notistack.line.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.notistack.line.core.model.CapturedNotification
import com.notistack.line.core.model.EventType
import com.notistack.line.data.repository.NotificationLogRepository
import com.notistack.line.parser.NotificationParser
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createTestNotificationChannel(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    companion object {
        const val TEST_CHANNEL_ID = "notistack_test_channel"

        fun createTestNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    TEST_CHANNEL_ID,
                    "NotiStack 測試通知管道",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "用於驗證通知攔截、解析與 RemoteInput 回覆功能"
                }
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val isServiceConnected by NotificationLogRepository.isServiceConnected.collectAsState()
    val logs by NotificationLogRepository.logs.collectAsState()
    val detectedAccounts by NotificationLogRepository.detectedAccounts.collectAsState()

    var isListenerPermissionGranted by remember {
        mutableStateOf(isNotificationListenerEnabled(context))
    }

    var hasPostNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPostNotificationPermission = isGranted
    }

    // 每次畫面恢復時檢查權限狀態
    DisposableEffect(Unit) {
        isListenerPermissionGranted = isNotificationListenerEnabled(context)
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "NotiStack for LINE",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { NotificationLogRepository.clearLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空日誌")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 權限與服務狀態卡片
            StatusCard(
                isListenerPermissionGranted = isListenerPermissionGranted,
                isServiceConnected = isServiceConnected,
                hasPostNotificationPermission = hasPostNotificationPermission,
                onGrantListener = {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    context.startActivity(intent)
                },
                onRequestPostNotification = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )

            // 雙開帳號偵測卡片 (若已偵測到)
            if (detectedAccounts.isNotEmpty()) {
                AccountsCard(accounts = detectedAccounts.values.toList())
            }

            // 實機驗證動作按鈕列
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        sendTestNotification(context)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("發送模擬通知測試", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        isListenerPermissionGranted = isNotificationListenerEnabled(context)
                    }
                ) {
                    Text("重新整理狀態", fontSize = 13.sp)
                }
            }

            // 即時日誌清單標題
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "即時捕捉通知日誌 (${logs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "最新在最上方",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // 日誌 LazyColumn
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "尚未收到 LINE 或測試通知",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "1. 確認上方已授權「通知存取權限」\n2. 點擊「發送模擬通知測試」或在另一台手機傳送 LINE 訊息\n3. 系統將即時解析帳號 (User ID)、聊天室與 RemoteInput 回覆結構",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs, key = { it.id }) { logItem ->
                        LogItemCard(log = logItem)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    isListenerPermissionGranted: Boolean,
    isServiceConnected: Boolean,
    hasPostNotificationPermission: Boolean,
    onGrantListener: () -> Unit,
    onRequestPostNotification: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isListenerPermissionGranted && isServiceConnected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isListenerPermissionGranted && isServiceConnected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                    Text("核心通知服務：運作正常", fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                } else {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        if (!isListenerPermissionGranted) "未授權通知存取權限" else "服務等待系統綁定中",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (!isListenerPermissionGranted) {
                Text(
                    "NotiStack 必須存取通知以解析 LINE 訊息、雙開帳號並提供堆疊通知與已讀同步。完全無需網路權限。",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = onGrantListener,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("前往系統設定授權通知存取")
                }
            }

            if (!hasPostNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("發布通知權限未開啟", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRequestPostNotification) {
                        Text("授權通知發布")
                    }
                }
            }
        }
    }
}

@Composable
fun AccountsCard(accounts: List<com.notistack.line.core.model.LineAccount>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("已偵測到的 LINE 帳號執行個體 (${accounts.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            accounts.forEach { acc ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "• ${acc.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "User ID: ${acc.userId} | UID: ${acc.uid}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
fun LogItemCard(log: CapturedNotification) {
    val isPosted = log.eventType == EventType.POSTED
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = timeFormat.format(Date(log.postTime))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPosted)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Header Row: Type Badge + Time + User ID
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = if (isPosted) Color(0xFF2E7D32) else Color(0xFFC62828),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isPosted) "POSTED" else "REMOVED",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = if (log.userId == 0) "主帳號 [User 0]" else "分身 [User ${log.userId}]",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = timeStr,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Title / Chat room
            Text(
                text = log.title ?: "（無標題）",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            // Message text
            if (!log.text.isNullOrBlank()) {
                Text(
                    text = log.text,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Removed Reason (若是 REMOVED 事件)
            if (!isPosted && log.removeReason != null) {
                val desc = NotificationParser.getRemovalReasonDescription(log.removeReason)
                Text(
                    text = "移除原因: $desc",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (log.removeReason == 8) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
            }

            // Footer Details (Actions & RemoteInput)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (log.hasRemoteInputReply) {
                    Text(
                        text = "✓ 支援直接回覆 (RemoteInput)",
                        fontSize = 11.sp,
                        color = Color(0xFF1565C0),
                        fontWeight = FontWeight.Medium
                    )
                } else if (log.actionTitles.isNotEmpty()) {
                    Text(
                        text = "動作: ${log.actionTitles.joinToString()}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Text(
                    text = "UID: ${log.uid}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * 檢查通知存取權限是否已被使用者啟用
 */
fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
    return enabledListeners.contains(context.packageName)
}

/**
 * 發送一則包含 RemoteInput 回覆 Action 的本機測試通知，用於驗證監聽服務與回覆解析
 */
fun sendTestNotification(context: Context) {
    val remoteInput = RemoteInput.Builder("test_reply_key")
        .setLabel("輸入測試回覆文字...")
        .build()

    val replyIntent = Intent(context, MainActivity::class.java)
    val replyPendingIntent = PendingIntent.getActivity(
        context,
        1001,
        replyIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    val replyAction = NotificationCompat.Action.Builder(
        android.R.drawable.ic_menu_send,
        "回覆 (RemoteInput 測試)",
        replyPendingIntent
    ).addRemoteInput(remoteInput).build()

    val notification = NotificationCompat.Builder(context, MainActivity.TEST_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle("測試聊天室 (NotiStack)")
        .setContentText("這是一則模擬的即時通訊訊息，用於驗證 Listener 與 User ID 辨識")
        .setSubText("模擬訊息")
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .addAction(replyAction)
        .build()

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(9999, notification)
}
