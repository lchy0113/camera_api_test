package com.richgold.camera_api_test

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.*
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    // --- 카메라별 전체 characteristics 출력 기능 ---
    private fun dumpAllCameraCharacteristics() {
        try {
            val cameraIdList = cameraManager.cameraIdList
            for (id in cameraIdList) {
                logLine("==== CameraId: $id ====")
                val chars = cameraManager.getCameraCharacteristics(id)
                // 모든 key를 순회하며 value를 출력
                for (key in chars.keys) {
                    val value = try { chars.get(key) } catch (_: Throwable) { "<error>" }
                    logLine("${key.name} [${key.javaClass.simpleName}]: $value")
                }
            }
        } catch (e: Exception) {
            logLine("[ERROR] dumpAllCameraCharacteristics: ${e.message}")
        }
    }

    private val TAG = "CameraVendorTagTool"

    private lateinit var textureView: TextureView
    private lateinit var etCameraId: EditText
    private lateinit var etKeyName: EditText
    private lateinit var etValue: EditText
    private lateinit var tvLog: TextView
    private lateinit var spType: Spinner
    private lateinit var spCardinality: Spinner

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)

    enum class VType { BYTE, INT32, INT64, FLOAT }
    enum class Cardinality { SINGLE, ARRAY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        textureView = findViewById(R.id.textureView)
        etCameraId = findViewById(R.id.etCameraId)
        etKeyName = findViewById(R.id.etKeyName)
        etValue = findViewById(R.id.etValue)
        tvLog = findViewById(R.id.tvLog)
        spType = findViewById(R.id.spType)
        spCardinality = findViewById(R.id.spCardinality)

        initSpinners()

        // 전체 characteristics 출력 버튼 추가 (임시)
        findViewById<Button?>(R.id.btnDumpChars)?.setOnClickListener { dumpAllCameraCharacteristics() }

        // 기본값: 네 dumpsys 기준 키 자동 입력
        if (etKeyName.text.isNullOrBlank()) {
            etKeyName.setText("com.kdiwin.control.source.available_input_sources")
        }
        if (etValue.text.isNullOrBlank()) {
            etValue.setText("0")
        }

        findViewById<Button>(R.id.btnReadChars).setOnClickListener { readFromCharacteristics() }
        findViewById<Button>(R.id.btnReadResult).setOnClickListener { readFromResultOnce() }
        findViewById<Button>(R.id.btnApplyRequest).setOnClickListener { applyVendorTagToPreviewRequest() }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (hasCameraPermission()) openCamera()
                else requestCameraPermission()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            if (hasCameraPermission()) openCamera()
            else requestCameraPermission()
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun initSpinners() {
        spType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("BYTE", "INT32", "INT64", "FLOAT")
        )
        spCardinality.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("SINGLE", "ARRAY")
        )

        // 기본: BYTE / SINGLE (available_input_sources는 0/1로 사용)
        spType.setSelection(0)
        spCardinality.setSelection(0)
    }

    private fun selectedType(): VType = VType.valueOf(spType.selectedItem.toString())
    private fun selectedCardinality(): Cardinality = Cardinality.valueOf(spCardinality.selectedItem.toString())

    private fun logLine(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val line = "[$ts] $msg"
        Log.i(TAG, line)

        // UI thread에서만 TextView 접근
        runOnUiThread {
            tvLog.append(line + "\n")

            // 자동 스크롤 (맨 아래로)
            val layout = tvLog.layout
            if (layout != null) {
                val scroll = layout.getLineTop(tvLog.lineCount) - tvLog.height
                if (scroll > 0) tvLog.scrollTo(0, scroll) else tvLog.scrollTo(0, 0)
            }
        }
    }

    private fun logSeparator(title: String) {
        logLine("---------------- $title ----------------")
    }



    private fun hasCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            logLine("CAMERA permission denied.")
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun cameraId(): String = etCameraId.text.toString().trim().ifEmpty { "0" }

    private fun openCamera() {
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                logLine("Timeout waiting to lock camera opening.")
                return
            }
            val id = cameraId()
            if (!hasCameraPermission()) {
                cameraOpenCloseLock.release()
                return
            }
            logLine("Opening cameraId=$id")
            cameraManager.openCamera(id, cameraStateCallback, backgroundHandler)
        } catch (e: Exception) {
            logLine("openCamera failed: ${e.message}")
            cameraOpenCloseLock.release()
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            previewRequestBuilder = null
        } catch (e: Exception) {
            logLine("closeCamera error: ${e.message}")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            logLine("Camera opened.")
            createPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            logLine("Camera disconnected.")
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            logLine("Camera error: $error")
            camera.close()
            cameraDevice = null
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        // Preview size: 고정(간단). 필요하면 supported sizes로 개선 가능
        val previewSize = Size(1280, 720)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)

        val surface = Surface(texture)

        try {
            previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        logLine("Preview session configured.")
                        startRepeatingPreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        logLine("Preview session configure failed.")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            logLine("createPreviewSession failed: ${e.message}")
        }
    }

    private fun startRepeatingPreview() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            logLine("Preview repeating started.")
        } catch (e: Exception) {
            logLine("startRepeatingPreview failed: ${e.message}")
        }
    }

    // --- Vendor Tag: Read from CameraCharacteristics (Static) ---
    private fun readFromCharacteristics() {
        logSeparator("READ CHARS")
        val keyName = etKeyName.text.toString().trim()
        if (keyName.isEmpty()) {
            logLine("Key name is empty.")
            return
        }

        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId())
            val t = selectedType()
            val c = selectedCardinality()

            logLine("[DEBUG] CameraCharacteristics read: key=$keyName, type=$t, cardinality=$c")
            val v1 = readAnyFromCharacteristics(chars, keyName, t, c)
            logLine("[DEBUG] Chars[$keyName] ($t/$c) => $v1")

            if (t == VType.BYTE) {
                val vArr = readAnyFromCharacteristics(chars, keyName, VType.BYTE, Cardinality.ARRAY)
                logLine("[DEBUG] Chars[$keyName] (BYTE/ARRAY) => $vArr")
                val vOne = readAnyFromCharacteristics(chars, keyName, VType.BYTE, Cardinality.SINGLE)
                logLine("[DEBUG] Chars[$keyName] (BYTE/SINGLE) => $vOne")
            }
        } catch (e: Exception) {
            logLine("[DEBUG] Read Chars failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun readAnyFromCharacteristics(
        chars: CameraCharacteristics,
        keyName: String,
        type: VType,
        cardinality: Cardinality
    ): String {
        return try {
            when (type) {
                VType.BYTE -> {
                    if (cardinality == Cardinality.ARRAY) {
                        val k = CameraCharacteristics.Key(keyName, ByteArray::class.java)
                        val v = chars.get(k)
                        v?.joinToString { it.toUByte().toString() } ?: "null"
                    } else {
                        val k = CameraCharacteristics.Key(keyName, Byte::class.javaObjectType)
                        val v = chars.get(k)
                        v?.toUByte()?.toString() ?: "null"
                    }
                }

                VType.INT32 -> {
                    if (cardinality == Cardinality.ARRAY) {
                        val k = CameraCharacteristics.Key(keyName, IntArray::class.java)
                        val v = chars.get(k)
                        v?.joinToString() ?: "null"
                    } else {
                        val k = CameraCharacteristics.Key(keyName, Int::class.javaObjectType)
                        val v = chars.get(k)
                        v?.toString() ?: "null"
                    }
                }

                VType.INT64 -> {
                    if (cardinality == Cardinality.ARRAY) {
                        val k = CameraCharacteristics.Key(keyName, LongArray::class.java)
                        val v = chars.get(k)
                        v?.joinToString() ?: "null"
                    } else {
                        val k = CameraCharacteristics.Key(keyName, Long::class.javaObjectType)
                        val v = chars.get(k)
                        v?.toString() ?: "null"
                    }
                }

                VType.FLOAT -> {
                    if (cardinality == Cardinality.ARRAY) {
                        val k = CameraCharacteristics.Key(keyName, FloatArray::class.java)
                        val v = chars.get(k)
                        v?.joinToString() ?: "null"
                    } else {
                        val k = CameraCharacteristics.Key(keyName, Float::class.javaObjectType)
                        val v = chars.get(k)
                        v?.toString() ?: "null"
                    }
                }
            }
        } catch (iae: IllegalArgumentException) {
            "IllegalArgumentException (key/type mismatch or not exposed in Characteristics)"
        }
    }

    // --- Vendor Tag: Read from CaptureResult (Dynamic) ---
    // 기존의 "다음 프레임 기다리기" 대신 "1프레임 캡처"로 즉시 읽기
    private fun readFromResultOnce() {
        logSeparator("READ RESULT")
        val session = captureSession
        val builder = previewRequestBuilder

        if (session == null || builder == null) {
            logLine("No active camera session. Start preview first.")
            return
        }

        logLine("Capturing 1 frame to read CaptureResult...")

        try {
            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        readVendorTagFromResult(result)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            logLine("Read Result failed: ${e.message}")
        }
    }

    private fun readVendorTagFromResult(result: TotalCaptureResult) {
        val keyName = etKeyName.text.toString().trim()
        if (keyName.isEmpty()) {
            logLine("Key name is empty.")
            return
        }

        val t = selectedType()
        val c = selectedCardinality()

        val out = try {
            logLine("[DEBUG] CaptureResult read: key=$keyName, type=$t, cardinality=$c")
            when (t) {
                VType.BYTE -> {
                    if (c == Cardinality.ARRAY) {
                        val k = CaptureResult.Key(keyName, ByteArray::class.java)
                        logLine("[DEBUG] CaptureResult.Key = $k")
                        val v = result.get(k)
                        logLine("[DEBUG] CaptureResult value = ${v?.joinToString { it.toUByte().toString() } ?: "null"}")
                        v?.joinToString { it.toUByte().toString() } ?: "null"
                    } else {
                        val k = CaptureResult.Key(keyName, Byte::class.javaObjectType)
                        logLine("[DEBUG] CaptureResult.Key = $k")
                        val v = result.get(k)
                        logLine("[DEBUG] CaptureResult value = ${v?.toUByte()?.toString() ?: "null"}")
                        v?.toUByte()?.toString() ?: "null"
                    }
                }

                VType.INT32 -> {
                    if (c == Cardinality.ARRAY) {
                        val k = CaptureResult.Key(keyName, IntArray::class.java)
                        logLine("[DEBUG] CaptureResult.Key = $k")
                        val v = result.get(k)
                        logLine("[DEBUG] CaptureResult value = ${v?.joinToString() ?: "null"}")
                        v?.joinToString() ?: "null"
                    } else {
                        val k = CaptureResult.Key(keyName, Int::class.javaObjectType)
                        logLine("[DEBUG] CaptureResult.Key = $k")
                        val v = result.get(k)
                        logLine("[DEBUG] CaptureResult value = ${v?.toString() ?: "null"}")
                        v?.toString() ?: "null"
                    }
                }

                VType.INT64 -> {
                    if (c == Cardinality.ARRAY) {
                        val k = CaptureResult.Key(keyName, LongArray::class.java)
                        logLine("[DEBUG] CaptureResult.Key = $k")
                        val v = result.get(k)
                        logLine("[DEBUG] CaptureResult value = ${v?.joinToString() ?: "null"}")
                        v?.joinToString() ?: "null"
                    } else {
                        val k = CaptureResult.Key(keyName, Long::class.javaObjectType)
                        logLine("[DEBUG] CaptureResult.Key = $k")
                        val v = result.get(k)
                        logLine("[DEBUG] CaptureResult value = ${v?.toString() ?: "null"}")
                        v?.toString() ?: "null"
                    }
                }

                VType.FLOAT -> {
                    if (c == Cardinality.ARRAY) {
                        val k = CaptureResult.Key(keyName, FloatArray::class.java)
                        logLine("[DEBUG] CaptureResult.Key = $k")
                        val v = result.get(k)
                        logLine("[DEBUG] CaptureResult value = ${v?.joinToString() ?: "null"}")
                        v?.joinToString() ?: "null"
                    } else {
                        val k = CaptureResult.Key(keyName, Float::class.javaObjectType)
                        logLine("[DEBUG] CaptureResult.Key = $k")
                        val v = result.get(k)
                        logLine("[DEBUG] CaptureResult value = ${v?.toString() ?: "null"}")
                        v?.toString() ?: "null"
                    }
                }
            }
        } catch (iae: IllegalArgumentException) {
            logLine("[DEBUG] IllegalArgumentException: ${iae.message}")
            iae.printStackTrace()
            "IllegalArgumentException (key/type mismatch or not exposed in Result)"
        } catch (t2: Throwable) {
            logLine("[DEBUG] Error: ${t2.message}")
            t2.printStackTrace()
            "Error: ${t2.message}"
        }

        logLine("[DEBUG] Result[$keyName] ($t/$c) => $out")

        if (t == VType.BYTE) {
            val outArr = safeReadByteFromResult(result, keyName, true)
            logLine("[DEBUG] Result[$keyName] (BYTE/ARRAY) => $outArr")
            val outOne = safeReadByteFromResult(result, keyName, false)
            logLine("[DEBUG] Result[$keyName] (BYTE/SINGLE) => $outOne")
        }
    }

    private fun safeReadByteFromResult(result: TotalCaptureResult, keyName: String, isArray: Boolean): String {
        return try {
            if (isArray) {
                val k = CaptureResult.Key(keyName, ByteArray::class.java)
                val v = result.get(k)
                v?.joinToString { it.toUByte().toString() } ?: "null"
            } else {
                val k = CaptureResult.Key(keyName, Byte::class.javaObjectType)
                val v = result.get(k)
                v?.toUByte()?.toString() ?: "null"
            }
        } catch (_: Throwable) {
            "n/a"
        }
    }

    // --- Vendor Tag: Apply to CaptureRequest (Set) ---
    private fun applyVendorTagToPreviewRequest() {
        logSeparator("APPLY REQUEST")
        val builder = previewRequestBuilder
        val session = captureSession

        if (builder == null || session == null) {
            logLine("No active preview. Start preview first.")
            return
        }

        val keyName = etKeyName.text.toString().trim()
        if (keyName.isEmpty()) {
            logLine("Key name is empty.")
            return
        }

        val valueText = etValue.text.toString().trim()
        if (valueText.isEmpty()) {
            logLine("Value is empty.")
            return
        }

        val t = selectedType()
        val c = selectedCardinality()

        try {
            logLine("[DEBUG] CaptureRequest set: key=$keyName, type=$t, cardinality=$c, value=$valueText")
            when (t) {
                VType.BYTE -> {
                    if (c == Cardinality.ARRAY) {
                        val k = CaptureRequest.Key(keyName, ByteArray::class.java)
                        logLine("[DEBUG] CaptureRequest.Key = $k")
                        val v = parseByteArray(valueText)
                        builder.set(k, v)
                        logLine("[DEBUG] Set Request byte[]: $keyName = ${v.joinToString { it.toUByte().toString() }}")
                    } else {
                        val k = CaptureRequest.Key(keyName, Byte::class.javaObjectType)
                        logLine("[DEBUG] CaptureRequest.Key = $k")
                        val v = parseByte(valueText)
                        builder.set(k, v)
                        logLine("[DEBUG] Set Request byte: $keyName = ${v.toUByte()}")
                    }
                }

                VType.INT32 -> {
                    if (c == Cardinality.ARRAY) {
                        val k = CaptureRequest.Key(keyName, IntArray::class.java)
                        logLine("[DEBUG] CaptureRequest.Key = $k")
                        val v = parseIntArray(valueText)
                        builder.set(k, v)
                        logLine("[DEBUG] Set Request int[]: $keyName = ${v.joinToString()}")
                    } else {
                        val k = CaptureRequest.Key(keyName, Int::class.javaObjectType)
                        logLine("[DEBUG] CaptureRequest.Key = $k")
                        val v = valueText.toInt()
                        builder.set(k, v)
                        logLine("[DEBUG] Set Request int: $keyName = $v")
                    }
                }

                VType.INT64 -> {
                    if (c == Cardinality.ARRAY) {
                        val k = CaptureRequest.Key(keyName, LongArray::class.java)
                        logLine("[DEBUG] CaptureRequest.Key = $k")
                        val v = parseLongArray(valueText)
                        builder.set(k, v)
                        logLine("[DEBUG] Set Request long[]: $keyName = ${v.joinToString()}")
                    } else {
                        val k = CaptureRequest.Key(keyName, Long::class.javaObjectType)
                        logLine("[DEBUG] CaptureRequest.Key = $k")
                        val v = valueText.toLong()
                        builder.set(k, v)
                        logLine("[DEBUG] Set Request long: $keyName = $v")
                    }
                }

                VType.FLOAT -> {
                    if (c == Cardinality.ARRAY) {
                        val k = CaptureRequest.Key(keyName, FloatArray::class.java)
                        logLine("[DEBUG] CaptureRequest.Key = $k")
                        val v = parseFloatArray(valueText)
                        builder.set(k, v)
                        logLine("[DEBUG] Set Request float[]: $keyName = ${v.joinToString()}")
                    } else {
                        val k = CaptureRequest.Key(keyName, Float::class.javaObjectType)
                        logLine("[DEBUG] CaptureRequest.Key = $k")
                        val v = valueText.toFloat()
                        builder.set(k, v)
                        logLine("[DEBUG] Set Request float: $keyName = $v")
                    }
                }
            }

            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            logLine("[DEBUG] Re-applied repeating request with vendor tag.")
        } catch (iae: IllegalArgumentException) {
            logLine("[DEBUG] Set failed: IllegalArgumentException (key/type mismatch or not exposed in Request)")
            iae.printStackTrace()
        } catch (t2: Throwable) {
            logLine("[DEBUG] Set failed: ${t2.message}")
            t2.printStackTrace()
        }
    }

    // --- parsers ---
    private fun parseByte(s: String): Byte {
        val x = s.trim()
        return if (x.startsWith("0x", true)) x.substring(2).toInt(16).toByte() else x.toInt().toByte()
    }

    private fun parseByteArray(s: String): ByteArray {
        val parts = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val out = ByteArray(parts.size)
        for (i in parts.indices) out[i] = parseByte(parts[i])
        return out
    }

    private fun parseIntArray(s: String): IntArray {
        val parts = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.map { it.toInt() }.toIntArray()
    }

    private fun parseLongArray(s: String): LongArray {
        val parts = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.map { it.toLong() }.toLongArray()
    }

    private fun parseFloatArray(s: String): FloatArray {
        val parts = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.map { it.toFloat() }.toFloatArray()
    }
}
