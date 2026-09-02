package com.spamcut.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.spamcut.app.data.db.entities.RecentContact

// 최근 수신 내역 접근 DAO.
// abstract class 로 둔 이유: record() 가 조회 후 삽입하는 트랜잭션 메서드라 구현부가 필요하다.
// (SQLite UPSERT 는 API 30+ 에서만 지원 — minSdk 26 이므로 사용하지 않는다)
@Dao
abstract class RecentContactDao {

    @Query("SELECT * FROM recent_contacts ORDER BY last_received_at DESC LIMIT :limit")
    abstract suspend fun getRecent(limit: Int): List<RecentContact>

    @Query("SELECT * FROM recent_contacts WHERE phone_number = :phoneNumber LIMIT 1")
    abstract suspend fun findByPhoneNumber(phoneNumber: String): RecentContact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(contact: RecentContact)

    @Query("DELETE FROM recent_contacts WHERE phone_number = :phoneNumber")
    abstract suspend fun deleteByPhoneNumber(phoneNumber: String)

    // 오래된 수신 내역 정리 — 기기에 개인 데이터를 무한정 쌓아두지 않는다
    @Query("DELETE FROM recent_contacts WHERE last_received_at < :cutoff")
    abstract suspend fun evictOlderThan(cutoff: Long)

    @Query(
        """
        UPDATE recent_contacts
        SET tag_type = :tagType, checked_at = :checkedAt
        WHERE phone_number = :phoneNumber
        """
    )
    abstract suspend fun markChecked(phoneNumber: String, tagType: String?, checkedAt: Long)

    @Query("UPDATE recent_contacts SET reported_at = :reportedAt WHERE phone_number = :phoneNumber")
    abstract suspend fun markReported(phoneNumber: String, reportedAt: Long)

    // 수신 1건 기록 — 같은 번호면 횟수만 누적하고 마지막 수신 정보를 갱신한다
    @Transaction
    open suspend fun record(
        phoneNumber: String,
        eventType: String,
        receivedAt: Long = System.currentTimeMillis(),
        messagePreview: String? = null,
    ) {
        val existing = findByPhoneNumber(phoneNumber)
        val merged = if (existing == null) {
            RecentContact(
                phoneNumber = phoneNumber,
                lastEventType = eventType,
                lastReceivedAt = receivedAt,
                smsCount = if (eventType == EVENT_SMS) 1 else 0,
                callCount = if (eventType == EVENT_CALL) 1 else 0,
                lastMessagePreview = messagePreview,
            )
        } else {
            existing.copy(
                lastEventType = eventType,
                lastReceivedAt = receivedAt,
                smsCount = existing.smsCount + if (eventType == EVENT_SMS) 1 else 0,
                callCount = existing.callCount + if (eventType == EVENT_CALL) 1 else 0,
                // 새 미리보기가 없으면 기존 값을 유지 (전화 수신은 미리보기가 없다)
                lastMessagePreview = messagePreview ?: existing.lastMessagePreview,
            )
        }
        upsert(merged)
    }

    companion object {
        const val EVENT_SMS = "SMS"
        const val EVENT_CALL = "CALL"

        // 문자 본문은 앞부분만 보관 — 로컬 저장량과 프라이버시 노출을 함께 줄인다
        const val PREVIEW_MAX_LENGTH = 120
    }
}
