package com.quickshare.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.integration.android.IntentIntegrator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newCachedThreadPool()
    private val selectedUris = mutableListOf<android.net.Uri>()
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var transferInfo: TextView
    private lateinit var qrImage: ImageView
    private lateinit var receiverAddress: TextView
    @Volatile private var serverSocket: ServerSocket? = null

    companion object {
        private const val PICK_FILE = 1001
        private const val CAMERA_PERMISSION_REQUEST = 1002
        private const val MAX_FILES_PER_BATCH = 100
        private const val PORT = 8988
        private const val BUFFER_SIZE = 64 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private var darkMode = false

    private fun buildScreen() {
        darkMode = getSharedPreferences("quickshare_ui", MODE_PRIVATE).getBoolean("dark_mode", false)
        val bg = if (darkMode) 0xFF101827.toInt() else 0xFFF2F6FC.toInt()
        val surface = if (darkMode) 0xFF1B2638.toInt() else 0xFFFFFFFF.toInt()
        val primaryText = if (darkMode) 0xFFF2F6FF.toInt() else 0xFF14233B.toInt()
        val secondaryText = if (darkMode) 0xFFB0BED2.toInt() else 0xFF65758B.toInt()
        val accent = 0xFF2684FF.toInt()

        val scroll = android.widget.ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(bg)
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1677F2.toInt(), 0xFF6554D9.toInt(), 0xFF243B80.toInt())
            ).apply { cornerRadius = dp(28).toFloat() }
        }
        hero.addView(TextView(this).apply {
            text = "↗"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0x33FFFFFF, 60)
            setPadding(dp(13), dp(5), dp(13), dp(5))
        })
        hero.addView(TextView(this).apply {
            text = "QuickShare"
            textSize = 31f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        })
        hero.addView(TextView(this).apply {
            text = "انتقال سریع و آسان فایل؛ مستقیم بین دو گوشی"
            textSize = 14f
            setTextColor(0xFFEAF2FF.toInt())
            gravity = Gravity.CENTER
        })
        val themeButton = Button(this).apply {
            text = if (darkMode) "☀  حالت روشن" else "☾  حالت تاریک"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0x334F6FFF, 40)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                darkMode = !darkMode
                getSharedPreferences("quickshare_ui", MODE_PRIVATE).edit().putBoolean("dark_mode", darkMode).apply()
                buildScreen()
            }
        }
        hero.addView(themeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)
        ).apply { topMargin = dp(14) })
        root.addView(hero, matchWrap().apply { bottomMargin = dp(18) })

        root.addView(sectionTitle("شروع انتقال", primaryText), matchWrap())
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val sendCard = actionCard("↑", "ارسال فایل", "انتخاب و ارسال چند فایل", 0xFF1677F2.toInt(), surface)
        sendCard.setOnClickListener { chooseFile() }
        val receiveCard = actionCard("↓", "دریافت فایل", "نمایش QR برای اتصال", 0xFF16A085.toInt(), surface)
        receiveCard.setOnClickListener { startReceiving() }
        actions.addView(sendCard, LinearLayout.LayoutParams(0, dp(126), 1f).apply { marginEnd = dp(7) })
        actions.addView(receiveCard, LinearLayout.LayoutParams(0, dp(126), 1f).apply { marginStart = dp(7) })
        root.addView(actions, matchWrap().apply { bottomMargin = dp(16) })

        val connectCard = panel(surface)
        connectCard.addView(sectionTitle("اتصال به گوشی گیرنده", primaryText))
        ipInput = EditText(this).apply {
            hint = "آدرس IP گیرنده یا اسکن QR"
            textSize = 14f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            background = rounded(if (darkMode) 0xFF26354B.toInt() else 0xFFF0F4FA.toInt(), 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        connectCard.addView(ipInput, matchWrap())
        val scanButton = Button(this).apply {
            text = "▦  اسکن کد QR"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF5367D9.toInt(), 14)
            setOnClickListener { startQrScanner() }
        }
        connectCard.addView(scanButton, matchWrap())
        root.addView(connectCard, matchWrap().apply { bottomMargin = dp(14) })

        val filesCard = panel(surface)
        filesCard.addView(sectionTitle("فایل‌های ارسالی", primaryText))
        val chooseButton = Button(this).apply {
            text = "＋  انتخاب چند فایل"
            textSize = 14f
            setTextColor(accent)
            background = rounded(if (darkMode) 0xFF203A5A.toInt() else 0xFFE8F1FF.toInt(), 14)
            setOnClickListener { chooseFile() }
        }
        filesCard.addView(chooseButton, matchWrap())
        val sendButton = Button(this).apply {
            text = "ارسال فایل‌های انتخاب‌شده  ➤"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF1677F2.toInt(), 14)
            setOnClickListener { sendSelectedFile() }
        }
        filesCard.addView(sendButton, matchWrap())
        root.addView(filesCard, matchWrap().apply { bottomMargin = dp(14) })

        val receiveCardPanel = panel(surface)
        receiveCardPanel.addView(sectionTitle("کد اتصال و دریافت", primaryText))
        receiverAddress = TextView(this).apply {
            text = "برای دریافت فایل، دکمه «دریافت فایل» را بزنید."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(secondaryText)
            setPadding(dp(4), dp(4), dp(4), dp(10))
        }
        receiveCardPanel.addView(receiverAddress, matchWrap())
        qrImage = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(0xFFFFFFFF.toInt(), 18)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        receiveCardPanel.addView(qrImage, LinearLayout.LayoutParams(dp(210), dp(210)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(10)
        })
        root.addView(receiveCardPanel, matchWrap().apply { bottomMargin = dp(14) })

        val progressCard = panel(surface)
        progressCard.addView(sectionTitle("وضعیت انتقال", primaryText))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            visibility = View.GONE
        }
        progressCard.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(9)
        ).apply { bottomMargin = dp(10) })
        transferInfo = TextView(this).apply {
            text = "سرعت و حجم انتقال اینجا نمایش داده می‌شود."
            textSize = 13f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
        }
        progressCard.addView(transferInfo, matchWrap())
        status = TextView(this).apply {
            text = "هر دو گوشی را به یک Wi-Fi یا هات‌اسپات وصل کنید."
            textSize = 13f
            setTextColor(primaryText)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        progressCard.addView(status, matchWrap())
        root.addView(progressCard, matchWrap())

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun panel(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(12))
        background = rounded(color, 22)
        elevation = dp(2).toFloat()
    }

    private fun sectionTitle(value: String, color: Int) = TextView(this).apply {
        text = value
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(color)
        gravity = Gravity.RIGHT
        setPadding(0, 0, 0, dp(12))
    }

    private fun actionCard(symbol: String, title: String, subtitle: String, accent: Int, surface: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(10))
            background = rounded(surface, 20)
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            addView(TextView(this@MainActivity).apply {
                text = symbol
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = rounded(accent, 50)
                setPadding(dp(11), dp(2), dp(11), dp(2))
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (darkMode) 0xFFF2F6FF.toInt() else 0xFF14233B.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(2))
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 10f
                setTextColor(if (darkMode) 0xFFB0BED2.toInt() else 0xFF65758B.toInt())
                gravity = Gravity.CENTER
            })
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 8 }

    private fun startQrScanner() {
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
            return
        }
        launchQrScanner()
    }

    private fun launchQrScanner() {
        try {
            IntentIntegrator(this).apply {
                setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                setPrompt("کد QR گوشی گیرنده را اسکن کنید")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }.initiateScan()
        } catch (e: Exception) {
            status.text = "باز کردن دوربین ناموفق بود: ${e.localizedMessage ?: "خطای ناشناخته"}"
            Toast.makeText(this, "دسترسی دوربین را در تنظیمات برنامه بررسی کنید.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                launchQrScanner()
            } else {
                status.text = "برای اسکن QR باید مجوز دوربین را فعال کنید."
                Toast.makeText(this, "مجوز دوربین برای اسکن کد لازم است.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, PICK_FILE)
    }

    @Deprecated("Kept compatible with the platform-only MVP.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IntentIntegrator.REQUEST_CODE) {
            val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
            val contents = scan?.contents
            if (contents != null) {
                val parts = contents.split("|")
                if (parts.size == 3 && parts[0] == "QUICKSHARE" && parts[2].toIntOrNull() == PORT) {
                    ipInput.setText(parts[1])
                    status.text = "گوشی گیرنده شناسایی شد؛ حالا فایل را انتخاب و ارسال کنید."
                } else {
                    status.text = "کد QR معتبر QuickShare نیست."
                }
            } else {
                status.text = "اسکن کد QR لغو شد."
            }
        } else if (requestCode == PICK_FILE && resultCode == RESULT_OK) {
            selectedUris.clear()
            data?.clipData?.let { clip ->
                for (i in 0 until minOf(clip.itemCount, MAX_FILES_PER_BATCH)) {
                    clip.getItemAt(i).uri?.let { selectedUris.add(it) }
                }
            } ?: data?.data?.let { selectedUris.add(it) }
            selectedUris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
            }
            status.text = if (selectedUris.isNotEmpty()) {
                "${selectedUris.size} فایل انتخاب شد؛ برای ارسال آماده است."
            } else "فایلی انتخاب نشد."
        }
    }

    private fun sendSelectedFile() {
        val files = selectedUris.toList()
        val host = ipInput.text.toString().trim()
        if (files.isEmpty()) { Toast.makeText(this, "ابتدا یک یا چند فایل انتخاب کنید.", Toast.LENGTH_SHORT).show(); return }
        if (host.isBlank()) { ipInput.error = "ابتدا IP را وارد یا QR را اسکن کنید"; return }
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "در حال اتصال برای ارسال ${files.size} فایل…"
        executor.execute {
            try {
                val prepared = files.map { uri ->
                    val name = queryDisplayName(uri) ?: "shared-file"
                    val size = querySize(uri)
                    if (size < 0L) throw IllegalStateException("اندازه فایل «$name» مشخص نیست؛ فایل را از حافظه داخلی انتخاب کنید.")
                    Triple(uri, name, size)
                }
                val totalBytes = prepared.sumOf { it.third }.coerceAtLeast(1L)
                var totalSent = 0L
                val transferStarted = System.nanoTime()
                var lastUiUpdate = transferStarted
                Socket(host, PORT).use { socket ->
                    socket.tcpNoDelay = true
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    output.writeInt(prepared.size)
                    prepared.forEach { (uri, name, size) ->
                        val nameBytes = name.toByteArray(Charsets.UTF_8)
                        output.writeInt(nameBytes.size); output.write(nameBytes); output.writeLong(size)
                        contentResolver.openInputStream(uri)?.use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var fileSent = 0L
                            while (fileSent < size) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - fileSent).toInt())
                                if (count < 0) throw IllegalStateException("خواندن فایل «$name» ناقص بود.")
                                output.write(buffer, 0, count); fileSent += count; totalSent += count
                                val now = System.nanoTime()
                                if (now - lastUiUpdate >= 250_000_000L || totalSent == totalBytes) {
                                    val percent = ((totalSent * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    val seconds = ((now - transferStarted).coerceAtLeast(1L)) / 1_000_000_000.0
                                    val speed = totalSent / seconds
                                    val detail = "${formatBytes(totalSent)} / ${formatBytes(totalBytes)}  •  ${formatBytes(speed.toLong())}/ثانیه"
                                    runOnUiThread { progress.progress = percent; transferInfo.text = detail }
                                    lastUiUpdate = now
                                }
                            }
                        } ?: throw IllegalStateException("فایل «$name» قابل خواندن نیست.")
                    }
                    output.flush()
                }
                runOnUiThread { status.text = "ارسال هر ${prepared.size} فایل با موفقیت انجام شد."; progress.progress = 100; transferInfo.text = "ارسال کامل شد • ${formatBytes(totalBytes)}" }
            } catch (e: Exception) { runOnUiThread { status.text = "خطا در ارسال: ${e.localizedMessage ?: "اتصال برقرار نشد"}" } }
        }
    }
    private fun startReceiving() {
        if (serverSocket != null) {
            status.text = "سرویس دریافت فعال است."
            return
        }
        executor.execute {
            try {
                val ip = findLocalIpv4()
                    ?: throw IllegalStateException("IP محلی پیدا نشد؛ Wi-Fi را بررسی کنید.")
                val server = ServerSocket(PORT)
                serverSocket = server
                val qr = createQrBitmap("QUICKSHARE|$ip|$PORT")
                runOnUiThread {
                    qrImage.setImageBitmap(qr)
                    qrImage.visibility = View.VISIBLE
                    receiverAddress.text = "آدرس گیرنده: $ip:$PORT"
                    status.text = "آماده دریافت؛ با گوشی فرستنده QR را اسکن کنید."
                }
                while (!server.isClosed) {
                    val client = server.accept()
                    executor.execute { receiveFile(client) }
                }
            } catch (e: SocketException) {
                runOnUiThread { status.text = "سرویس دریافت متوقف شد." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطا در دریافت: ${e.localizedMessage}" }
            }
        }
    }

    private fun findLocalIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!network.isUp || network.isLoopback) continue
            val addresses = network.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    private fun createQrBitmap(payload: String): Bitmap {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 640, 640)
        val pixels = IntArray(640 * 640)
        for (y in 0 until 640) {
            for (x in 0 until 640) {
                pixels[y * 640 + x] = if (matrix[x, y]) 0xFF102F2A.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888)
    }

    private fun receiveFile(socket: Socket) {
        socket.use { client ->
            try {
                val input = DataInputStream(BufferedInputStream(client.getInputStream()))
                val fileCount = input.readInt()
                require(fileCount in 1..MAX_FILES_PER_BATCH) { "تعداد فایل‌ها نامعتبر است." }
                repeat(fileCount) { index ->
                    val nameLength = input.readInt()
                    require(nameLength in 1..1024) { "نام فایل نامعتبر است." }
                    val nameBytes = ByteArray(nameLength); input.readFully(nameBytes)
                    val safeName = String(nameBytes, Charsets.UTF_8)
                        .substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[\\u0000-\\u001F\\u007F]"), "_")
                        .take(180).ifBlank { "received-file" }
                    val size = input.readLong()
                    require(size >= 0L) { "اندازه فایل نامعتبر است." }
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/QuickShare")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("امکان ساخت فایل مقصد وجود ندارد.")
                    try {
                        val fileStarted = System.nanoTime()
                        var lastUiUpdate = fileStarted
                        var received = 0L
                        contentResolver.openOutputStream(uri)?.use { output ->
                            val buffer = ByteArray(BUFFER_SIZE); var remaining = size
                            while (remaining > 0) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw IllegalStateException("انتقال فایل «$safeName» ناقص بود.")
                                output.write(buffer, 0, count); remaining -= count; received += count
                                val now = System.nanoTime()
                                if (now - lastUiUpdate >= 250_000_000L || remaining == 0L) {
                                    val percent = ((received * 100) / size.coerceAtLeast(1L)).toInt().coerceIn(0, 100)
                                    val seconds = ((now - fileStarted).coerceAtLeast(1L)) / 1_000_000_000.0
                                    val speed = received / seconds
                                    runOnUiThread {
                                        progress.visibility = View.VISIBLE
                                        progress.progress = percent
                                        transferInfo.text = "$safeName • ${formatBytes(received)} / ${formatBytes(size)} • ${formatBytes(speed.toLong())}/ثانیه"
                                    }
                                    lastUiUpdate = now
                                }
                            }
                        } ?: throw IllegalStateException("امکان نوشتن فایل وجود ندارد.")
                        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                        contentResolver.update(uri, done, null, null)
                        val current = index + 1
                        runOnUiThread { status.text = "دریافت $current از $fileCount: $safeName" }
                    } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
                }
                runOnUiThread { status.text = "دریافت هر $fileCount فایل کامل شد؛ پوشه Download/QuickShare" }
            } catch (e: Exception) { runOnUiThread { status.text = "خطا در دریافت فایل‌ها: ${e.localizedMessage}" } }
        }
    }
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do { value /= 1024.0; unit++ } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    private fun queryDisplayName(uri: android.net.Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        return null
    }

    private fun querySize(uri: android.net.Uri): Long {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
            }
        return -1L
    }

    override fun onDestroy() {
        try { serverSocket?.close() } catch (_: Exception) { }
        executor.shutdownNow()
        super.onDestroy()
    }
}
