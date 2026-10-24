package com.purposegate

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding


import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 悬浮条的宿主 Activity：用安卓原生画中画承载“目的 + 倒计时”。
 *
 * 画中画窗口由系统管理（系统负责拖动、关闭、展开），所以悬浮条不再需要自己
 * 用无障碍悬浮窗画和拖动。无障碍服务负责决定什么时候显示/收起它。
 */
class GatePipActivity : ComponentActivity() {

    companion object {
        const val ACTION_FINISH_BAR = "com.purposegate.action.FINISH_BAR"
        const val ACTION_TIMER_ZERO = "com.purposegate.action.TIMER_ZERO"
        const val ACTION_BAR_CLOSED = "com.purposegate.action.BAR_CLOSED"
        const val ACTION_PIP_UNAVAILABLE = "com.purposegate.action.PIP_UNAVAILABLE"

        private const val EXTRA_PURPOSE = "extra_purpose"
        private const val EXTRA_REMAINING = "extra_remaining_seconds"

        /** 计时悬浮条不是定时器就是目的条，用 -1 表示没有倒计时。 */
        const val NO_TIMER = -1L

        fun intent(context: Context, purpose: String?, remainingSeconds: Long): Intent =
            Intent(context, GatePipActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PURPOSE, purpose)
                .putExtra(EXTRA_REMAINING, remainingSeconds)
    }

    private var remainingSeconds by mutableLongStateOf(NO_TIMER)
    private var purpose by mutableStateOf<String?>(null)
    private var expanded by mutableStateOf(false)
    private var enterPipRequested = false
    private var finishRequested = false
    private var receiverRegistered = false

    private val barReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_FINISH_BAR -> {
                    // 服务端要求收起悬浮条：不要再回报 BAR_CLOSED，否则会把会话当成用户主动结束。
                    finishRequested = true
                    finish()
                }
                ACTION_TIMER_ZERO -> remainingSeconds = 0L
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        purpose = intent.getStringExtra(EXTRA_PURPOSE)
        remainingSeconds = intent.getLongExtra(EXTRA_REMAINING, NO_TIMER)
        registerBarReceiver()
        setPictureInPictureParams(pipParams())
        setContent {
            PurposeGateTheme(denseTypography = false) {
                BarCountdown()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    if (expanded) {
                        ExpandedBar()
                    } else {
                        CompactBar()
                    }
                }
            }
        }
    }

    /** 悬浮条自己按秒刷新显示；结束与否由无障碍服务判断，避免两处各算一遍。 */
    @Composable
    private fun BarCountdown() {
        LaunchedEffect(Unit) {
            while (remainingSeconds > 0L) {
                delay(1_000L)
                if (remainingSeconds > 0L) remainingSeconds -= 1L
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        purpose = intent.getStringExtra(EXTRA_PURPOSE)
        remainingSeconds = intent.getLongExtra(EXTRA_REMAINING, NO_TIMER)
        expanded = false
    }

    override fun onResume() {
        super.onResume()
        if (!enterPipRequested) {
            enterPipRequested = true
            val entered = runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
            if (!entered) {
                // 设备/系统不允许画中画：通知服务退回无障碍悬浮条。
                sendBroadcast(Intent(ACTION_PIP_UNAVAILABLE).setPackage(packageName))
                finishRequested = true
                finish()
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // 用户点了展开：显示完整界面，可以再回到悬浮窗或直接结束。
        expanded = !isInPictureInPictureMode
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(barReceiver) }
            receiverRegistered = false
        }
        // 用户用系统的关闭按钮或返回结束的，才回报服务。
        if (!finishRequested) {
            sendBroadcast(Intent(ACTION_BAR_CLOSED).setPackage(packageName))
        }
        super.onDestroy()
    }

    private fun registerBarReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_FINISH_BAR)
            addAction(ACTION_TIMER_ZERO)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(this, barReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onSuccess { receiverRegistered = true }
    }

    private fun pipParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            // 画中画窗口比例必须在 1:2.39 到 2.39:1 之间，2:1 适合“目的 + 倒计时”两行。
            .setAspectRatio(Rational(2, 1))
            .build()

    @Composable
    private fun CompactBar() {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (purpose != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Symbol(
                        Symbols.Flag,
                        contentDescription = null,
                        size = 18.sp,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "目的：$purpose",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                }
            }
            if (remainingSeconds >= 0L) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Symbol(
                        Symbols.Timer,
                        contentDescription = null,
                        size = 18.sp,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "%02d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = CountdownFontFamily
                        )
                    )
                }
            }
            if (purpose == null && remainingSeconds < 0L) {
                Text("PurposeGate", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun ExpandedBar() {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SymbolBadge(Symbols.Timer, size = 64.dp, iconSize = 32.sp)
            Text("这次的专注", style = MaterialTheme.typography.headlineMedium)
            if (purpose != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Text("目的：$purpose", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
            if (remainingSeconds >= 0L) {
                Text(
                    "%02d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = CountdownFontFamily
                    ),
                    textAlign = TextAlign.Center
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExpressiveButton(
                    onClick = {
                        expanded = false
                        runCatching { enterPictureInPictureMode(pipParams()) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("回到悬浮窗") }
                ExpressiveOutlinedButton(
                    onClick = {
                        // 主动结束沿用系统关闭的通知路径，让服务同时清理计时与会话。
                        finish()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("结束计时并关闭悬浮窗") }
            }
        }
    }
}
