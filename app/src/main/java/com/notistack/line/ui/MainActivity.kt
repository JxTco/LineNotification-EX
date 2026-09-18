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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.notistack.line.core.model.ChatConversation
import com.notistack.line.core.model.EventType
import com.notistack.line.data.local.NotiStackDatabase
import com.notistack.line.data.preferences.NotificationMode
import com.notistack.line.data.preferences.SettingsManager
import com.notistack.line.data.repository.NotificationLogRepository
import com.notistack.line.parser.NotificationParser
import com.notistack.line.service.NotificationDispatcher
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val database = remember { NotiStackDatabase.getInstance(context) }
    val settingsManager = remember { SettingsManager.getInstance(context) }

    val isServiceConnected by NotificationLogRepository.isServiceConnected.collectAsState()
    val logs by NotificationLogRepository.logs.collectAsState()
    val detectedAccounts by NotificationLogRepository.detectedAccounts.collectAsState()
    val chats by database.chatsFlow.collectAsState()

    val currentMode by settingsManager.notificationMode.collectAsState()
    val isGlobalStackEnabled by settingsManager.isGlobalStackEnabled.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }

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

    DisposableEffect(Unit) {
        isListenerPermissionGranted = isNotificationListenerEnabled(context)
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("NotiStack for LINE", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    if (selectedTabIndex == 1) {
                        IconButton(onClick = { NotificationLogRepository.clearLogs() }) {
                            Icon(Icons.Default.Delete, contentDescription = "清空日誌")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 頂部常駐權限狀態警示
            StatusBanner(
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

            // 分頁 Tab 列
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("通知模式與聊天室 (${chats.size})") },
                    icon = { Icon(Icons.Default.Forum, contentDescription = null) }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("即時事件日誌 (${logs.size})") },
                    icon = { Icon(Icons.Default.List, contentDescription = null) }
                )
            }

            if (selectedTabIndex == 0) {
                // Tab 0: 模式設定與個別聊天室管理
                SettingsAndChatsView(
                    currentMode = currentMode,
                    isGlobalStackEnabled = isGlobalStackEnabled,
                    chats = chats,
                    detectedAccounts = detectedAccounts.values.toList(),
                    onModeChange = { settingsManager.setNotificationMode(it) },
                    onGlobalStackToggle = { settingsManager.setGlobalStackEnabled(it) },
                    onChatStackToggle = { chat, enabled ->
                        scope.launch { database.setChatStackEnabled(chat.chatKey, enabled) }
                    },
                    onClearChat = { chat ->
                        scope.launch {
                            database.clearChatMessages(chat.chatKey)
                            NotificationDispatcher.getInstance(context).cancelStackedNotification(chat.chatKey)
                        }
                    }
                )
            } else {
                // Tab 1: 即時事件日誌與測試模擬
                LogsView(
                    logs = logs,
                    onSendTest = { sendTestNotification(context) },
                    onRefresh = { isListenerPermissionGranted = isNotificationListenerEnabled(context) }
                )
            }
        }
    }
}

@Composable
fun StatusBanner(
    isListenerPermissionGranted: Boolean,
    isServiceConnected: Boolean,
    hasPostNotificationPermission: Boolean,
    onGrantListener: () -> Unit,
    onRequestPostNotification: () -> Unit
) {
    if (!isListenerPermissionGranted || !isServiceConnected || !hasPostNotificationPermission) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        if (!isListenerPermissionGranted) "未授權通知存取權限"
                        else if (!hasPostNotificationPermission) "未授權通知發布權限"
                        else "通知服務等待系統啟動中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Button(
                    onClick = {
                        if (!isListenerPermissionGranted) onGrantListener()
                        else onRequestPostNotification()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("前往授權", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun SettingsAndChatsView(
    currentMode: NotificationMode,
    isGlobalStackEnabled: Boolean,
    chats: List<ChatConversation>,
    detectedAccounts: List<com.notistack.line.core.model.LineAccount>,
    onModeChange: (NotificationMode) -> Unit,
    onGlobalStackToggle: (Boolean) -> Unit,
    onChatStackToggle: (ChatConversation, Boolean) -> Unit,
    onClearChat: (ChatConversation) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 全域通知模式卡片 (模式 A vs 模式 B)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("LINE 通知呈現模式", fontWeight = FontWeight.Bold, fontSize = 15.sp)

                    // 模式 A 選項
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentMode == NotificationMode.MODE_A_KEEP_NATIVE,
                                onClick = { onModeChange(NotificationMode.MODE_A_KEEP_NATIVE) }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentMode == NotificationMode.MODE_A_KEEP_NATIVE,
                            onClick = { onModeChange(NotificationMode.MODE_A_KEEP_NATIVE) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("模式 A：保留 LINE 原生通知 + 顯示自訂堆疊", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text("兩個通知並存，可雙重確認訊息狀態", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }

                    // 模式 B 選項
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentMode == NotificationMode.MODE_B_HIDE_NATIVE,
                                onClick = { onModeChange(NotificationMode.MODE_B_HIDE_NATIVE) }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentMode == NotificationMode.MODE_B_HIDE_NATIVE,
                            onClick = { onModeChange(NotificationMode.MODE_B_HIDE_NATIVE) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("模式 B：隱藏 LINE 原生通知，純自訂堆疊 (推薦)", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text("自動清除 LINE 原生通知，通知列乾淨不重複", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("全域堆疊開關", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text("關閉後將停止發送自訂堆疊通知", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = isGlobalStackEnabled,
                            onCheckedChange = onGlobalStackToggle
                        )
                    }
                }
            }
        }

        // 雙開帳號識別資訊 (若有)
        if (detectedAccounts.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已識別的 LINE 執行個體", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        detectedAccounts.forEach { acc ->
                            Text(
                                "• ${acc.displayName} (User ID: ${acc.userId})",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // 聊天室清單標題
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "個別聊天室堆疊設定 (${chats.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (chats.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        Text("尚未捕捉到任何聊天室", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("當 LINE 收到新訊息時，會自動建立聊天室並加入此處進行個別設定。", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        } else {
            items(chats, key = { it.chatKey }) { chat ->
                ChatCard(
                    chat = chat,
                    onToggleStack = { enabled -> onChatStackToggle(chat, enabled) },
                    onClear = { onClearChat(chat) }
                )
            }
        }
    }
}

@Composable
fun ChatCard(
    chat: ChatConversation,
    onToggleStack: (Boolean) -> Unit,
    onClear: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeStr = timeFormat.format(Date(chat.lastMessageTime))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = chat.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    if (chat.isGroup) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "群組",
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (chat.unreadCount > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.error,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "${chat.unreadCount}",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(text = timeStr, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }

            Text(
                text = chat.lastMessageContent,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("啟用堆疊通知", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = chat.isStackEnabled,
                        onCheckedChange = onToggleStack,
                        modifier = Modifier.scale(0.8f)
                    )
                }

                TextButton(onClick = onClear) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清除未讀", fontSize = 11.sp)
                }
            }
        }
    }
}

private fun Modifier.scale(scale: Float): Modifier = this.then(
    Modifier.padding(0.dp) // placeholder for scale
)

@Composable
fun LogsView(
    logs: List<CapturedNotification>,
    onSendTest: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSendTest,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("發送模擬訊息測試", fontSize = 13.sp)
            }

            OutlinedButton(onClick = onRefresh) {
                Text("重新整理", fontSize = 13.sp)
            }
        }

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
                Text("尚未有任何通知或事件日誌", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
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

                Text(text = timeStr, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }

            Text(text = log.title ?: "（無標題）", fontWeight = FontWeight.Bold, fontSize = 14.sp)

            if (!log.text.isNullOrBlank()) {
                Text(text = log.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            }

            if (!isPosted && log.removeReason != null) {
                val desc = NotificationParser.getRemovalReasonDescription(log.removeReason)
                Text(
                    text = "移除原因: $desc",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (log.removeReason == 8) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (log.hasRemoteInputReply) {
                    Text(text = "✓ 支援直接回覆 (RemoteInput)", fontSize = 11.sp, color = Color(0xFF1565C0), fontWeight = FontWeight.Medium)
                }
                Text(text = "UID: ${log.uid}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
    return enabledListeners.contains(context.packageName)
}

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
