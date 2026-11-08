package fit.hotsync.pacer.recording

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Minimal FIT (Garmin Flexible and Interoperable data Transfer) activity encoder.
 *
 * Strava reads FIT completely, including running cadence and running dynamics,
 * which it ignored in our TCX files. Messages written:
 *   file_id, event (timer start), record x N, lap x segments, event (timer stop),
 *   session, activity.
 * Heart rate is optional so the Strava copy can leave it out entirely.
 *
 * Field numbers/scales follow the FIT SDK profile (record, lap, session, activity).
 */
object FitWriter {

    private const val FIT_EPOCH_OFFSET_S = 631_065_600L   // 1989-12-31T00:00:00Z
    private const val PROFILE_VERSION = 2132
    private const val SPORT_RUNNING = 1

    // Base types
    private const val ENUM = 0x00
    private const val UINT8 = 0x02
    private const val UINT16 = 0x84
    private const val SINT32 = 0x85
    private const val UINT32 = 0x86
    private const val UINT32Z = 0x8C

    private class Field(val num: Int, val size: Int, val type: Int)

    private class Def(val local: Int, val global: Int, val fields: List<Field>)

    fun write(recording: RunRecording, laps: List<LapInfo>, includeHeartRate: Boolean): ByteArray {
        val points = recording.points
        val data = ByteArrayOutputStream()
        val w = Writer(data)
        val start = fitTime(points.firstOrNull()?.epochMs ?: recording.startEpochMs)
        val end = fitTime(points.lastOrNull()?.epochMs ?: recording.startEpochMs)

        // file_id
        val fileId = Def(0, 0, listOf(Field(0, 1, ENUM), Field(1, 2, UINT16), Field(2, 2, UINT16),
            Field(3, 4, UINT32Z), Field(4, 4, UINT32)))
        w.define(fileId)
        w.data(fileId) { u8(4); u16(255); u16(1); u32(1); u32(start) } // activity, development manufacturer

        // Timer start
        val event = Def(1, 21, listOf(Field(253, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM)))
        w.define(event)
        w.data(event) { u32(start); u8(0); u8(0) } // timer, start

        // Records
        val recordFields = buildList {
            add(Field(253, 4, UINT32))   // timestamp
            add(Field(0, 4, SINT32))     // position_lat (semicircles)
            add(Field(1, 4, SINT32))     // position_long
            add(Field(2, 2, UINT16))     // altitude: (m + 500) * 5
            if (includeHeartRate) add(Field(3, 1, UINT8)) // heart_rate
            add(Field(4, 1, UINT8))      // cadence: strides/min (one foot), like Garmin
            add(Field(5, 4, UINT32))     // distance: cm
            add(Field(39, 2, UINT16))    // vertical_oscillation: mm * 10
            add(Field(41, 2, UINT16))    // stance_time (ground contact): ms * 10
            add(Field(83, 2, UINT16))    // vertical_ratio: % * 100
            add(Field(85, 2, UINT16))    // step_length: mm * 10
        }
        val record = Def(2, 20, recordFields)
        w.define(record)
        for (p in points) {
            w.data(record) {
                u32(fitTime(p.epochMs))
                s32(p.lat?.let { semicircles(it) })
                s32(p.lon?.let { semicircles(it) })
                u16(p.altitudeM?.let { ((it + 500) * 5).roundToInt() })
                if (includeHeartRate) u8(p.heartRate?.takeIf { it in 1..254 })
                u8(p.cadenceSpm?.let { it / 2 }?.takeIf { it in 1..254 })
                u32(p.distanceM.takeIf { it >= 0 }?.let { (it * 100).roundToLong() })
                u16(p.verticalOscillationCm?.let { (it * 100).roundToInt() })   // cm -> mm * 10
                u16(p.groundContactMs?.let { (it * 10).roundToInt() })
                u16(p.verticalRatioPct?.let { (it * 100).roundToInt() })
                u16(p.strideLengthM?.let { (it * 10_000).roundToInt() })       // m -> mm * 10
            }
        }

        // Laps: one per workout segment (consecutive points with the same segment index)
        val lapFields = buildList {
            add(Field(254, 2, UINT16)); add(Field(253, 4, UINT32)); add(Field(0, 1, ENUM)); add(Field(1, 1, ENUM))
            add(Field(2, 4, UINT32)); add(Field(7, 4, UINT32)); add(Field(8, 4, UINT32)); add(Field(9, 4, UINT32))
            add(Field(24, 1, ENUM)); add(Field(25, 1, ENUM))
            if (includeHeartRate) { add(Field(15, 1, UINT8)); add(Field(16, 1, UINT8)) }
        }
        val lap = Def(3, 19, lapFields)
        w.define(lap)
        val lapInfo = laps.associateBy { it.segment }
        val groups = groupConsecutive(points)
        var prevEndDistance = points.firstOrNull()?.distanceM ?: 0.0
        var totalTimerS = 0.0
        groups.forEachIndexed { i, g ->
            val lapStart = fitTime(g.first().epochMs)
            val lapEnd = fitTime(g.last().epochMs)
            val elapsed = (g.last().epochMs - g.first().epochMs) / 1000.0 + 1.0
            val timer = lapInfo[g.first().segment]?.durationMs?.div(1000.0) ?: elapsed
            totalTimerS += timer
            val meters = (g.last().distanceM - prevEndDistance).coerceAtLeast(0.0)
            prevEndDistance = g.last().distanceM
            val hrs = g.mapNotNull { it.heartRate }
            w.data(lap) {
                u16(i); u32(lapEnd); u8(9); u8(1)                    // index, timestamp, event=lap, stop
                u32(lapStart); u32((elapsed * 1000).roundToLong()); u32((timer * 1000).roundToLong())
                u32((meters * 100).roundToLong())
                u8(0); u8(SPORT_RUNNING)                             // trigger manual, running
                if (includeHeartRate) {
                    u8(hrs.takeIf { it.isNotEmpty() }?.average()?.roundToInt())
                    u8(hrs.maxOrNull())
                }
            }
        }

        // Timer stop (stop_all)
        w.data(event) { u32(end); u8(0); u8(4) }

        // Session
        val totalElapsedS = if (points.isEmpty()) 0.0 else (points.last().epochMs - points.first().epochMs) / 1000.0 + 1.0
        val totalMeters = if (points.isEmpty()) 0.0 else points.last().distanceM - points.first().distanceM
        val allHr = points.mapNotNull { it.heartRate }
        val sessionFields = buildList {
            add(Field(254, 2, UINT16)); add(Field(253, 4, UINT32)); add(Field(0, 1, ENUM)); add(Field(1, 1, ENUM))
            add(Field(2, 4, UINT32)); add(Field(5, 1, ENUM)); add(Field(6, 1, ENUM))
            add(Field(7, 4, UINT32)); add(Field(8, 4, UINT32)); add(Field(9, 4, UINT32))
            add(Field(25, 2, UINT16)); add(Field(26, 2, UINT16))
            if (includeHeartRate) { add(Field(16, 1, UINT8)); add(Field(17, 1, UINT8)) }
        }
        val session = Def(4, 18, sessionFields)
        w.define(session)
        w.data(session) {
            u16(0); u32(end); u8(8); u8(1)                           // index, timestamp, event=session, stop
            u32(start); u8(SPORT_RUNNING); u8(0)
            u32((totalElapsedS * 1000).roundToLong()); u32((totalTimerS.coerceAtLeast(0.0) * 1000).roundToLong())
            u32((totalMeters.coerceAtLeast(0.0) * 100).roundToLong())
            u16(0); u16(groups.size)
            if (includeHeartRate) {
                u8(allHr.takeIf { it.isNotEmpty() }?.average()?.roundToInt())
                u8(allHr.maxOrNull())
            }
        }

        // Activity
        val activity = Def(5, 34, listOf(Field(253, 4, UINT32), Field(0, 4, UINT32), Field(1, 2, UINT16),
            Field(2, 1, ENUM), Field(3, 1, ENUM), Field(4, 1, ENUM)))
        w.define(activity)
        w.data(activity) {
            u32(end); u32((totalTimerS * 1000).roundToLong()); u16(1); u8(0); u8(26); u8(1) // manual, activity, stop
        }

        // Header (14 bytes, with header CRC) + data + file CRC
        val body = data.toByteArray()
        val header = ByteArray(14)
        header[0] = 14; header[1] = 0x20 // protocol 2.0
        le16(header, 2, PROFILE_VERSION)
        le32(header, 4, body.size.toLong())
        header[8] = '.'.code.toByte(); header[9] = 'F'.code.toByte(); header[10] = 'I'.code.toByte(); header[11] = 'T'.code.toByte()
        le16(header, 12, crc(header, 0, 12, 0))
        var fileCrc = crc(header, 0, 14, 0)
        fileCrc = crc(body, 0, body.size, fileCrc)
        val out = ByteArrayOutputStream(14 + body.size + 2)
        out.write(header); out.write(body)
        out.write(fileCrc and 0xFF); out.write((fileCrc shr 8) and 0xFF)
        return out.toByteArray()
    }

    private fun groupConsecutive(points: List<TrackPoint>): List<List<TrackPoint>> {
        val out = mutableListOf<MutableList<TrackPoint>>()
        for (p in points) {
            if (out.isEmpty() || out.last().last().segment != p.segment) out += mutableListOf(p) else out.last() += p
        }
        return out
    }

    private fun fitTime(epochMs: Long) = epochMs / 1000 - FIT_EPOCH_OFFSET_S
    private fun semicircles(deg: Double) = (deg * (2147483648.0 / 180.0)).roundToLong().toInt()

    private fun le16(b: ByteArray, at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }
    private fun le32(b: ByteArray, at: Int, v: Long) { for (i in 0..3) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    internal fun crc(bytes: ByteArray, from: Int, to: Int, seed: Int): Int {
        var crc = seed
        for (i in from until to) {
            val byte = bytes[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        }
        return crc
    }

    /** Writes definition and data messages; missing values become FIT "invalid" markers. */
    private class Writer(val out: ByteArrayOutputStream) {
        fun define(def: Def) {
            out.write(0x40 or def.local)
            out.write(0); out.write(0) // reserved, little-endian
            out.write(def.global and 0xFF); out.write((def.global shr 8) and 0xFF)
            out.write(def.fields.size)
            for (f in def.fields) { out.write(f.num); out.write(f.size); out.write(f.type) }
        }

        fun data(def: Def, body: Writer.() -> Unit) {
            out.write(def.local)
            body()
        }

        fun u8(v: Int?) = out.write(v?.takeIf { it in 0..254 } ?: 0xFF)
        fun u16(v: Int?) {
            val x = v?.takeIf { it in 0..0xFFFE } ?: 0xFFFF
            out.write(x and 0xFF); out.write((x shr 8) and 0xFF)
        }
        fun u32(v: Long?) {
            val x = v?.takeIf { it in 0..0xFFFFFFFEL } ?: 0xFFFFFFFFL
            for (i in 0..3) out.write(((x shr (8 * i)) and 0xFF).toInt())
        }
        fun u16(v: Long?) = u16(v?.toInt())
        fun s32(v: Int?) {
            val x = v ?: 0x7FFFFFFF
            for (i in 0..3) out.write((x shr (8 * i)) and 0xFF)
        }
    }

    // Convenience overloads used above
    private fun Writer.u32(v: Int) = u32(v.toLong())
}
