package cn.riyousuode.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Pictures {
    const val MAX_IMAGE = 512 * 1024
    fun directory(context: Context) = context.filesDir.resolve("countdown-images").apply { mkdirs() }
    fun name(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + ".jpg"
    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while(true) { val n = input.read(buffer); if(n < 0) break; require(out.size().toLong() + n <= limit) { "文件超过大小限制" }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    fun validate(bytes: ByteArray) {
        require(bytes.size >= 4 && bytes.size <= MAX_IMAGE && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[bytes.size-2] == 0xFF.toByte() && bytes.last() == 0xD9.toByte()) { "背景图片过大" }
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
        require(info.outMimeType == "image/jpeg" && info.outWidth in 1..1280 && info.outHeight in 1..1280) { "背景图片格式或尺寸无效" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("背景图片已损坏")
        bitmap.recycle()
    }
    // Android 8 fallback reads only the bounded JPEG orientation tag; no platform EXIF parser.
    internal fun jpegOrientation(bytes: ByteArray): Int = runCatching {
        fun u16(offset: Int) = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset+1].toInt() and 255)
        if(bytes.size < 4 || u16(0) != 0xffd8) return@runCatching 1
        var cursor = 2
        while(cursor+4 <= bytes.size && bytes[cursor] == 0xff.toByte()) {
            val marker = bytes[cursor+1].toInt() and 255
            if(marker == 0xda || marker == 0xd9) break
            val length = u16(cursor+2)
            if(length < 2 || cursor.toLong()+2+length > bytes.size) break
            val start = cursor+4; val end = cursor+2+length
            if(marker == 0xe1 && length >= 16 && bytes.copyOfRange(start,start+6).contentEquals(byteArrayOf(69,120,105,102,0,0))) {
                val tiff = start+6
                val order = when(u16(tiff)) { 0x4949 -> java.nio.ByteOrder.LITTLE_ENDIAN; 0x4d4d -> java.nio.ByteOrder.BIG_ENDIAN; else -> return@runCatching 1 }
                val buffer = ByteBuffer.wrap(bytes).order(order)
                if(buffer.getShort(tiff+2).toInt() != 42) return@runCatching 1
                val offset = buffer.getInt(tiff+4)
                if(offset < 8 || tiff.toLong()+offset+2 > end) return@runCatching 1
                val directory = tiff+offset; val count = buffer.getShort(directory).toInt() and 65535
                if(directory.toLong()+2+count.toLong()*12 > end) return@runCatching 1
                repeat(count) { index ->
                    val entry=directory+2+index*12
                    if((buffer.getShort(entry).toInt() and 65535)==0x112 && buffer.getShort(entry+2).toInt()==3 && buffer.getInt(entry+4)==1) {
                        return@runCatching (buffer.getShort(entry+8).toInt() and 65535).takeIf { it in 1..8 } ?: 1
                    }
                }
            }
            cursor=end
        }
        1
    }.getOrDefault(1)
    fun import(context: Context, uri: Uri): ByteArray {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { readBounded(it, 32 * 1024 * 1024) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
        val bitmap = if(Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val scale = minOf(1.0, 1280.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            var sample = 1
            while(maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("无法读取图片")
            val orientation = jpegOrientation(bytes)
            val matrix = Matrix().apply {
                when(orientation) {
                    2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
                }
            }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if(it !== raw) raw.recycle() }
        }
        // Store a bounded image; the card applies center-crop at its actual accessible height.
        var scaled = bitmap
        val scale = minOf(1.0, 1280.0 / maxOf(bitmap.width, bitmap.height))
        if(scale < 1) { scaled = Bitmap.createScaledBitmap(bitmap, maxOf(1,(bitmap.width*scale).toInt()), maxOf(1,(bitmap.height*scale).toInt()), true); bitmap.recycle() }
        var quality = 90
        var result: ByteArray
        while(true) {
            result = ByteArrayOutputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }
            if(result.size <= MAX_IMAGE) break
            if(quality > 40) quality -= 10 else {
                val smaller = Bitmap.createScaledBitmap(scaled, maxOf(1,scaled.width*3/4), maxOf(1,scaled.height*3/4), true)
                scaled.recycle(); scaled = smaller
            }
        }
        scaled.recycle(); validate(result); return result
    }
    fun put(context: Context, bytes: ByteArray): String {
        validate(bytes)
        val name = name(bytes); val target = directory(context).resolve(name)
        if(!target.exists()) {
            val temp = directory(context).resolve("${uid()}.tmp")
            try { temp.writeBytes(bytes); check(temp.renameTo(target)) { "图片保存失败" } } finally { temp.delete() }
        }
        return name
    }
    fun cleanup(context: Context, state: AppState) {
        val used = state.countdowns.mapNotNull { it.photo }.toSet()
        directory(context).listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }
}

data class BackupBundle(val state: AppState, val images: Map<String, ByteArray>)
object ArchiveBackup {
    const val LIMIT = 64 * 1024 * 1024
    fun encode(state: AppState, images: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("data.json", Backup.encode(state).toByteArray(Charsets.UTF_8))
            state.countdowns.mapNotNull { it.photo }.distinct().forEach { name ->
                val bytes = requireNotNull(images[name]) { "备份缺少背景图片" }; Pictures.validate(bytes)
                require(Pictures.name(bytes) == name); entry("images/$name", bytes)
            }
        }
        return out.toByteArray().also { require(it.size <= LIMIT) }
    }
    fun decode(bytes: ByteArray): BackupBundle {
        require(bytes.size <= LIMIT) { "备份超过 64 MiB" }
        if(bytes.size < 2 || bytes[0] != 80.toByte() || bytes[1] != 75.toByte()) {
            val state = Backup.decode(bytes.toString(Charsets.UTF_8))
            require(state.countdowns.none { it.photo != null }) { "含图片的备份必须使用 ZIP" }
            return BackupBundle(state, emptyMap())
        }
        // Require the complete ZIP central directory, not only recoverable local entries.
        require(bytes.size >= 22) { "ZIP 已截断" }
        val end = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val tail = bytes.size - 22
        require(end.getInt(tail) == 0x06054b50 && end.getShort(tail+20).toInt() == 0) { "ZIP 结尾无效" }
        require(end.getShort(tail+4).toInt() == 0 && end.getShort(tail+6).toInt() == 0) { "不支持分卷 ZIP" }
        val count = end.getShort(tail+10).toInt() and 65535
        require(count in 1..101 && (end.getShort(tail+8).toInt() and 65535) == count)
        val directoryStart = end.getInt(tail+16); val directorySize = end.getInt(tail+12)
        require(directoryStart >= 0 && directorySize > 0 && directoryStart.toLong()+directorySize == tail.toLong()) { "ZIP 目录无效" }
        require(end.getInt(directoryStart) == 0x02014b50) { "ZIP 目录缺失" }
        var json: ByteArray? = null; val images = mutableMapOf<String, ByteArray>(); val seen = mutableSetOf<String>(); var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while(true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && seen.add(entry.name) && seen.size <= 101) { "备份含重复或多余文件" }
                require(entry.name == "data.json" || Regex("images/[a-f0-9]{64}\\.jpg").matches(entry.name)) { "备份文件路径无效" }
                val data = Pictures.readBounded(zip, if(entry.name == "data.json") 10*1024*1024 else Pictures.MAX_IMAGE)
                total += data.size; require(total <= LIMIT) { "解压内容超过 64 MiB" }
                if(entry.name == "data.json") json = data else {
                    Pictures.validate(data); val name = entry.name.removePrefix("images/")
                    require(Pictures.name(data) == name) { "背景图片校验失败" }; images[name] = data
                }
                zip.closeEntry()
            }
        }
        require(seen.size == count) { "ZIP 文件数量不符" }
        val state = Backup.decode(requireNotNull(json) { "备份缺少 data.json" }.toString(Charsets.UTF_8))
        require(state.countdowns.mapNotNull { it.photo }.toSet() == images.keys) { "背景图片缺失或存在多余图片" }
        return BackupBundle(state, images)
    }
    fun collect(context: Context, state: AppState) = state.countdowns.mapNotNull { it.photo }.distinct().associateWith {
        Pictures.directory(context).resolve(it).inputStream().use { input -> Pictures.readBounded(input, Pictures.MAX_IMAGE) }
    }
}
