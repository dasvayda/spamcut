package com.spamguard.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// 최근 수신 내역 — 이 기기로 들어온 문자·전화의 발신 번호를 로컬에 그대로 쌓아둔다.
//
// WHY: 웹사이트에 접속해 번호를 손으로 옮겨 적는 방식은 접근성이 떨어진다.
//      수신 즉시 로컬에 남겨두면 사용자는 목록에서 고르기만 하면 되고,
//      [신고하기] 를 눌렀을 때만 서버로 올라간다 (기본은 기기 안에만 존재).
//
// 번호 단위로 합산 저장한다 — 같은 번호가 여러 번 와도 행이 하나만 유지된다.
@Entity(tableName = "recent_contacts")
data class RecentContact(
    @PrimaryKey
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,

    // 마지막 수신 종류: "SMS" 또는 "CALL"
    @ColumnInfo(name = "last_event_type")
    val lastEventType: String,

    @ColumnInfo(name = "last_received_at")
    val lastReceivedAt: Long,

    @ColumnInfo(name = "sms_count")
    val smsCount: Int = 0,

    @ColumnInfo(name = "call_count")
    val callCount: Int = 0,

    // 문자 본문 앞부분만 보관 — 신고 시 설명 자동 입력에 쓰인다.
    // 서버로는 사용자가 신고 버튼을 눌러 확인한 경우에만 전송된다.
    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String? = null,

    // 서버 판정 결과 캐시: "RED" / "YELLOW" / null(스팸 아님 또는 미확인)
    @ColumnInfo(name = "tag_type")
    val tagType: String? = null,

    // 서버 확인 시각 — 0이면 아직 한 번도 확인하지 않은 번호
    @ColumnInfo(name = "checked_at")
    val checkedAt: Long = 0,

    // 내가 이 번호를 신고한 시각 — 중복 신고 방지 및 목록 표시용
    @ColumnInfo(name = "reported_at")
    val reportedAt: Long? = null,
) {
    // 아직 서버 확인 전인지 여부 — 목록에서 "확인 필요"로 표시
    fun isUnchecked(): Boolean = checkedAt == 0L
}
