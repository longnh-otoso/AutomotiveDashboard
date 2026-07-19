package com.example.automotivecardashboard

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import navis.can.ICan
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val TAG = "AAOS_Dashboard"

    // --- ĐỊNH NGHĨA CÁC CAN ID THỰC TẾ ---
    private val CAN_ID_TURN_SIGNAL   = 0x100 // Tín hiệu đèn xi nhan (100h)
    private val CAN_ID_VOLUME        = 0x2B2 // Tín hiệu chỉnh volume
    private val CAN_ID_VEHICLE_SPEED = 0x101 // Tín hiệu tốc độ xe (101h)
    private val CAN_ID_BATTERY_LEVEL = 0x4D4 // Tín hiệu dung lượng pin (%)
    private val CAN_ID_LIGHT_STATUS  = 0x200 // Trạng thái đèn xe (200h)

    private var canService: ICan? = null
    private var isReading = false
    private var isLightOn = false
    private var isBlinking = false
    private var blinkTxThread: Thread? = null
    @Volatile private var stopBlinkTx = false

    // UI Components
    private lateinit var btnConnect: Button
    private lateinit var txtSpeed: TextView
    private lateinit var progressBattery: ProgressBar
    private lateinit var txtBatteryPercent: TextView
    private lateinit var glowLeftTurn: ImageView
    private lateinit var glowRightTurn: ImageView
    private lateinit var txtTime: TextView
    private lateinit var txtLastCanFrame: TextView
    private lateinit var btnToggleLight: Button
    private lateinit var btnToggleBlink: Button

    // Âm lượng hệ thống
    private lateinit var audioManager: AudioManager

    // Quản lý hiệu ứng nhấp nháy đèn (Blink Animators)
    private var leftBlinkAnimator: ObjectAnimator? = null
    private var rightBlinkAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Set apply window insets to keep status bar / navigation bar paddings
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Ánh xạ UI
        btnConnect = findViewById(R.id.btnConnect)
        txtSpeed = findViewById(R.id.txtSpeed)
        progressBattery = findViewById(R.id.progressBattery)
        txtBatteryPercent = findViewById(R.id.txtBatteryPercent)
        glowLeftTurn = findViewById(R.id.glowLeftTurn)
        glowRightTurn = findViewById(R.id.glowRightTurn)
        txtTime = findViewById(R.id.txtTime)
        txtLastCanFrame = findViewById(R.id.txtLastCanFrame)
        btnToggleLight = findViewById(R.id.btnToggleLight)
        btnToggleBlink = findViewById(R.id.btnToggleBlink)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        btnConnect.setOnClickListener {
            connectToService()
        }

        btnToggleLight.setOnClickListener {
            toggleCarLight()
        }

        btnToggleBlink.setOnClickListener {
            toggleBlinkMode()
        }

        // Tự động kết nối tới CAN Service khi ứng dụng khởi động
        connectToService()
    }

    // 1. Kết nối Binder tới C++ Daemon (có tự động phục hồi khi lỗi)
    private fun connectToService() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "navis.can.CanService") as? IBinder
            if (binder != null) {
                // Đăng ký sự kiện lắng nghe khi Daemon bị crash/die để kết nối lại
                binder.linkToDeath(object : IBinder.DeathRecipient {
                    override fun binderDied() {
                        Log.w(TAG, "C++ Daemon navis_can đã bị crash hoặc dừng!")
                        runOnUiThread {
                            btnConnect.text = "KẾT NỐI CAN"
                            btnConnect.isEnabled = true
                            txtLastCanFrame.text = "CAN: Mất kết nối daemon!"
                        }
                        canService = null
                        isReading = false
                        // Tự động kết nối lại sau 3 giây
                        thread {
                            Thread.sleep(3000)
                            runOnUiThread { connectToService() }
                        }
                    }
                }, 0)

                canService = ICan.Stub.asInterface(binder)
                btnConnect.text = "Đã Kết Nối"
                btnConnect.isEnabled = false
                
                // Mở cổng can0 và thiết lập bitrate 500k
                openCanInterface()
            } else {
                txtLastCanFrame.text = "CAN: Không tìm thấy Daemon"
                btnConnect.text = "KẾT NỐI CAN"
                btnConnect.isEnabled = true
                // Thử lại sau 3 giây
                thread {
                    Thread.sleep(3000)
                    runOnUiThread { connectToService() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi kết nối", e)
            txtLastCanFrame.text = "CAN: Lỗi kết nối (${e.message})"
            btnConnect.text = "KẾT NỐI CAN"
            btnConnect.isEnabled = true
            // Thử lại sau 3 giây
            thread {
                Thread.sleep(3000)
                runOnUiThread { connectToService() }
            }
        }
    }

    private fun openCanInterface() {
        thread {
            val success = canService?.canOpen("can0", 500000) ?: false
            if (success) {
                isReading = true
                startReadingCanData()
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Không mở được cổng can0", Toast.LENGTH_SHORT).show()
                }
                // Thử lại sau 3 giây nếu mở cổng thất bại
                thread {
                    Thread.sleep(3000)
                    runOnUiThread { openCanInterface() }
                }
            }
        }
    }

    // 2. Luồng đọc liên tục và Giải mã các gói tin CAN nhận được
    private fun startReadingCanData() {
        Log.i(TAG, "Bắt đầu luồng đọc dữ liệu CAN...")
        thread(start = true, isDaemon = true) {
            val data = ByteArray(8)
            while (isReading) {
                try {
                    // Hàm canRead này sẽ block chờ dữ liệu từ Kit SK144
                    val canId = canService?.canRead(data) ?: -1
                    if (canId != -1) {
                        runOnUiThread {
                            // Cập nhật text hiển thị gói tin cuối cùng nhận được trên UI
                            val hexId = Integer.toHexString(canId).uppercase()
                            val hexData = data.joinToString(" ") { String.format("%02X", it) }
                            txtLastCanFrame.text = "CAN: ID = 0x$hexId, Data = $hexData"
                            
                            // Giải mã các ID tin nhắn CAN nhận được
                            handleReceivedCanFrame(canId, data.clone())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi khi đọc: ${e.message}")
                    isReading = false
                    runOnUiThread {
                        txtLastCanFrame.text = "CAN: Lỗi đọc (${e.message})"
                        btnConnect.text = "KẾT NỐI CAN"
                        btnConnect.isEnabled = true
                    }
                    // Tự động chạy lại luồng kết nối và đọc sau 2 giây
                    thread {
                        Thread.sleep(2000)
                        runOnUiThread { connectToService() }
                    }
                    break
                }
            }
        }
    }

    // 3. Hàm giải mã gói tin dựa trên CAN ID
    private fun handleReceivedCanFrame(canId: Int, payload: ByteArray) {
        Log.d(TAG, "Xử lý CAN Frame: ID = 0x${Integer.toHexString(canId)}, Payload = ${payload.joinToString(", ") { String.format("0x%02X", it) }}")
        when (canId) {
            // B. ID 0x101: Sensor_Data (2 bytes) và Threshold value (2 bytes)
            CAN_ID_VEHICLE_SPEED -> {
                // 2 bytes Little Endian cho Sensor_Data (byte 0 & 1 - byte thứ 1 và thứ 2)
                val sensorData = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                // 2 bytes Little Endian cho Threshold value (byte 2 & 3 - byte thứ 3 và thứ 4)
                val thresholdVal = (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8)
                
                // Quy đổi dải ADC 12-bit (0 - 4096) sang tốc độ 0 - 300 mph
                val speedMph = (sensorData * 300) / 4096
                runOnUiThread {
                    txtSpeed.text = speedMph.toString()
                }
            }
            // C. Trạng thái Pin (%)
            CAN_ID_BATTERY_LEVEL -> {
                val battery = payload[0].toInt() and 0xFF // Đọc 1 byte phần trăm pin

                progressBattery.progress = battery
                txtBatteryPercent.text = "$battery%"
            }
            // D. Điều chỉnh Volume
            CAN_ID_VOLUME -> {
                val volumeAction = payload[0].toInt() // 0x01: Tăng, 0x02: Giảm

                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (volumeAction == 1 && currentVol < maxVol) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVol + 1, AudioManager.FLAG_SHOW_UI)
                } else if (volumeAction == 2 && currentVol > 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVol - 1, AudioManager.FLAG_SHOW_UI)
                }
            }

        }
    }

    // 4. Tạo hiệu ứng nhấp nháy đèn Xi-nhan Neon mượt mà (Breathing Effect)
    private fun handleBlinkAnimation(state: Int) {
        // Hủy các animation cũ
        leftBlinkAnimator?.cancel()
        rightBlinkAnimator?.cancel()
        glowLeftTurn.alpha = 0f
        glowRightTurn.alpha = 0f

        when (state) {
            1 -> { // Nhấp nháy đèn xi nhan trái
                leftBlinkAnimator = ObjectAnimator.ofFloat(glowLeftTurn, "alpha", 0.1f, 1.0f).apply {
                    duration = 450
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            2 -> { // Nhấp nháy đèn xi nhan phải
                rightBlinkAnimator = ObjectAnimator.ofFloat(glowRightTurn, "alpha", 0.1f, 1.0f).apply {
                    duration = 450
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            3 -> { // Nhấp nháy cả hai đèn (Hazard Light)
                leftBlinkAnimator = ObjectAnimator.ofFloat(glowLeftTurn, "alpha", 0.1f, 1.0f).apply {
                    duration = 450
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
                rightBlinkAnimator = ObjectAnimator.ofFloat(glowRightTurn, "alpha", 0.1f, 1.0f).apply {
                    duration = 450
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        }
    }

    private fun startBlinkTxLoop() {
        if (blinkTxThread != null && blinkTxThread!!.isAlive) return
        stopBlinkTx = false
        blinkTxThread = thread(start = true, isDaemon = true) {
            val payload = ByteArray(8)
            payload[0] = 1.toByte()
            payload[1] = 1.toByte()
            payload[2] = 0xFF.toByte()
            payload[3] = 0xFF.toByte()
            
            while (!stopBlinkTx && isBlinking) {
                try {
                    val success = canService?.canWrite(CAN_ID_LIGHT_STATUS, payload, false) ?: false
                    Log.d(TAG, "Gửi CAN Frame: ID = 0x200, Payload = ${payload.joinToString(", ") { String.format("0x%02X", it) }}")
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi gửi chu kỳ CAN 0x200: ${e.message}")
                }
                try {
                    Thread.sleep(3000)
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopBlinkTxLoop() {
        stopBlinkTx = true
        blinkTxThread?.interrupt()
        blinkTxThread = null
    }

    private fun updateLightButtonsUI() {
        // Nút 1: Bật/Tắt Đèn Xe
        if (isLightOn) {
            btnToggleLight.setTextColor(android.graphics.Color.WHITE)
            btnToggleLight.text = "TẮT ĐÈN XE"
        } else {
            btnToggleLight.setTextColor(android.graphics.Color.parseColor("#FF8F00"))
            btnToggleLight.text = "BẬT ĐÈN XE"
        }

        // Nút 2: Bật/Tắt Nhấp Nháy
        if (isBlinking) {
            btnToggleBlink.setTextColor(android.graphics.Color.GREEN)
            btnToggleBlink.text = "ĐÈN: NHẤP NHÁY"
            // Kích hoạt chớp nháy cả 2 xi-nhan cùng lúc
            handleBlinkAnimation(3)
            // Khởi chạy vòng lặp gửi chu kỳ 3s
            startBlinkTxLoop()
        } else {
            btnToggleBlink.setTextColor(android.graphics.Color.parseColor("#FF8F00"))
            btnToggleBlink.text = "BẬT NHẤP NHÁY"
            // Tắt chớp nháy xi-nhan
            handleBlinkAnimation(0)
            // Dừng vòng lặp gửi chu kỳ 3s
            stopBlinkTxLoop()
        }
    }

    private fun toggleCarLight() {
        val nextLightState = !isLightOn

        thread {
            val payload = ByteArray(8)
            payload[0] = (if (nextLightState) 1 else 0).toByte()
            payload[1] = (if (isBlinking) 1 else 0).toByte()
            // Byte 2 & 3 là Threshold write (gửi giá trị mặc định FF FF)
            payload[2] = 0xFF.toByte()
            payload[3] = 0xFF.toByte()

            val success = canService?.canWrite(CAN_ID_LIGHT_STATUS, payload, false) ?: false
            if (success) {
                isLightOn = nextLightState
                runOnUiThread {
                    updateLightButtonsUI()
                    Toast.makeText(this, if (isLightOn) "Đã bật đèn xe" else "Đã tắt đèn xe", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Không thể gửi lệnh CAN!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleBlinkMode() {
        val nextBlinkState = !isBlinking

        thread {
            val payload = ByteArray(8)
            payload[0] = (if (isLightOn) 1 else 0).toByte()
            payload[1] = (if (nextBlinkState) 1 else 0).toByte()
            // Byte 2 & 3 là Threshold write (gửi giá trị mặc định FF FF)
            payload[2] = 0xFF.toByte()
            payload[3] = 0xFF.toByte()

            val success = canService?.canWrite(CAN_ID_LIGHT_STATUS, payload, false) ?: false
            if (success) {
                isBlinking = nextBlinkState
                runOnUiThread {
                    updateLightButtonsUI()
                    Toast.makeText(this, if (isBlinking) "Đã bật nhấp nháy" else "Đã tắt nhấp nháy", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Không thể gửi lệnh CAN!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isReading = false
        stopBlinkTxLoop()
        leftBlinkAnimator?.cancel()
        rightBlinkAnimator?.cancel()
        try {
            canService?.canClose()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}