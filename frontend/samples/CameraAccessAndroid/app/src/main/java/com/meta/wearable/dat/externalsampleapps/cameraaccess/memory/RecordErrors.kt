package com.meta.wearable.dat.externalsampleapps.cameraaccess.memory

/**
 * The sentence shown when loading or changing records fails. Network and HTTP errors arrive as English library
 * text ("Failed to connect to …", "HTTP 500: …"), so only a message the server already wrote in Korean is passed
 * through; everything else becomes one Korean sentence saying what failed and what to try.
 */
object RecordErrors {
    private val hangul = Regex("[가-힣]")

    fun text(error: Throwable, failed: String): String {
        val message = error.message.orEmpty()
        if (hangul.containsMatchIn(message)) return message
        val network = error is java.io.IOException || error.cause is java.io.IOException
        return if (network) "서버에 연결하지 못해 $failed. 인터넷 연결과 설정의 서버 주소를 확인해 주세요."
        else "$failed. 잠시 뒤 다시 시도해 주세요."
    }
}
