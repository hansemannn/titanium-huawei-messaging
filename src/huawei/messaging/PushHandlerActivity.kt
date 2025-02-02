package huawei.messaging

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class PushHandlerActivity : Activity() {
    public override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            finish()

            val context = applicationContext
            val notification = intent.getStringExtra("hcm_data")

            TitaniumHuaweiMessagingModule.getInstance().let { module ->
                module?.setNotificationData(notification)
            }

            val launcherIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            launcherIntent!!.addCategory(Intent.CATEGORY_LAUNCHER)
            launcherIntent!!.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            launcherIntent!!.putExtra("hcm_data", notification)

            startActivity(launcherIntent)
        } catch (e: Exception) {
            // no-op
        } finally {
            finish()
        }
    }
}