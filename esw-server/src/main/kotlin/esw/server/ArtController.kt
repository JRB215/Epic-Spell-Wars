package esw.server

import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

/**
 * Serves card pictures from the assets folder, so art can be swapped without rebuilding.
 * Names are matched leniently: upper/lower case, any image extension, and any sub-folder.
 */
@RestController
class ArtController(private val hub: Hub) {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    @Volatile private var index: Map<String, File> = emptyMap()
    @Volatile private var indexedAt = 0L

    private fun rebuildIndex() {
        val found = HashMap<String, File>()
        hub.assets.walkTopDown().filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }.forEach {
            val folder = it.parentFile.name.lowercase()
            found["$folder/${it.nameWithoutExtension.lowercase()}"] = it
        }
        index = found
        indexedAt = System.currentTimeMillis()
        cache.clear()
    }

    private fun find(folder: String, name: String): File? {
        val key = "${folder.lowercase()}/${name.lowercase()}"
        index[key]?.let { return it }
        if (System.currentTimeMillis() - indexedAt > 5_000) {
            rebuildIndex()
            index[key]?.let { return it }
        }
        // Last resort: the same file name in any folder.
        return index.entries.firstOrNull { it.key.endsWith("/${name.lowercase()}") }?.value
    }

    @GetMapping("/art/{folder}/{name}")
    fun art(
        @PathVariable("folder") folder: String,
        @PathVariable("name") name: String,
        @RequestParam("w", required = false) width: Int?,
    ): ResponseEntity<ByteArray> {
        val file = find(folder, name) ?: return ResponseEntity.notFound().build()
        val w = width?.coerceIn(60, 1600)
        val bytes = cache.computeIfAbsent("${file.absolutePath}|${file.lastModified()}|$w") {
            if (w == null) file.readBytes() else resized(file, w)
        }
        val type = if (w != null) MediaType.IMAGE_JPEG else MediaType.parseMediaType(
            when (file.extension.lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            },
        )
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic()).body(bytes)
    }

    private fun resized(file: File, width: Int): ByteArray {
        val source = ImageIO.read(file) ?: return file.readBytes()
        if (source.width <= width) return file.readBytes()
        val height = (source.height.toDouble() * width / source.width).toInt().coerceAtLeast(1)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.85f
        }
        val bytes = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bytes).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(out, null, null), params)
        }
        writer.dispose()
        return bytes.toByteArray()
    }
}
