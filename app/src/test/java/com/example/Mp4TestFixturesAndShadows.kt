package com.example

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Builds binary ISO-BMFF MP4 files (single-track, multi-track audio+video, CFR, VFR,
 * zero-duration, unsupported codec, undecodable bitstream, or multi-megabyte streaming files)
 * and provides Robolectric shadows for [MediaExtractor] and [MediaMetadataRetriever] that
 * read ISO-BMFF headers via bounded [RandomAccessFile] seeks without ever reading the whole file
 * into memory.
 */
object Mp4TestFixtureBuilder {

    const val UNDECODABLE_BITSTREAM_MAGIC = "UNDECODABLE_VIDEO_BITSTREAM_MARKER"

    data class TrackSpec(
        val handlerType: String = "vide",
        val codecFourCc: String = "avc1",
        val width: Int = 1280,
        val height: Int = 720,
        val timescale: Int = 1000,
        val durationTicks: Int = 2000,
        val sttsSampleDeltas: List<Int> = List(48) { 42 }
    )

    fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeInt(8 + payload.size)
        dos.writeBytes(type.take(4).padEnd(4, ' '))
        dos.write(payload)
        dos.flush()
        return out.toByteArray()
    }

    fun buildTrakBox(spec: TrackSpec): ByteArray {
        val tkhdPayload = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(0x00000003)
                write(ByteArray(72))
                writeInt(spec.width shl 16)
                writeInt(spec.height shl 16)
                flush()
            }
        }.toByteArray()
        val tkhdBox = box("tkhd", tkhdPayload)

        val mdhdPayload = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(0)
                writeInt(0)
                writeInt(0)
                writeInt(spec.timescale)
                writeInt(spec.durationTicks)
                writeInt(0)
                flush()
            }
        }.toByteArray()
        val mdhdBox = box("mdhd", mdhdPayload)

        val hdlrPayload = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(0)
                writeInt(0)
                writeBytes(spec.handlerType.take(4).padEnd(4, ' '))
                write(ByteArray(12))
                writeBytes("Handler\u0000")
                flush()
            }
        }.toByteArray()
        val hdlrBox = box("hdlr", hdlrPayload)

        val stsdPayload = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(0)
                writeInt(1)
                writeInt(86)
                writeBytes(spec.codecFourCc.take(4).padEnd(4, ' '))
                write(ByteArray(78))
                flush()
            }
        }.toByteArray()
        val stsdBox = box("stsd", stsdPayload)

        val sttsPayload = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(0)
                writeInt(spec.sttsSampleDeltas.size)
                for (delta in spec.sttsSampleDeltas) {
                    writeInt(1)
                    writeInt(delta)
                }
                flush()
            }
        }.toByteArray()
        val sttsBox = box("stts", sttsPayload)

        val stblBox = box("stbl", stsdBox + sttsBox)
        val minfBox = box("minf", stblBox)
        val mdiaBox = box("mdia", mdhdBox + hdlrBox + minfBox)
        return box("trak", tkhdBox + mdiaBox)
    }

    fun buildMp4Bytes(
        handlerType: String = "vide",
        codecFourCc: String = "avc1",
        width: Int = 1280,
        height: Int = 720,
        timescale: Int = 1000,
        durationTicks: Int = 2000,
        sttsSampleDeltas: List<Int> = List(48) { 42 },
        mdatPayloadSize: Int = 1024,
        includeLeadingAudioTrack: Boolean = false,
        undecodableBitstream: Boolean = false
    ): ByteArray {
        val tracks = mutableListOf<TrackSpec>()
        if (includeLeadingAudioTrack) {
            tracks.add(
                TrackSpec(
                    handlerType = "soun",
                    codecFourCc = "mp4a",
                    width = 0,
                    height = 0,
                    timescale = 44100,
                    durationTicks = 88200,
                    sttsSampleDeltas = List(80) { 1024 }
                )
            )
        }
        tracks.add(
            TrackSpec(
                handlerType = handlerType,
                codecFourCc = codecFourCc,
                width = width,
                height = height,
                timescale = timescale,
                durationTicks = durationTicks,
                sttsSampleDeltas = sttsSampleDeltas
            )
        )
        return buildMultiTrackMp4Bytes(
            tracks = tracks,
            movieTimescale = timescale,
            movieDurationTicks = durationTicks,
            mdatPayloadSize = mdatPayloadSize,
            undecodableBitstream = undecodableBitstream
        )
    }

    fun buildMultiTrackMp4Bytes(
        tracks: List<TrackSpec>,
        movieTimescale: Int = 1000,
        movieDurationTicks: Int = 2000,
        mdatPayloadSize: Int = 1024,
        undecodableBitstream: Boolean = false
    ): ByteArray {
        val ftypPayload = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeBytes("isom")
                writeInt(512)
                writeBytes("isomiso2avc1mp41")
                flush()
            }
        }.toByteArray()
        val ftypBox = box("ftyp", ftypPayload)

        val mvhdPayload = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(0)
                writeInt(0)
                writeInt(0)
                writeInt(movieTimescale)
                writeInt(movieDurationTicks)
                write(ByteArray(76))
                flush()
            }
        }.toByteArray()
        val mvhdBox = box("mvhd", mvhdPayload)

        val trakBoxes = tracks.fold(ByteArray(0)) { acc, spec -> acc + buildTrakBox(spec) }
        val moovBox = box("moov", mvhdBox + trakBoxes)

        val mdatPayload = ByteArray(mdatPayloadSize.coerceAtLeast(64)) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        if (undecodableBitstream) {
            val marker = UNDECODABLE_BITSTREAM_MAGIC.toByteArray(StandardCharsets.US_ASCII)
            System.arraycopy(marker, 0, mdatPayload, 0, marker.size.coerceAtMost(mdatPayload.size))
        }
        val mdatBox = box("mdat", mdatPayload)

        return ftypBox + moovBox + mdatBox
    }

    /**
     * Streams a multi-megabyte MP4 file directly to disk without allocating a multi-megabyte ByteArray.
     */
    fun writeLargeStreamingMp4File(
        targetFile: File,
        sampleDeltas: List<Int> = List(60) { 40 },
        mdatBytesToWrite: Long = 9L * 1024L * 1024L
    ): File {
        targetFile.parentFile?.mkdirs()
        val headerAndMoov = buildMultiTrackMp4Bytes(
            tracks = listOf(
                TrackSpec(
                    handlerType = "vide",
                    codecFourCc = "avc1",
                    width = 1920,
                    height = 1080,
                    timescale = 1000,
                    durationTicks = sampleDeltas.sum(),
                    sttsSampleDeltas = sampleDeltas
                )
            ),
            movieTimescale = 1000,
            movieDurationTicks = sampleDeltas.sum(),
            mdatPayloadSize = 64
        )
        // Strip the tiny 72-byte mdat at the end of headerAndMoov and stream a large mdat box instead
        val prefixLength = headerAndMoov.size - (8 + 64)
        FileOutputStream(targetFile).use { fos ->
            fos.write(headerAndMoov, 0, prefixLength)
            val dos = DataOutputStream(fos)
            dos.writeInt((8L + mdatBytesToWrite).toInt())
            dos.writeBytes("mdat")
            val chunk = ByteArray(64 * 1024) { idx -> (idx and 0xFF).toByte() }
            var remaining = mdatBytesToWrite
            while (remaining > 0L) {
                val toWrite = minOf(remaining, chunk.size.toLong()).toInt()
                fos.write(chunk, 0, toWrite)
                remaining -= toWrite
            }
            dos.flush()
        }
        return targetFile
    }
}

/**
 * Bounded-memory streaming parser used by [TestMp4ShadowMediaExtractor] and
 * [TestMp4ShadowMediaMetadataRetriever] to read ISO-BMFF track headers via [RandomAccessFile]
 * without ever loading `mdat` or the entire file into memory.
 */
internal object StreamingIsoBmffHeaderReader {

    data class ParsedTrack(
        val handlerType: String,
        val codecFourCc: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val frameRate: Float,
        val sampleTimestampsUs: LongArray
    )

    data class ParsedContainer(
        val hasFtyp: Boolean,
        val tracks: List<ParsedTrack>,
        val hasUndecodableMarker: Boolean
    )

    fun parseFileHeadersStreaming(file: File): ParsedContainer {
        if (!file.exists() || !file.isFile || file.length() < 16L) {
            throw IOException("File does not exist or is too small: ${file.absolutePath}")
        }
        RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            var hasFtyp = false
            var hasUndecodableMarker = false
            val tracks = mutableListOf<ParsedTrack>()
            var movieTimescale = 1000L
            var movieDurationTicks = 0L

            var pos = 0L
            while (pos + 8L <= fileLen) {
                raf.seek(pos)
                val sizeU32 = raf.readInt().toLong() and 0xFFFFFFFFL
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val boxType = String(typeBytes, StandardCharsets.US_ASCII)

                val headerSize = if (sizeU32 == 1L) 16L else 8L
                val boxSize = when {
                    sizeU32 == 1L -> {
                        if (pos + 16L > fileLen) break
                        raf.readLong()
                    }
                    sizeU32 == 0L -> fileLen - pos
                    else -> sizeU32
                }
                if (boxSize < headerSize || pos + boxSize > fileLen) break

                when (boxType) {
                    "ftyp" -> hasFtyp = true
                    "moov" -> {
                        parseMoov(
                            raf = raf,
                            moovStart = pos + headerSize,
                            moovEnd = pos + boxSize,
                            onMvhd = { ts, dur ->
                                if (ts > 0L) movieTimescale = ts
                                if (dur > 0L) movieDurationTicks = dur
                            },
                            onTrack = { track -> tracks.add(track) },
                            defaultMovieTimescale = { movieTimescale },
                            defaultMovieDurationTicks = { movieDurationTicks }
                        )
                    }
                    "mdat" -> {
                        val payloadStart = pos + headerSize
                        val probeLen = minOf(64L, (pos + boxSize) - payloadStart).toInt()
                        if (probeLen > 0) {
                            raf.seek(payloadStart)
                            val probeBytes = ByteArray(probeLen)
                            raf.readFully(probeBytes)
                            val probeStr = String(probeBytes, StandardCharsets.US_ASCII)
                            if (probeStr.contains(Mp4TestFixtureBuilder.UNDECODABLE_BITSTREAM_MAGIC)) {
                                hasUndecodableMarker = true
                            }
                        }
                    }
                }
                pos += boxSize
            }

            if (!hasFtyp) {
                throw IOException("Missing ISO-BMFF ftyp box in ${file.name}")
            }
            return ParsedContainer(
                hasFtyp = true,
                tracks = tracks,
                hasUndecodableMarker = hasUndecodableMarker
            )
        }
    }

    private fun parseMoov(
        raf: RandomAccessFile,
        moovStart: Long,
        moovEnd: Long,
        onMvhd: (Long, Long) -> Unit,
        onTrack: (ParsedTrack) -> Unit,
        defaultMovieTimescale: () -> Long,
        defaultMovieDurationTicks: () -> Long
    ) {
        var pos = moovStart
        while (pos + 8L <= moovEnd) {
            raf.seek(pos)
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            raf.readFully(typeBytes)
            val type = String(typeBytes, StandardCharsets.US_ASCII)
            if (size < 8L || pos + size > moovEnd) break

            if (type == "mvhd" && size >= 28L) {
                val version = raf.readUnsignedByte()
                raf.skipBytes(3)
                if (version == 1 && size >= 40L) {
                    raf.skipBytes(16)
                    val ts = raf.readInt().toLong() and 0xFFFFFFFFL
                    val dur = raf.readLong()
                    onMvhd(ts, dur)
                } else {
                    raf.skipBytes(8)
                    val ts = raf.readInt().toLong() and 0xFFFFFFFFL
                    val dur = raf.readInt().toLong() and 0xFFFFFFFFL
                    onMvhd(ts, dur)
                }
            } else if (type == "trak") {
                parseTrak(
                    raf = raf,
                    trakStart = pos + 8L,
                    trakEnd = pos + size,
                    fallbackTimescale = defaultMovieTimescale(),
                    fallbackDurationTicks = defaultMovieDurationTicks()
                )?.let(onTrack)
            }
            pos += size
        }
    }

    private fun parseTrak(
        raf: RandomAccessFile,
        trakStart: Long,
        trakEnd: Long,
        fallbackTimescale: Long,
        fallbackDurationTicks: Long
    ): ParsedTrack? {
        var handlerType = ""
        var codecFourCc = ""
        var width = 0
        var height = 0
        var trackTimescale = fallbackTimescale
        var trackDurationTicks = fallbackDurationTicks
        val sampleDeltas = mutableListOf<Long>()

        fun walkBoxes(start: Long, end: Long) {
            var pos = start
            while (pos + 8L <= end) {
                raf.seek(pos)
                val size = raf.readInt().toLong() and 0xFFFFFFFFL
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val type = String(typeBytes, StandardCharsets.US_ASCII)
                if (size < 8L || pos + size > end) break
                val payloadStart = pos + 8L
                val payloadSize = size - 8L

                when (type) {
                    "mdia", "minf", "stbl" -> walkBoxes(payloadStart, pos + size)
                    "tkhd" -> {
                        if (payloadSize >= 84L) {
                            raf.seek(payloadStart)
                            val version = raf.readUnsignedByte()
                            val widthOffset = if (version == 1) payloadStart + 88L else payloadStart + 76L
                            if (widthOffset + 8L <= pos + size) {
                                raf.seek(widthOffset)
                                val wFixed = raf.readInt()
                                val hFixed = raf.readInt()
                                val w = (wFixed ushr 16) and 0xFFFF
                                val h = (hFixed ushr 16) and 0xFFFF
                                if (w > 0 && h > 0) {
                                    width = w
                                    height = h
                                }
                            }
                        }
                    }
                    "mdhd" -> {
                        if (payloadSize >= 20L) {
                            raf.seek(payloadStart)
                            val version = raf.readUnsignedByte()
                            raf.skipBytes(3)
                            if (version == 1 && payloadSize >= 32L) {
                                raf.skipBytes(16)
                                val ts = raf.readInt().toLong() and 0xFFFFFFFFL
                                val dur = raf.readLong()
                                if (ts > 0L) trackTimescale = ts
                                trackDurationTicks = dur
                            } else {
                                raf.skipBytes(8)
                                val ts = raf.readInt().toLong() and 0xFFFFFFFFL
                                val dur = raf.readInt().toLong() and 0xFFFFFFFFL
                                if (ts > 0L) trackTimescale = ts
                                trackDurationTicks = dur
                            }
                        }
                    }
                    "hdlr" -> {
                        if (payloadSize >= 12L) {
                            raf.seek(payloadStart + 8L)
                            val hBytes = ByteArray(4)
                            raf.readFully(hBytes)
                            handlerType = String(hBytes, StandardCharsets.US_ASCII).trim()
                        }
                    }
                    "stsd" -> {
                        if (payloadSize >= 16L) {
                            raf.seek(payloadStart + 12L)
                            val cBytes = ByteArray(4)
                            raf.readFully(cBytes)
                            codecFourCc = String(cBytes, StandardCharsets.US_ASCII).trim().lowercase()
                        }
                    }
                    "stts" -> {
                        if (payloadSize >= 8L) {
                            raf.seek(payloadStart + 4L)
                            val entryCount = (raf.readInt().toLong() and 0xFFFFFFFFL).coerceAtMost(50_000L).toInt()
                            for (i in 0 until entryCount) {
                                if (raf.filePointer + 8L > pos + size) break
                                val sampleCount = (raf.readInt().toLong() and 0xFFFFFFFFL).coerceAtMost(50_000L).toInt()
                                val sampleDelta = raf.readInt().toLong() and 0xFFFFFFFFL
                                repeat(sampleCount) {
                                    if (sampleDeltas.size < 25_000) {
                                        sampleDeltas.add(sampleDelta)
                                    }
                                }
                            }
                        }
                    }
                }
                pos += size
            }
        }

        walkBoxes(trakStart, trakEnd)
        if (handlerType.isEmpty()) return null

        val mime = when (handlerType) {
            "soun" -> "audio/mp4a-latm"
            "vide" -> when (codecFourCc) {
                "avc1", "avc3" -> MediaFormat.MIMETYPE_VIDEO_AVC
                "hvc1", "hev1" -> MediaFormat.MIMETYPE_VIDEO_HEVC
                "vp09" -> MediaFormat.MIMETYPE_VIDEO_VP9
                "av01" -> MediaFormat.MIMETYPE_VIDEO_AV1
                "mp4v" -> MediaFormat.MIMETYPE_VIDEO_MPEG4
                else -> "video/$codecFourCc"
            }
            else -> "application/octet-stream"
        }

        val safeTimescale = trackTimescale.coerceAtLeast(1L)
        val durationUs = (trackDurationTicks * 1_000_000L) / safeTimescale
        val ptsUs = LongArray(sampleDeltas.size)
        var accTicks = 0L
        for (i in sampleDeltas.indices) {
            ptsUs[i] = (accTicks * 1_000_000L) / safeTimescale
            accTicks += sampleDeltas[i]
        }
        val frameRate = if (durationUs > 0L && sampleDeltas.isNotEmpty()) {
            (sampleDeltas.size * 1_000_000f) / durationUs.toFloat()
        } else {
            0f
        }

        return ParsedTrack(
            handlerType = handlerType,
            codecFourCc = codecFourCc,
            mime = mime,
            width = width,
            height = height,
            durationUs = durationUs,
            frameRate = frameRate,
            sampleTimestampsUs = ptsUs
        )
    }
}

/**
 * Robolectric shadow for [MediaExtractor] that reads real binary ISO-BMFF files via
 * [StreamingIsoBmffHeaderReader] (`RandomAccessFile`).
 */
@Implements(MediaExtractor::class)
class TestMp4ShadowMediaExtractor {

    private var tracks: List<StreamingIsoBmffHeaderReader.ParsedTrack> = emptyList()
    private var selectedTrackIndex: Int = -1
    private var currentSampleIndex: Int = 0

    @Implementation
    @Throws(IOException::class)
    fun setDataSource(path: String) {
        val parsed = StreamingIsoBmffHeaderReader.parseFileHeadersStreaming(File(path))
        tracks = parsed.tracks
        selectedTrackIndex = -1
        currentSampleIndex = 0
    }

    @Implementation
    fun getTrackCount(): Int = tracks.size

    @Implementation
    fun getTrackFormat(index: Int): MediaFormat {
        val t = tracks[index]
        return MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, t.mime)
            if (t.width > 0) setInteger(MediaFormat.KEY_WIDTH, t.width)
            if (t.height > 0) setInteger(MediaFormat.KEY_HEIGHT, t.height)
            setLong(MediaFormat.KEY_DURATION, t.durationUs)
            if (t.frameRate > 0f) setFloat(MediaFormat.KEY_FRAME_RATE, t.frameRate)
        }
    }

    @Implementation
    fun selectTrack(index: Int) {
        selectedTrackIndex = index
        currentSampleIndex = 0
    }

    @Implementation
    fun getSampleTime(): Long {
        val t = tracks.getOrNull(selectedTrackIndex) ?: return -1L
        if (currentSampleIndex !in t.sampleTimestampsUs.indices) return -1L
        return t.sampleTimestampsUs[currentSampleIndex]
    }

    @Implementation
    fun advance(): Boolean {
        val t = tracks.getOrNull(selectedTrackIndex) ?: return false
        currentSampleIndex++
        return currentSampleIndex < t.sampleTimestampsUs.size
    }

    @Implementation
    fun release() {
        tracks = emptyList()
        selectedTrackIndex = -1
        currentSampleIndex = 0
    }
}

/**
 * Robolectric shadow for [MediaMetadataRetriever] that decodes frames for valid video tracks
 * and returns `null` when [Mp4TestFixtureBuilder.UNDECODABLE_BITSTREAM_MAGIC] is present.
 */
@Implements(MediaMetadataRetriever::class)
class TestMp4ShadowMediaMetadataRetriever {

    private var parsedContainer: StreamingIsoBmffHeaderReader.ParsedContainer? = null

    @Implementation
    fun setDataSource(path: String) {
        parsedContainer = StreamingIsoBmffHeaderReader.parseFileHeadersStreaming(File(path))
    }

    @Implementation
    fun extractMetadata(keyCode: Int): String? {
        val container = parsedContainer ?: return null
        val videoTrack = container.tracks.firstOrNull { it.mime.startsWith("video/") } ?: return null
        return when (keyCode) {
            MediaMetadataRetriever.METADATA_KEY_DURATION -> (videoTrack.durationUs / 1000L).toString()
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH -> videoTrack.width.toString()
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT -> videoTrack.height.toString()
            MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO -> "yes"
            else -> null
        }
    }

    @Implementation
    fun getFrameAtTime(timeUs: Long, option: Int): Bitmap? {
        val container = parsedContainer ?: return null
        if (container.hasUndecodableMarker) return null
        val videoTrack = container.tracks.firstOrNull { it.mime.startsWith("video/") } ?: return null
        if (videoTrack.width <= 0 || videoTrack.height <= 0 || videoTrack.durationUs <= 0L) return null
        val bmpWidth = videoTrack.width.coerceIn(16, 320)
        val bmpHeight = videoTrack.height.coerceIn(16, 320)
        return Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
    }

    @Implementation
    fun getFrameAtTime(timeUs: Long): Bitmap? {
        return getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }

    @Implementation
    fun release() {
        parsedContainer = null
    }
}
