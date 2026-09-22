package com.example.dex_touchpad

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.dex_touchpad.databinding.ActivityMainBinding
import com.example.dex_touchpad.services.ShizukuUserService
import com.example.dex_touchpad.services.TouchpadService
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs

private const val TAG = "MainActivity"
private const val SHIZUKU_REQUEST_CODE = 100
private const val PREFS_NAME = "dex_touchpad_prefs"
private const val PREF_SENSITIVITY = "sensitivity"
private const val DEFAULT_SENSITIVITY = 1.0f

private const val BUTTON_LEFT = 1
private const val BUTTON_RIGHT = 2
private const val REBIND_DELAY_MS = 1000L

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())

    private var touchpadService: TouchpadService? = null
    private var mouseControl: IMouseControl? = null
    private var isUserServiceBound = false
    private var isFullscreen = false

    /**
     * The Shizuku user service runs the UHid code as shell in its own process.
     * Shizuku hands back that service's binder directly, and because the service
     * class extends `IMouseControl.Stub` the binder *is* our mouse interface.
     *
     * Daemon mode is used deliberately: Shizuku then keeps a single instance and
     * hands the same binder back on later binds, instead of creating a fresh
     * process (and a second UHid mouse) whenever the app is restarted. `onDestroy`
     * still removes it on a clean exit.
     */
    private val userServiceArgs = UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name)
    ).daemon(true).processNameSuffix("user_service").debuggable(false).version(7)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Shizuku user service connected")
            if (service == null || !service.pingBinder()) {
                Log.w(TAG, "Shizuku user service binder is not alive")
                isUserServiceBound = false
                updateStatus("Shizuku service binder died — tap Reconnect")
                return
            }
            val control = IMouseControl.Stub.asInterface(service)
            mouseControl = control
            touchpadService?.setMouseControl(control)
            binding.touchpadView.mouseControlService = control
            updateStatus("Connected — cover display is a touchpad")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Shizuku user service disconnected")
            isUserServiceBound = false
            mouseControl = null
            touchpadService?.setMouseControl(null)
            binding.touchpadView.mouseControlService = null
            updateStatus("Shizuku service stopped — reconnecting…")
            scheduleRebind()
        }
    }

    private val touchpadServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TouchpadService.LocalBinder
            touchpadService = binder.getService()
            touchpadService?.setMouseControl(mouseControl)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            touchpadService = null
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        checkShizukuAndConnect()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder died")
        // Shizuku (and therefore our user service) is gone. Clear the bound flag so
        // the OnBinderReceivedListener re-binds once Shizuku is started again.
        isUserServiceBound = false
        mouseControl = null
        touchpadService?.setMouseControl(null)
        binding.touchpadView.mouseControlService = null
        updateStatus("Shizuku stopped — start Shizuku to reconnect")
    }

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindUserService()
                } else {
                    updateStatus("Shizuku permission denied — grant it in the Shizuku app")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setupSensitivityControl()
        setupButtons()
        registerShizukuListeners()

        // Leave fullscreen with a four-finger tap or the system Back gesture.
        binding.touchpadView.onFourFingerTap = { if (isFullscreen) toggleFullscreen() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    toggleFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        startForegroundService(Intent(this, TouchpadService::class.java))
        bindService(
            Intent(this, TouchpadService::class.java),
            touchpadServiceConnection,
            Context.BIND_AUTO_CREATE
        )

        updateStatus("Waiting for Shizuku…")
    }

    override fun onResume() {
        super.onResume()
        if (Shizuku.pingBinder()) {
            checkShizukuAndConnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unregisterShizukuListeners()
        if (isUserServiceBound) {
            try {
                // Only actually remove the user service when the user leaves the
                // app. On a configuration change / display switch the Activity is
                // recreated, and killing the service there would tear down and
                // rebuild the virtual mouse over and over.
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, isFinishing)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding user service", e)
            }
            isUserServiceBound = false
        }
        try {
            unbindService(touchpadServiceConnection)
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding touchpad service", e)
        }
    }

    private fun registerShizukuListeners() {
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
    }

    private fun unregisterShizukuListeners() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
    }

    private fun checkShizukuAndConnect() {
        if (!Shizuku.pingBinder()) {
            updateStatus("Shizuku is not running — start Shizuku first")
            return
        }
        if (Shizuku.isPreV11()) {
            updateStatus("Shizuku is too old — please update it")
            return
        }
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindUserService()
            Shizuku.shouldShowRequestPermissionRationale() ->
                updateStatus("Open the Shizuku app and allow Dex Touchpad")
            else -> Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
    }

    private fun bindUserService() {
        if (isUserServiceBound) return
        try {
            updateStatus("Connecting via Shizuku…")
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            isUserServiceBound = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Shizuku user service", e)
            updateStatus("Failed to connect: ${e.message}")
        }
    }

    /** Re-binds after an unexpected disconnect (Shizuku restart, service reaped, …). */
    private fun scheduleRebind() {
        handler.postDelayed({
            if (!isFinishing && !isDestroyed && mouseControl == null && Shizuku.pingBinder()) {
                checkShizukuAndConnect()
            }
        }, REBIND_DELAY_MS)
    }

    private fun setupSensitivityControl() {
        val savedSensitivity = prefs.getFloat(PREF_SENSITIVITY, DEFAULT_SENSITIVITY)
        val seekbarProgress = ((savedSensitivity - 0.1f) / 4.9f * 100).toInt()
        binding.sensitivitySeekbar.progress = seekbarProgress
        binding.touchpadView.setSensitivity(savedSensitivity)

        binding.sensitivitySeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sensitivity = 0.1f + (progress / 100f) * 4.9f
                binding.touchpadView.setSensitivity(sensitivity)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupButtons() {
        binding.btnLeftClick.setOnClickListener { click(BUTTON_LEFT) }
        binding.btnRightClick.setOnClickListener { click(BUTTON_RIGHT) }
        binding.btnFullscreen.setOnClickListener { toggleFullscreen() }
        binding.btnReconnect.setOnClickListener { reconnect() }
    }

    /**
     * Hides every control and the system bars so only the touchpad is visible.
     * Leave it with the system Back gesture or a four-finger tap.
     */
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen

        val chrome = if (isFullscreen) View.GONE else View.VISIBLE
        binding.topChrome.visibility = chrome
        binding.bottomChrome.visibility = chrome
        val padding = if (isFullscreen) 0 else (16 * resources.displayMetrics.density).toInt()
        binding.rootLayout.setPadding(padding, padding, padding, padding)

        WindowCompat.setDecorFitsSystemWindows(window, !isFullscreen)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isFullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        binding.btnFullscreen.text = if (isFullscreen) "Exit fullscreen" else "Fullscreen"
    }

    private fun click(button: Int) {
        try {
            mouseControl?.sendClick(button)
        } catch (e: Exception) {
            Log.w(TAG, "sendClick($button) failed", e)
        }
    }

    private fun reconnect() {
        mouseControl = null
        binding.touchpadView.mouseControlService = null
        if (isUserServiceBound) {
            try {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding", e)
            }
            isUserServiceBound = false
        }
        updateStatus("Reconnecting…")
        checkShizukuAndConnect()
    }

    private fun updateStatus(status: String) {
        runOnUiThread { binding.statusText.text = status }
    }
}
