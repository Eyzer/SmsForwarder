package cn.ppps.forwarder.service

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.database.entity.Rule
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PACKAGE_NAME
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.Worker
import cn.ppps.forwarder.workers.SendWorker
import com.google.gson.Gson
import com.xuexiang.xrouter.utils.TextUtils
import com.xuexiang.xutil.display.ScreenUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date


@Suppress("PrivatePropertyName", "DEPRECATION")
class NotificationService : NotificationListenerService() {

    private val TAG: String = NotificationService::class.java.simpleName

    // 直接派发用的协程作用域（常驻服务进程，不随单条消息结束）
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        Log.d(TAG, "onListenerConnected")
    }

    override fun onListenerDisconnected() {
        //纯客户端模式
        if (SettingUtils.enablePureClientMode) return

        //总开关
        if (!SettingUtils.enableAppNotify) return

        Log.d(TAG, "通知侦听器断开连接 - 请求重新绑定")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationListenerService::class.java))
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            //纯客户端模式
            if (SettingUtils.enablePureClientMode) return

            //异常通知跳过
            val notification = sbn?.notification ?: return
            val extras = notification.extras ?: return

            //自动消除额外APP通知
            SettingUtils.cancelExtraAppNotify
                .takeIf { it.isNotEmpty() }
                ?.split("\n")
                ?.forEach { app ->
                    if (sbn.packageName == app.trim()) {
                        Log.d(TAG, "自动消除额外APP通知：$app")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            cancelNotification(sbn.key)
                        } else {
                            cancelNotification(sbn.packageName, sbn.tag, sbn.id)
                        }
                        return@forEach
                    }
                }


            //总开关
            if (!SettingUtils.enableAppNotify) return

            //仅锁屏状态转发APP通知
            if (SettingUtils.enableNotUserPresent && !ScreenUtils.isScreenLock()) return

            val from = sbn.packageName
            //自身通知跳过
            if (PACKAGE_NAME == sbn.packageName) return
            // 标题
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            // 通知内容
            var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
                if (bigText.isNotEmpty()) {
                    text = bigText
                }
            }
            if (text.isEmpty() && notification.tickerText != null) {
                text = notification.tickerText.toString()
            }

            //不处理空消息（标题跟内容都为空）
            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return

            //关键词黑名单：一行一个，支持正则，命中标题或内容则不转发
            if (isInBlacklist(title, text)) {
                Log.d(TAG, "命中APP通知关键词黑名单，跳过转发。title=$title, text=$text")
                return
            }

            val msgInfo = MsgInfo("app", from, text, Date(), title, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "消息的UID====>" + sbn.uid)
                msgInfo.uid = sbn.uid
            }
            //TODO：自动消除通知（临时方案，重复查询换取准确性）
            if (SettingUtils.enableCancelAppNotify) {
                val ruleList: List<Rule> = Core.rule.getRuleList(msgInfo.type, 1, "SIM0")
                for (rule in ruleList) {
                    if (rule.checkMsg(msgInfo)) {
                        Log.d(TAG, "自动消除通知")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            cancelNotification(sbn.key)
                        } else {
                            cancelNotification(sbn.packageName, sbn.tag, sbn.id)
                        }
                        break
                    }
                }
            }

            //【修复】直接派发，不再依赖 WorkManager 排队
            //WorkManager 调度静默失效（典型表现：任务已入队但永不执行、且不打任何异常日志）
            //会导致通知长期不转发，这里改为直接调用派发逻辑，失败时再退回 WorkManager 兜底。
            val msgInfoJson = Gson().toJson(msgInfo)
            dispatchScope.launch {
                try {
                    Log.d(TAG, "直接派发通知：$from | $title")
                    val ok = SendWorker.dispatch(applicationContext, msgInfoJson)
                    Log.d(TAG, "直接派发结果：$ok | $from")
                } catch (e: Exception) {
                    Log.e(TAG, "直接派发异常，转 WorkManager 兜底：" + e.message.toString())
                    enqueueByWorkManager(msgInfoJson)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Parsing Notification failed: " + e.message.toString())
        }

    }

    private fun enqueueByWorkManager(msgInfoJson: String) {
        try {
            val request = OneTimeWorkRequestBuilder<SendWorker>().setInputData(
                workDataOf(
                    Worker.SEND_MSG_INFO to msgInfoJson,
                )
            ).build()
            WorkManager.getInstance(applicationContext).enqueue(request)
            Log.d(TAG, "WorkManager 兜底入队：${request.id}")
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager 兜底入队异常：" + e.message.toString())
        }
    }

    //关键词黑名单匹配：一行一个关键词，支持正则表达式，命中通知标题或内容任意一项即返回 true
    private fun isInBlacklist(title: String, text: String): Boolean {
        val blacklist = SettingUtils.appNotifyBlacklist
        if (TextUtils.isEmpty(blacklist)) return false

        for (line in blacklist.split("\n")) {
            val keyword = line.trim()
            if (keyword.isEmpty()) continue
            try {
                val regex = Regex(keyword)
                if (regex.containsMatchIn(title) || regex.containsMatchIn(text)) {
                    return true
                }
            } catch (e: Exception) {
                //正则表达式非法时，降级为普通包含匹配
                Log.w(TAG, "黑名单关键词正则非法，降级为包含匹配：$keyword, ${e.message}")
                if (title.contains(keyword) || text.contains(keyword)) {
                    return true
                }
            }
        }
        return false
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Log.d(TAG, "Removed Package Name : ${sbn?.packageName}")
    }

}
