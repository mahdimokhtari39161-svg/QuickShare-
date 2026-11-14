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
        private const val MAX_FILES_PER_BATCH = 100
        private const val PORT = 8988
        private const val BUFFER_SIZE = 64 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 24)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFFF5F7F6.toInt())
        }

        root.addView(TextView(this).apply {
            text = "QuickShare"
            textSize = 30f
            setTextColor(0xFF102F2A.toInt())
            gravity = Gravity.CENTER
        }, matchWrap())

        root.addView(TextView(this).apply {
            text = "انتقال فایل در شبکه محلی، بدون فضای ابری"
            textSize = 15f
            setTextColor(0xFF52615D.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 14)
        }, matchWrap())

        receiverAddress = TextView(this).apply {
            text = "برای دریافت، دکمه شروع دریافت را بزنید."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF52615D.toInt())
        }
        root.addView(receiverAddress, matchWrap())

        qrImage = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(12, 12, 12, 12)
        }
        root.addView(qrImage, LinearLayout.LayoutParams(220, 220).apply {
            gravity = Gravity.CENTER
            bottomMargin = 10
        })

        ipInput = EditText(this).apply {
            hint = "IP گیرنده یا اسکن کد QR"
            textSize = 14f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        root.addView(ipInput, matchWrap())

        root.addView(Button(this).apply {
            text = "اسکن کد QR گیرنده"
            setOnClickListener {
                IntentIntegrator(this@MainActivity).apply {
                    setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    setPrompt("کد QR گوشی گیرنده را اسکن کنید")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                }.initiateScan()
            }
        }, matchWrap())

        root.addView(Button(this).apply {
            text = "انتخاب چند فایل"
            setOnClickListener { chooseFile() }
        }, matchWrap())

        root.addView(Button(this).apply {
            text = "ارسال فایل‌های انتخاب‌شده"
            setOnClickListener { sendSelectedFile() }
        }, matchWrap())

        root.addView(Button(this).apply {
            text = "شروع دریافت و نمایش QR"
            setOnClickListener { startReceiving() }
        }, matchWrap())

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 12
        ).apply { topMargin = 14; bottomMargin = 6 })

        transferInfo = TextView(this).apply {
            text = "سرعت و جزئیات انتقال اینجا نمایش داده می‌شود."
            textSize = 12f
            setTextColor(0xFF52615D.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(transferInfo, matchWrap())

        status = TextView(this).apply {
            text = "هر دو گوشی را به یک Wi-Fi یا هات‌اسپات وصل کنید."
            textSize = 14f
            setTextColor(0xFF263A35.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }
        root.addView(status, matchWrap())
        setContentView(root)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 8 }

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
