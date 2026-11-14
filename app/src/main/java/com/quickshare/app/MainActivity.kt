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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
    private var selectedUri: android.net.Uri? = null
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var qrImage: ImageView
    private lateinit var receiverAddress: TextView
    @Volatile private var serverSocket: ServerSocket? = null

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val parts = result.contents.split("|")
            if (parts.size == 3 && parts[0] == "QUICKSHARE" && parts[2].toIntOrNull() == PORT) {
                ipInput.setText(parts[1])
                status.text = "گوشی گیرنده شناسایی شد؛ حالا فایل را انتخاب و ارسال کنید."
            } else {
                status.text = "کد QR معتبر QuickShare نیست."
            }
        } else {
            status.text = "اسکن کد QR لغو شد."
        }
    }

    companion object {
        private const val PICK_FILE = 1001
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
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("کد QR گوشی گیرنده را اسکن کنید")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                }
                qrScanner.launch(options)
            }
        }, matchWrap())

        root.addView(Button(this).apply {
            text = "انتخاب فایل"
            setOnClickListener { chooseFile() }
        }, matchWrap())

        root.addView(Button(this).apply {
            text = "ارسال فایل"
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
        ).apply { topMargin = 14; bottomMargin = 10 })

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
        }
        startActivityForResult(intent, PICK_FILE)
    }

    @Deprecated("Kept compatible with the platform-only MVP.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE && resultCode == RESULT_OK) {
            selectedUri = data?.data
            status.text = if (selectedUri != null) "فایل انتخاب شد؛ برای ارسال آماده است." else "فایلی انتخاب نشد."
            selectedUri?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
            }
        }
    }

    private fun sendSelectedFile() {
        val uri = selectedUri
        val host = ipInput.text.toString().trim()
        if (uri == null) {
            Toast.makeText(this, "ابتدا یک فایل انتخاب کنید.", Toast.LENGTH_SHORT).show()
            return
        }
        if (host.isBlank()) {
            ipInput.error = "ابتدا IP را وارد یا QR را اسکن کنید"
            return
        }

        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "در حال اتصال به گیرنده…"

        executor.execute {
            try {
                val name = queryDisplayName(uri) ?: "shared-file"
                val size = querySize(uri)
                Socket(host, PORT).use { socket ->
                    socket.tcpNoDelay = true
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    val nameBytes = name.toByteArray(Charsets.UTF_8)
                    output.writeInt(nameBytes.size)
                    output.write(nameBytes)
                    output.writeLong(size)
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var sent = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            sent += count
                            if (size > 0) {
                                val percent = ((sent * 100) / size).toInt().coerceIn(0, 100)
                                runOnUiThread { progress.progress = percent }
                            }
                        }
                        output.flush()
                    } ?: throw IllegalStateException("فایل قابل خواندن نیست.")
                }
                runOnUiThread {
                    status.text = "ارسال فایل انجام شد."
                    progress.progress = 100
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطا در ارسال: ${e.localizedMessage ?: "اتصال برقرار نشد"}" }
            }
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
                val nameLength = input.readInt()
                require(nameLength in 1..1024) { "نام فایل نامعتبر است." }
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                val safeName = String(nameBytes, Charsets.UTF_8)
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    .ifBlank { "received-file" }
                val size = input.readLong()
                require(size >= -1L) { "اندازه فایل نامعتبر است." }

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/QuickShare")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("امکان ساخت فایل مقصد وجود ندارد.")
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        if (size >= 0L) {
                            var remaining = size
                            while (remaining > 0) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw IllegalStateException("انتقال ناقص بود.")
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                        } else {
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: throw IllegalStateException("امکان نوشتن فایل وجود ندارد.")
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    contentResolver.update(uri, done, null, null)
                    runOnUiThread { status.text = "دریافت شد: $safeName" }
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطا در دریافت فایل: ${e.localizedMessage}" }
            }
        }
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
