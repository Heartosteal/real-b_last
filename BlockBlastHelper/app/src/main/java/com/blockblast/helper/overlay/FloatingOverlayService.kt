package com.blockblast.helper.overlay

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.blockblast.helper.R
import com.blockblast.helper.accessibility.AutoDragAccessibilityService
import com.blockblast.helper.controller.AutomationController
import com.blockblast.helper.model.BoardCalibration
import com.blockblast.helper.model.HelperMode
import com.blockblast.helper.solver.BlockBlastSolver
import com.blockblast.helper.vision.ScreenAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var hintOverlayView: HintOverlayView
    private lateinit var calibration: BoardCalibration

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var workerJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420
    private var foregroundStarted = false

    private val solver = BlockBlastSolver()
    private val analyzer = ScreenAnalyzer()
    private val controller = AutomationController()

    private lateinit var tvStatusBanner: TextView
    private lateinit var menuDropdown: LinearLayout

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        calibration = BoardCalibration.defaultForScreen(screenWidth, screenHeight)

        setupHintOverlay()
        setupFloatingButtonAndMenu()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            startForegroundNotification()
            foregroundStarted = true
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != -1 && resultData != null && mediaProjection == null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, resultData)
            setupVirtualDisplay()
        }
        return START_STICKY
    }

    private fun setupHintOverlay() {
        hintOverlayView = HintOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(hintOverlayView, params)
    }

    private fun setupFloatingButtonAndMenu() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_menu, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 220
        }

        val btnToggle = floatingView.findViewById<View>(R.id.btnFloatingToggle)
        menuDropdown = floatingView.findViewById(R.id.menuDropdown)
        tvStatusBanner = floatingView.findViewById(R.id.tvStatusBanner)

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        btnToggle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = initX + dx
                    params.y = initY + dy
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        menuDropdown.visibility =
                            if (menuDropdown.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }

        floatingView.findViewById<Button>(R.id.btnRecommendMode).setOnClickListener {
            val nextMode = if (controller.state.value.mode == HelperMode.RECOMMEND) {
                HelperMode.IDLE
            } else {
                HelperMode.RECOMMEND
            }
            controller.setMode(nextMode)
            restartWorkerLoop()
        }

        floatingView.findViewById<Button>(R.id.btnAutoPlayMode).setOnClickListener {
            if (AutoDragAccessibilityService.instance == null) {
                Toast.makeText(this, "접근성 서비스를 먼저 활성화해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val nextMode = if (controller.state.value.mode == HelperMode.AUTO_PLAY) {
                HelperMode.IDLE
            } else {
                HelperMode.AUTO_PLAY
            }
            controller.setMode(nextMode)
            restartWorkerLoop()
        }

        floatingView.findViewById<Button>(R.id.btnTargetScore).setOnClickListener {
            showNumericInputDialog("목표 점수 입력", "도달 시 자동 정지할 점수 (예: 15000)") { value ->
                controller.setTargetScore(value)
            }
        }

        floatingView.findViewById<Button>(R.id.btnTargetTime).setOnClickListener {
            showNumericInputDialog("실행 시간 입력 (초)", "자동 실행 유지 시간(초) (예: 180)") { value ->
                controller.setTargetDurationSeconds(value)
            }
        }

        windowManager.addView(floatingView, params)
    }

    private fun showNumericInputDialog(title: String, hint: String, onConfirm: (Int?) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            this.hint = hint
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("적용") { _, _ ->
                onConfirm(input.text.toString().toIntOrNull())
            }
            .setNegativeButton("해제") { _, _ ->
                onConfirm(null)
            }
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun observeState() {
        serviceScope.launch {
            controller.state.collect { state ->
                val scoreInfo = state.targetScore?.let { " | 목표:${it}" } ?: ""
                val timeInfo = state.remainingTimeMs?.let { " | ${it / 1000}s" } ?: ""
                tvStatusBanner.text = "${state.statusMessage}$scoreInfo$timeInfo"

                if (state.mode == HelperMode.RECOMMEND) {
                    hintOverlayView.updateHint(state.lastResult, calibration)
                } else {
                    hintOverlayView.clearHint()
                }
            }
        }
    }

    private fun restartWorkerLoop() {
        workerJob?.cancel()
        val mode = controller.state.value.mode
        if (mode == HelperMode.IDLE) return

        workerJob = serviceScope.launch {
            while (isActive && controller.state.value.mode != HelperMode.IDLE) {
                if (controller.tickTimerAndCheckStop()) break

                val frame = captureScreenBitmap()
                if (frame != null) {
                    val currentScore = withContext(Dispatchers.Default) {
                        analyzer.extractScore(frame, calibration)
                    }
                    controller.updateCurrentScore(currentScore)
                    if (controller.state.value.mode == HelperMode.IDLE) {
                        frame.recycle()
                        break
                    }

                    val solveResult = withContext(Dispatchers.Default) {
                        val grid = analyzer.extractBoardGrid(frame, calibration)
                        val boardMask = solver.boardFromGrid(grid)
                        val blocks = analyzer.extractAvailableBlocks(frame, calibration)
                        solver.solve(boardMask, blocks)
                    }
                    frame.recycle()
                    controller.updateSolveResult(solveResult)

                    if (controller.state.value.mode == HelperMode.AUTO_PLAY && solveResult != null) {
                        executeAutoPlaySteps(solveResult)
                    }
                }
                delay(700L)
            }
        }
    }

    private suspend fun executeAutoPlaySteps(result: com.blockblast.helper.model.SolveResult) {
        val service = AutoDragAccessibilityService.instance ?: return
        // Block Blast는 드래그 시 손가락 위치보다 블록이 약간 위로 들려 보이므로 Y 오프셋을 더해 정확히 안착
        val fingerLiftCompensationY = calibration.boardCellSize * 1.1f

        for (step in result.steps) {
            if (controller.state.value.mode != HelperMode.AUTO_PLAY) break
            if (controller.tickTimerAndCheckStop()) break

            val (startX, startY) = calibration.slotCenter(step.slotIndex)
            val centerRow = step.boardRow + (step.block.height - 1) / 2f
            val centerCol = step.boardCol + (step.block.width - 1) / 2f
            val targetX = calibration.boardRect.left + (centerCol + 0.5f) * calibration.boardCellSize
            val targetY = calibration.boardRect.top + (centerRow + 0.5f) * calibration.boardCellSize + fingerLiftCompensationY

            service.performBlockDrag(startX, startY, targetX, targetY)
            delay(420L)
        }
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "BlockBlastCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun captureScreenBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } finally {
            image.close()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "blockblast_helper_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "BlockBlast 플로팅 도우미",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("BlockBlast 플로팅 도우미 실행 중")
            .setContentText("플로팅 버튼으로 추천/자동 배치를 제어할 수 있습니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1001, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        workerJob?.cancel()
        serviceScope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
        if (::hintOverlayView.isInitialized) windowManager.removeView(hintOverlayView)
    }

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
