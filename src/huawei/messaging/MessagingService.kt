package huawei.messaging

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.os.Build
import android.preference.PreferenceManager
import androidx.core.app.NotificationCompat
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage
import org.appcelerator.kroll.KrollDict
import org.appcelerator.kroll.common.Log
import org.appcelerator.titanium.TiApplication
import org.appcelerator.titanium.util.TiConvert
import org.json.JSONObject
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class MessagingService : HmsMessageService() {

    override fun onMessageReceived(message: RemoteMessage?) {
        super.onMessageReceived(message)
        var isVisible: Boolean = true
        val appInForeground = TiApplication.isCurrentActivityInForeground()
        val msg = HashMap<String, Any>()

        Log.i("HCM", "onMessageReceived is called")

        if (message == null) {
            Log.e("HCM", "Received message entity is null!")
            return
        }

        logMessage(message)

        if (message.getNotification() != null) {
            android.util.Log.d("HCM", "Message Notification Body: " + message.getNotification().getBody())
            msg.put("title", message.getNotification().getTitle())
            msg.put("body", message.getNotification().getBody())
            isVisible = true
        } else {
            android.util.Log.d("HCM", "Data message: " + message.getData())
        }

        msg.put("from", message.getFrom())
        msg.put("to", message.getTo())
        msg.put("ttl", message.getTtl())
        msg.put("messageId", message.getMessageId())
        msg.put("messageType", message.getMessageType())
        msg.put("data", KrollDict(message.getData() as MutableMap<out String, out Any>))
        msg.put("sendTime", message.getSentTime())

        // data message
        if (!message.data.isEmpty()) {
            isVisible = showNotification(message)
        }

        if (isVisible || appInForeground) {
            TitaniumHuaweiMessagingModule.getInstance().let { module ->
                module?.onMessageReceived(msg)
            }
        }
    }

    private fun logMessage(message: RemoteMessage) {
        Log.i("HCM", "getCollapseKey: " + message.collapseKey
                .toString() + "\n getData: " + message.data
                .toString() + "\n getFrom: " + message.from
                .toString() + "\n getTo: " + message.to
                .toString() + "\n getMessageId: " + message.messageId
                .toString() + "\n getSendTime: " + message.sentTime
                .toString() + "\n getMessageType: " + message.messageType
                .toString() + "\n getTtl: " + message.ttl)

        val notification: RemoteMessage.Notification = message.notification

        Log.i("HCM", """
             getImageUrl: ${notification.imageUrl}
             getTitle: ${notification.title}
             getTitleLocalizationKey: ${notification.titleLocalizationKey}
             getTitleLocalizationArgs: ${Arrays.toString(notification.titleLocalizationArgs)}
             getBody: ${notification.body}
             getBodyLocalizationKey: ${notification.bodyLocalizationKey}
             getBodyLocalizationArgs: ${Arrays.toString(notification.bodyLocalizationArgs)}
             getIcon: ${notification.icon}
             getSound: ${notification.sound}
             getTag: ${notification.tag}
             getColor: ${notification.color}
             getClickAction: ${notification.clickAction}
             getChannelId: ${notification.channelId}
             getLink: ${notification.link}
             getNotifyId: ${notification.notifyId}""")
    }

    override fun onNewToken(token: String) {
        Log.i("HCM", "received refresh token:$token")

        TitaniumHuaweiMessagingModule.getInstance().let { module ->
            module?.onTokenRefresh(token)
        }
    }

    private fun showNotification(remoteMessage: RemoteMessage): Boolean {
        val module = TitaniumHuaweiMessagingModule.getInstance()
        val params: Map<String?, String?>? = remoteMessage.dataOfMap
        val jsonData = JSONObject(params)
        val appInForeground = TiApplication.isCurrentActivityInForeground()
        var showNotification = true
        val context = applicationContext
        var defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        var builderDefaults = 0 or Notification.DEFAULT_SOUND
        if (appInForeground) {
            showNotification = false
        }
        if (params!!["force_show_in_foreground"] != null && params["force_show_in_foreground"] !== "") {
            showNotification = showNotification || TiConvert.toBoolean(params["force_show_in_foreground"], false)
        }
        if (module != null && module.forceShowInForeground()) {
            showNotification = module.forceShowInForeground()
        }
        if (TiConvert.toBoolean(params["vibrate"], false)) {
            builderDefaults = builderDefaults or Notification.DEFAULT_VIBRATE
        }
        if (params["title"] == null && params["alert"] == null && params["message"] == null && params["big_text"] == null && params["big_text_summary"] == null && params["ticker"] == null && params["image"] == null) {
            // no actual content - don't show it
            showNotification = false
        }
        val priorityString = params["priority"]
        var priority = NotificationManager.IMPORTANCE_MAX
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && priorityString != null && !priorityString.isEmpty()) {
            priority = if (priorityString.toLowerCase() == "low") {
                NotificationManager.IMPORTANCE_LOW
            } else if (priorityString.toLowerCase() == "min") {
                NotificationManager.IMPORTANCE_MIN
            } else if (priorityString.toLowerCase() == "max") {
                NotificationManager.IMPORTANCE_MAX
            } else if (priorityString.toLowerCase() == "default") {
                NotificationManager.IMPORTANCE_DEFAULT
            } else if (priorityString.toLowerCase() == "high") {
                NotificationManager.IMPORTANCE_HIGH
            } else {
                TiConvert.toInt(priorityString, 1)
            }
        }

        val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = preferences.edit()
        editor.putString("huawei.messaging.prefs.message", jsonData.toString())
        editor.commit()
        if (!showNotification) {
            // hidden notification - still send broadcast with data for next app start
            val i = Intent()
            i.addCategory(Intent.CATEGORY_LAUNCHER)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            i.putExtra("hcm_data", jsonData.toString())
            sendBroadcast(i)

            return false
        }
        val notificationIntent = Intent(this, PushHandlerActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        notificationIntent.putExtra("hcm_data", jsonData.toString())
        val contentIntent = PendingIntent.getActivity(this, Random().nextInt(), notificationIntent, PendingIntent.FLAG_ONE_SHOT)

        // Start building notification
        val builder: NotificationCompat.Builder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var channelId: String? = "default"
            if (params["channelId"] != null && params["channelId"] !== "") {
                channelId = params["channelId"]
            }
            builder = NotificationCompat.Builder(context, channelId!!)
        } else {
            builder = NotificationCompat.Builder(context)
        }
        builder.setContentIntent(contentIntent)
        builder.setAutoCancel(true)
        builder.priority = priority
        builder.setContentTitle(params["title"])
        if (params["alert"] != null) {
            // OneSignal uses alert for the message
            builder.setContentText(params["alert"])
        } else {
            builder.setContentText(params["message"])
        }
        builder.setTicker(params["ticker"])
        builder.setDefaults(builderDefaults)
        builder.setSound(defaultSoundUri)

        // BigText
        if (params["big_text"] != null && params["big_text"] !== "") {
            val bigTextStyle = NotificationCompat.BigTextStyle()
            bigTextStyle.bigText(params["big_text"])
            if (params["big_text_summary"] != null && params["big_text_summary"] !== "") {
                bigTextStyle.setSummaryText(params["big_text_summary"])
            }
            builder.setStyle(bigTextStyle)
        }

        if (params["color"] != null && params["color"] !== "") {
            try {
                val color = TiConvert.toColor(params["color"])
                builder.color = color
                builder.setColorized(true)
            } catch (ex: Exception) {
                android.util.Log.e("HCM", "Color exception: " + ex.message)
            }
        }

        var id = 0
        if (params != null && params["id"] !== "") {
            // ensure that the id sent from the server is negative to prevent
            // collision with the atomic integer
            id = TiConvert.toInt(params["id"], 0)
        }
        if (id == 0) {
            id = AtomicInteger(0).getAndIncrement()
        }

        // Send
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(id, builder.build())

        return true
    }
}