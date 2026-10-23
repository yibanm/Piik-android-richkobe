package com.richkobe.piikandroid.capture

/**
 * The JSON messages of Piik's capture contract (protocol 7). Kept free of
 * Android APIs so they can be checked against Piik's Go validators off-device.
 */
object CaptureProtocol {
    class OutputProfile(val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
        fun json() = """{"width":$width,"height":$height,"fps":$fps,"bitrate":$bitrate}"""
    }

    class EncoderEntry(val name: String, val identity: String)

    /** `--probe` reply (nativecapture.Capabilities; unknown fields are rejected). */
    fun probe(platform: String, build: String, encoders: List<EncoderEntry>, systemAudio: Boolean): String {
        val adapters = if (encoders.isEmpty()) "" else {
            val list = encoders.mapIndexed { i, e ->
                """{"index":$i,"name":${jsonString(e.name)},"identity":${jsonString(e.identity)}}"""
            }.joinToString(",")
            """{"index":0,"name":"Android MediaCodec","identity":"android-mediacodec","hardwareH264":[$list]}"""
        }
        return """{"protocol":7,"platform":${jsonString(platform)},"platformBuild":${jsonString(build.take(500))},""" +
            """"videoCapture":true,"softwareVP8":false,"processAudio":false,"systemAudio":$systemAudio,"microphone":true,""" +
            """"adapters":[$adapters]}"""
    }

    const val SOURCES = """[{"kind":"picker","sourceId":"1","title":"Phone screen (choose in the Android dialog)"}]"""

    private fun outputs(list: List<OutputProfile>) = list.joinToString(",", "[", "]") { it.json() }

    /** First status of a video run (nativehost.CaptureState, state "starting"). */
    fun starting(encoderIndex: Int, encoderName: String, list: List<OutputProfile>) =
        """{"state":"starting","codec":"h264","hardwareOnly":true,"adapterIndex":0,""" +
            """"adapterName":"Android MediaCodec","adapterIdentity":"android-mediacodec",""" +
            """"encoderIndex":$encoderIndex,"encoderName":${jsonString(encoderName)},""" +
            """"encoderIdentity":${jsonString(encoderName)},"outputs":${outputs(list)}}"""

    /** Sent after the original output's first decodable frame. */
    fun active(profileLevelId: String, original: OutputProfile, list: List<OutputProfile>) =
        """{"state":"active","codec":"h264","hardwareOnly":true,"profileLevelId":"$profileLevelId",""" +
            """"width":${original.width},"height":${original.height},"fps":${original.fps},"outputs":${outputs(list)}}"""

    const val AUDIO_ACTIVE = """{"state":"active","audio":true}"""

    /** Parsed `--capture-video` / `--encoded-video` arguments. */
    class VideoArgs(
        val encoded: Boolean,
        val encoderIndex: Int,
        val fps: Int,
        val outputs: List<OutputProfile>,
    ) {
        /** Index of the output whose dimensions the active status must report. */
        val original: Int get() = if (encoded) 0 else minOf(1, outputs.size - 1)
        /** The Linux sidecar starts outputs 0 and 1 for screen capture; others wait for "A n 1". */
        fun initiallyEnabled(layer: Int) = !encoded && layer < 2
    }

    fun parseVideoArgs(a: List<String>): VideoArgs {
        val encoded = a.firstOrNull() == "--encoded-video"
        var i: Int
        if (encoded) {
            i = 1
        } else {
            require(a.size >= 5 && a[0] == "--capture-video") { "unsupported capture command" }
            require(a[1] == "picker" || a[1] == "display") { "Android shares the screen chosen in the system dialog" }
            i = 5
        }
        var codec = "h264"
        var encoderIndex = 0
        var fps = 30
        val outputs = ArrayList<OutputProfile>()
        while (i < a.size) {
            when (a[i]) {
                "--adapter-index", "--width", "--height", "--bitrate", "--preference" -> i += 2
                "--mft-index" -> { encoderIndex = a[i + 1].toInt(); i += 2 }
                "--fps" -> { fps = a[i + 1].toInt().coerceIn(1, 60); i += 2 }
                "--codec" -> { codec = a[i + 1]; i += 2 }
                "--protocol-v7", "--show-capture-border" -> i += 1
                "--output" -> {
                    val w = a[i + 1].toInt(); val h = a[i + 2].toInt()
                    val f = a[i + 3].toInt(); val b = a[i + 4].toInt()
                    require(w in 2..Smed.MAX_WIDTH && h in 2..Smed.MAX_HEIGHT && w % 2 == 0 && h % 2 == 0 &&
                        f in 1..60 && b in 1_000..12_000_000) { "output profile is invalid" }
                    outputs.add(OutputProfile(w, h, f, b))
                    i += 5
                }
                else -> throw IllegalArgumentException("unsupported argument ${a[i]}")
            }
        }
        require(codec == "h264" || codec == "auto") { "Android capture encodes H.264 only" }
        require(outputs.isNotEmpty() && outputs.size <= 6) { "output count is invalid" }
        if (encoded) fps = outputs.maxOf { it.fps }
        return VideoArgs(encoded, encoderIndex, fps, outputs)
    }
}
