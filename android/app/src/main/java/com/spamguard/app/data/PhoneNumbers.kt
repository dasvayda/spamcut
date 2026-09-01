package com.spamguard.app.data

// 수신 번호를 서버가 요구하는 E.164 형식(+국가코드)으로 맞춘다.
//
// WHY: 통신사가 넘겨주는 발신 번호는 "010-1234-5678", "01012345678", "+82 10 1234 5678" 등
//      형식이 제각각이다. 로컬 저장 시점에 한 번만 정규화해 두면
//      조회·신고·중복 판정이 모두 같은 키로 동작한다.
object PhoneNumbers {

    // 기본 국가 코드 — 한국. Stage 4 글로벌 확장 시 SIM MCC 기반으로 대체한다.
    // TODO(Stage 4): TelephonyManager.simCountryIso 로 국가 코드 자동 판별
    private const val DEFAULT_COUNTRY_CODE = "82"

    private val E164_PATTERN = Regex("^\\+[1-9]\\d{6,14}$")

    fun toE164(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val cleaned = raw.replace(Regex("[\\s\\-()]"), "")

        val candidate = when {
            cleaned.startsWith("+") -> cleaned
            // 국제 전화 접두사 00 → +
            cleaned.startsWith("00") -> "+" + cleaned.removePrefix("00")
            // 국내 번호 앞자리 0 제거 후 국가 코드 부착
            cleaned.startsWith("0") -> "+$DEFAULT_COUNTRY_CODE" + cleaned.removePrefix("0")
            cleaned.all { it.isDigit() } -> "+$DEFAULT_COUNTRY_CODE$cleaned"
            else -> return null
        }

        return if (E164_PATTERN.matches(candidate)) candidate else null
    }

    fun isE164(value: String): Boolean = E164_PATTERN.matches(value)

    // 화면 표시용 — +8210... 을 010... 형태로 되돌린다 (국내 번호만)
    fun toDisplay(e164: String): String =
        if (e164.startsWith("+$DEFAULT_COUNTRY_CODE")) {
            "0" + e164.removePrefix("+$DEFAULT_COUNTRY_CODE")
        } else {
            e164
        }
}
