package esw.server

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

/** The prepared game pieces the screens look for in the Pieces folder (made by tools/make_pieces.py). */
private val PIECE_NAMES = listOf("skull", "lws", "marker", "tower1", "tower2")

/**
 * Serves card pictures from the assets folder, so art can be swapped without rebuilding.
 * Names are matched leniently: upper/lower case, any image extension, and any sub-folder.
 */
@RestController
class ArtController(private val hub: Hub, @Value("\${esw.version:dev}") private val version: String) {
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

    /** What the server can see: which build is running and how many pictures it found in each folder. */
    @GetMapping("/api/status", produces = ["application/json"])
    fun status(): String {
        rebuildIndex()
        val perFolder = index.keys.groupingBy { it.substringBefore('/') }.eachCount()
        val pieces = PIECE_NAMES.associateWith { index.containsKey("pieces/$it") }
        return obj(
            "version" to version,
            "assets" to hub.assets.absolutePath,
            "folders" to perFolder.toSortedMap(),
            "pieces" to pieces,
        ).toString()
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
        val out = shrink(source, width)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.88f
        }
        val bytes = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bytes).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(out, null, null), params)
        }
        writer.dispose()
        return bytes.toByteArray()
    }

    /**
     * Shrinks a card scan for the screen. Printed cards are made of tiny halftone dots, and a plain one-step
     * shrink turns them into a speckled crosshatch. So a very large scan is first softened a touch to blur the
     * dots away, then halved again and again (which averages neighbouring pixels), and only then finished.
     */
    private fun shrink(source: BufferedImage, width: Int): BufferedImage {
        var current = toRgb(source)
        if (current.width > width * 3 / 2) current = descreen(current)
        while (current.width / 2 >= width) current = draw(current, current.width / 2, current.height / 2, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val height = (source.height.toDouble() * width / source.width).toInt().coerceAtLeast(1)
        return draw(current, width, height, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    }

    private fun toRgb(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_RGB) return source
        val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        g.drawImage(source, 0, 0, null)
        g.dispose()
        return rgb
    }

    private fun descreen(source: BufferedImage): BufferedImage {
        val k = floatArrayOf(1f, 2f, 1f, 2f, 4f, 2f, 1f, 2f, 1f).map { it / 16f }.toFloatArray()
        return ConvolveOp(Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(source, null)
    }

    private fun draw(source: BufferedImage, width: Int, height: Int, interpolation: Any): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        return out
    }
}
