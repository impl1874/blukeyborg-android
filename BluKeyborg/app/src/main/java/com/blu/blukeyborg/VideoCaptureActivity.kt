package com.blu.blukeyborg

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCDeviceManager
import com.serenegiant.usb.UVCDeviceManager.UVCDevice
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoCaptureActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var usbManager: UsbManager
    private var camera: UVCCamera? = null
    private var textureView: TextureView? = null
    private var noDeviceView: LinearLayout? = null
    private var controlBar: LinearLayout? = null
    private var isInteractiveMode = false
    private var pendingDevice: UsbDevice? = null

    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // USB permission result
    private val usbPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val device = pendingDevice
        if (device != null) {
            if (result.resultCode == RESULT_OK && usbManager.hasPermission(device)) {
                openCamera(device)
            } else {
                showNoDevice("USB permission denied.\nGrant permission in Settings.")
            }
        }
        pendingDevice = null
    }

    // Camera permission result
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkUsbDevice()
        } else {
            showNoDevice("Camera permission denied.\nCannot display video.")
        }
    }

    // USB device receiver
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { checkAndOpenDevice(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    camera?.close()
                    camera = null
                    showNoDevice("USB device disconnected.\nConnect a UVC device and tap Refresh.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Full-screen root
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())

        // TextureView for video preview
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        textureView?.surfaceTextureListener = this
        root.addView(textureView)

        // No device / permission message
        noDeviceView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(48), dp(48), dp(48), dp(48))
            visibility = View.GONE

            addView(TextView(context).apply {
                text = "No USB Camera Detected"
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
            })

            addView(TextView(context).apply {
                text = "Connect a UVC capture device via USB OTG cable, then tap Refresh"
                textSize = 14f
                setTextColor(0xFFAAAAAA.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(16), 0, dp(32))
            })

            addView(Button(context).apply {
                text = "🔄 Refresh"
                setOnClickListener { checkUsbDevice() }
            })
        }
        root.addView(noDeviceView)

        // Bottom control bar
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xCC1A1A1A.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val closeBtn = Button(context).apply {
            text = "✕ Close"
            setOnClickListener { finish() }
        }
        addView(closeBtn)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        addView(spacer)

        val toggleBtn = Button(context).apply {
            text = "🖱️+📺 Interactive"
            setOnClickListener {
                isInteractiveMode = !isInteractiveMode
                updateControlBar()
                if (isInteractiveMode) {
                    startActivity(Intent(this@VideoCaptureActivity, RemoteControlActivity::class.java).apply {
                        putExtra("video_mode", true)
                    })
                }
            }
        }
        addView(toggleBtn)

        val controlLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        root.addView(controlBar, controlLp)

        setContentView(root)

        // Immersive mode
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        // Register USB broadcast
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        // Check camera permission (needed for USB camera on some devices)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            checkUsbDevice()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(usbReceiver) } catch (_: Throwable) {}
        camera?.close()
        camera = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // Surface is ready; if camera already connected, start preview
        camera?.let {
            try {
                it.setPreviewDisplay(Surface(surface))
                it.startPreview()
            } catch (e: Exception) {
                Toast.makeText(this, "Preview error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        camera?.stopPreview()
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun checkUsbDevice() {
        val deviceMap = usbManager.deviceList
        val uvcDevice = deviceMap.values.find { isUvcDevice(it) }

        if (uvcDevice != null) {
            checkAndOpenDevice(uvcDevice)
        } else {
            showNoDevice("No UVC device found.\nConnect a USB camera and tap Refresh.")
        }
    }

    private fun checkAndOpenDevice(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            openCamera(device)
        } else {
            pendingDevice = device
            val intent = usbManager.buildRequestPermissionIntent(device)
            usbPermissionLauncher.launch(intent)
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 0x0E) {  // Video class
                return true
            }
        }
        return false
    }

    private fun openCamera(device: UsbDevice) {
        cameraExecutor.execute {
            try {
                camera = UVCCamera()
                camera?.open(device)

                val surfaceTexture = textureView?.surfaceTexture ?: return@execute
                val surface = Surface(surfaceTexture)
                camera?.setPreviewDisplay(surface)
                camera?.startPreview()

                // Default resolution: try to set 640x480 if supported
                try {
                    camera?.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT)
                } catch (e: Exception) {
                    // Use whatever default the camera supports
                }

                handler.post {
                    noDeviceView?.visibility = View.GONE
                    Toast.makeText(this@VideoCaptureActivity, "Camera connected", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                handler.post {
                    showNoDevice("Failed to open camera:\n${e.message}")
                }
            }
        }
    }

    private fun showNoDevice(message: String) {
        noDeviceView?.visibility = View.VISIBLE
        (noDeviceView?.getChildAt(0) as? TextView)?.text = message.split("\n").firstOrNull() ?: message
        (noDeviceView?.getChildAt(1) as? TextView)?.text = message.split("\n").getOrNull(1) ?: ""
    }

    private fun updateControlBar() {
        (controlBar?.getChildAt(2) as? Button)?.apply {
            text = if (isInteractiveMode) "📺 Monitor" else "🖱️+📺 Interactive"
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}