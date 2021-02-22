package huawei.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.preference.PreferenceManager
import com.huawei.agconnect.appmessaging.AGConnectAppMessaging
import com.huawei.agconnect.appmessaging.AGConnectAppMessagingOnClickListener
import com.huawei.agconnect.appmessaging.model.AppMessage
import com.huawei.agconnect.config.AGConnectServicesConfig
import com.huawei.hms.aaid.HmsInstanceId
import com.huawei.hms.common.ApiException
import com.huawei.hms.push.HmsMessaging
import com.huawei.hms.push.RemoteMessage
import org.appcelerator.kroll.KrollDict
import org.appcelerator.kroll.KrollFunction
import org.appcelerator.kroll.KrollModule
import org.appcelerator.kroll.annotations.Kroll.*
import org.appcelerator.kroll.common.Log
import org.appcelerator.titanium.TiApplication
import org.json.JSONObject
import java.util.*

class ClickListener : AGConnectAppMessagingOnClickListener {
    override fun onMessageClick(appMessage: AppMessage) {
        // Obtain the content of the tapped message.
    }
}

@module(name = "TitaniumHuaweiMessagingModule", id = "huawei.messaging")
class TitaniumHuaweiMessagingModule : KrollModule() {

    private lateinit var instance: AGConnectAppMessaging
    private var notificationData = ""

    init {
        moduleInstance = this
    }

    @method
    fun configure() {
        instance = AGConnectAppMessaging.getInstance()
        instance.addOnClickListener(ClickListener())

        // Check for pending intents
        parseBootIntent()
    }

    @method
    fun trigger(event: String) {
        instance.trigger(event)
    }

    @method
    fun subscribe(topic: String) {
        HmsMessaging.getInstance(TiApplication.getAppCurrentActivity())
                .subscribe(topic)
                .addOnCompleteListener { task ->
                    val event = KrollDict()
                    event["success"] = task.isSuccessful
                    fireEvent("subscribe", event)
                }
    }

    @method
    fun unsubscribe(topic: String) {
        HmsMessaging.getInstance(TiApplication.getAppCurrentActivity())
                .unsubscribe(topic)
                .addOnCompleteListener { task ->
                    val event = KrollDict()
                    event["success"] = task.isSuccessful
                    fireEvent("subscribe", event)
                }
    }

    @setProperty
    fun enablePush(enabled: Boolean) {
        val messagingInstance = HmsMessaging.getInstance(TiApplication.getAppCurrentActivity())

        if (enabled) {
            messagingInstance.turnOnPush().addOnCompleteListener { _ ->
                // Handle task state?
            }
        } else {
            messagingInstance.turnOffPush().addOnCompleteListener { _ ->
                // Handle task state?
            }
        }
    }

    @method
    fun getToken(callback: KrollFunction) {
        object : Thread() {
            override fun run() {
                val event = KrollDict()

                try {
                    val appId = AGConnectServicesConfig.fromContext(TiApplication.getAppCurrentActivity()).getString("client/app_id")
                    val token = HmsInstanceId.getInstance(TiApplication.getAppCurrentActivity()).getToken(appId, "HCM")

                    event["success"] = true
                    event["token"] = token

                } catch (e: ApiException) {
                    Log.e("HCM", "Cannot get token: " + e.message)
                    event["success"] = false
                }

                callback.callAsync(getKrollObject(), event)
            }
        }.start()
    }

    @method
    fun deleteToken() {
        object : Thread() {
            override fun run() {
                try {
                    val appId = AGConnectServicesConfig.fromContext(TiApplication.getAppCurrentActivity()).getString("client/app_id")
                    HmsInstanceId.getInstance(TiApplication.getAppCurrentActivity()).deleteToken(appId, "HCM")
                } catch (e: ApiException) {
                    Log.e("HCM", "Cannot delete token: " + e.message)
                }
            }
        }.start()
    }

    @method
    fun createNotificationChannel(options: KrollDict) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        Log.d("HCM", "createNotificationChannel $options")

        val context: Context = TiApplication.getInstance().applicationContext
        val sound = options.optString("sound", "default")
        val importance = options.optString("importance", if (sound == "silent") "low" else "default")
        val channelId = options.optString("channelId", "default")
        val channelName = options.optString("channelName", channelId)
        var importanceVal = NotificationManager.IMPORTANCE_DEFAULT
        if (importance == "low") {
            importanceVal = NotificationManager.IMPORTANCE_LOW
        } else if (importance == "high") {
            importanceVal = NotificationManager.IMPORTANCE_HIGH
        }
        var soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val channel = NotificationChannel(channelId, channelName, importanceVal)
        channel.enableVibration(options.optBoolean("vibrate", false))
        channel.enableLights(options.optBoolean("lights", false))
        channel.setShowBadge(options.optBoolean("showBadge", false))
        if (soundUri != null) {
            val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
            channel.setSound(soundUri, audioAttributes)
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    @method
    fun deleteNotificationChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        Log.d("HCM", "deleteNotificationChannel $channelId")
        val context: Context = TiApplication.getInstance().applicationContext
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.deleteNotificationChannel(channelId)
    }

    // clang-format off
    @setProperty
    @method
    fun setForceShowInForeground(showInForeground: Boolean?) // clang-format on
    {
        val context: Context = TiApplication.getInstance().applicationContext
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
        editor.putBoolean("huawei.messaging.prefs.show_in_foreground", showInForeground!!)
        editor.commit()
    }

    @getProperty
    fun forceShowInForeground(): Boolean {
        val context: Context = TiApplication.getInstance().applicationContext
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean("huawei.messaging.prefs.show_in_foreground", false)
    }

    fun setNotificationData(data: String) {
        notificationData = data
    }

    fun parseBootIntent() {
        try {
            val intent = TiApplication.getAppRootOrCurrentActivity().intent
            val notification = intent.getStringExtra("hcm_data")
            if (notification != null) {
                val msg = HashMap<String, Any>()
                msg["data"] = KrollDict(JSONObject(notification))
                onMessageReceived(msg)
                intent.removeExtra("hcm_data")
            } else {
                Log.d("HCM", "Empty notification in Intent")
            }
        } catch (ex: java.lang.Exception) {
            Log.e("HCM", "parseBootIntent$ex")
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(TiApplication.getInstance().applicationContext)
        preferences.edit().remove("huawei.messaging.prefs.message").commit()
    }

    fun onMessageReceived(message: HashMap<*, *>?) {
        try {
            if (hasListeners("didReceiveMessage")) {
                val data = KrollDict()
                data["message"] = KrollDict(message as MutableMap<out String, out Any>)
                fireEvent("didReceiveMessage", data)
            }
        } catch (e: java.lang.Exception) {
            Log.e("HCM", "Message exception: " + e.message)
        }
    }

    fun onTokenRefresh(token: String) {
        try {
            if (hasListeners("didRefreshToken")) {
                val data = KrollDict()
                data["token"] = token
                fireEvent("didRefreshToken", data)
            }
        } catch (e: Exception) {
            Log.e("HMS", "Can't refresh token: " + e.message)
        }
    }

    companion object {
        lateinit var moduleInstance: TitaniumHuaweiMessagingModule

        fun getInstance(): TitaniumHuaweiMessagingModule? {
            return moduleInstance
        }
    }
}