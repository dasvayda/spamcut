import { pool } from '../db/pool'
import { v4 as uuidv4 } from 'uuid'
import { redisGet, redisSet, redisDel } from '../services/redisClient'
import { analyzeMessage } from '../services/spamValidator'
import { notifyReportValidated } from '../services/fcmService'
import {
  TagType,
  VALIDATION_THRESHOLD,
  TOKEN_REWARD_BASE,
  TOKEN_REWARD_FIRST_MOVER_MULTIPLIER,
  DAILY_REPORT_LIMIT,
  ANON_DAILY_REPORT_LIMIT,
  REPUTATION_PENALTY_FALSE_POSITIVE,
} from '../types'

const SPAM_CACHE_TTL = 86400 // 24h

export type SpamCheckResult =
  | { isSpam: false; whitelisted?: true; company?: string }
  | { isSpam: true; tagType: TagType; score: number }

export async function checkSpam(phoneNumber: string) {
  // 화이트리스트 확인 — 인증된 기업 번호는 항상 안전
  const { rows: wlRows } = await pool.query(
    `SELECT company_name FROM whitelist WHERE phone_number = $1`,
    [phoneNumber],
  )
  if (wlRows.length > 0) {
    return { isSpam: false, whitelisted: true, company: wlRows[0].company_name }
  }

  // Redis 캐시 확인 (있으면 DB 조회 생략)
  const cacheKey = `spam:${phoneNumber}`
  const cached = await redisGet(cacheKey)
  if (cached) {
    return JSON.parse(cached)
  }

  const { rows } = await pool.query(
    `SELECT phone_number, tag_type, aggregate_score
     FROM spam_master
     WHERE phone_number = $1 AND global_status = 'ACTIVE'`,
    [phoneNumber],
  )

  const result =
    rows.length === 0
      ? { isSpam: false }
      : { isSpam: true, tagType: rows[0].tag_type as TagType, score: rows[0].aggregate_score }

  // 결과 캐싱 (스팸 여부 무관하게 캐시)
  await redisSet(cacheKey, JSON.stringify(result), SPAM_CACHE_TTL)

  return result
}

// 여러 번호를 한 번에 조회 — 앱의 "최신 정보 받기"(최근 수신 내역 일괄 갱신)에서 사용
//
// 단건 checkSpam()을 N번 호출하면 왕복 지연과 rate limit 소모가 커진다.
// 캐시 히트는 Redis에서 바로 채우고, 미스분만 화이트리스트/spam_master를 각각 1회 질의한다.
export async function checkSpamBatch(
  phoneNumbers: string[],
): Promise<Array<{ number: string } & SpamCheckResult>> {
  const unique = Array.from(new Set(phoneNumbers))
  const resolved = new Map<string, SpamCheckResult>()
  const misses: string[] = []

  for (const number of unique) {
    const cached = await redisGet(`spam:${number}`)
    if (cached) resolved.set(number, JSON.parse(cached) as SpamCheckResult)
    else misses.push(number)
  }

  if (misses.length > 0) {
    const { rows: wlRows } = await pool.query(
      `SELECT phone_number, company_name FROM whitelist WHERE phone_number = ANY($1::text[])`,
      [misses],
    )
    const whitelist = new Map<string, string>()
    for (const row of wlRows as Array<{ phone_number: string; company_name: string }>) {
      whitelist.set(row.phone_number, row.company_name)
    }

    const { rows } = await pool.query(
      `SELECT phone_number, tag_type, aggregate_score
       FROM spam_master
       WHERE phone_number = ANY($1::text[]) AND global_status = 'ACTIVE'`,
      [misses],
    )
    const spamRows = new Map<string, { tag_type: TagType; aggregate_score: number }>()
    for (const row of rows as Array<{
      phone_number: string
      tag_type: TagType
      aggregate_score: number
    }>) {
      spamRows.set(row.phone_number, row)
    }

    for (const number of misses) {
      const company = whitelist.get(number)
      if (company) {
        // 화이트리스트는 단건 조회와 동일하게 캐시하지 않는다 (해제 시 즉시 반영되어야 함)
        resolved.set(number, { isSpam: false, whitelisted: true, company })
        continue
      }

      const row = spamRows.get(number)
      const result: SpamCheckResult = row
        ? { isSpam: true, tagType: row.tag_type, score: row.aggregate_score }
        : { isSpam: false }

      await redisSet(`spam:${number}`, JSON.stringify(result), SPAM_CACHE_TTL)
      resolved.set(number, result)
    }
  }

  return unique.map((number) => ({ number, ...(resolved.get(number) as SpamCheckResult) }))
}

export async function submitReport(
  reporterId: string,
  phoneNumber: string,
  tagType: TagType,
  description: string | null,
) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')

    // 신고자 정보 — 익명(phone_number IS NULL)이면 한도가 더 낮다
    const { rows: userRows } = await client.query(
      `SELECT reputation_score, phone_number FROM users WHERE id = $1`,
      [reporterId],
    )
    const isAnonymous = userRows[0]?.phone_number == null
    const dailyLimit = isAnonymous ? ANON_DAILY_REPORT_LIMIT : DAILY_REPORT_LIMIT

    // 일일 신고 한도 확인
    const { rows: limitRows } = await client.query(
      `SELECT COUNT(*) AS cnt FROM spam_reports
       WHERE reporter_id = $1 AND created_at > NOW() - INTERVAL '24 hours'`,
      [reporterId],
    )
    if (parseInt(limitRows[0].cnt, 10) >= dailyLimit) {
      await client.query('ROLLBACK')
      return { error: 'daily_limit_exceeded' }
    }

    // 24h 내 동일 번호 중복 신고 방지
    const { rows: dupRows } = await client.query(
      `SELECT id FROM spam_reports
       WHERE reporter_id = $1 AND phone_number = $2 AND created_at > NOW() - INTERVAL '24 hours'`,
      [reporterId, phoneNumber],
    )
    if (dupRows.length > 0) {
      await client.query('ROLLBACK')
      return { error: 'duplicate_report' }
    }

    // reputation 기반 가중치 계산 (익명은 30 → 가중치 1)
    const reputation = userRows[0]?.reputation_score ?? 100
    const weight = Math.max(1, Math.floor(reputation / 20))

    // 규칙 기반 AI 분류 — 명백한 스팸은 가중치 boosting
    const aiResult = analyzeMessage(description)
    const effectiveWeight = aiResult.shouldAutoValidate ? Math.max(weight, 3) : weight

    const reportId = uuidv4()
    await client.query(
      `INSERT INTO spam_reports (id, reporter_id, phone_number, tag_type, description)
       VALUES ($1, $2, $3, $4, $5)`,
      [reportId, reporterId, phoneNumber, tagType, description],
    )

    // spam_master 집계 점수 누적
    await client.query(
      `INSERT INTO spam_master (phone_number, tag_type, aggregate_score)
       VALUES ($1, $2, $3)
       ON CONFLICT (phone_number) DO UPDATE
         SET aggregate_score = spam_master.aggregate_score + EXCLUDED.aggregate_score,
             tag_type = EXCLUDED.tag_type,
             updated_at = NOW()`,
      [phoneNumber, tagType, effectiveWeight],
    )

    // 임계값 초과 시 VALIDATED 처리 + 토큰 지급
    const { rows: masterRows } = await client.query(
      `SELECT aggregate_score, global_status FROM spam_master WHERE phone_number = $1`,
      [phoneNumber],
    )
    const { aggregate_score, global_status } = masterRows[0]

    let validatedReporters: Array<{ reporter_id: string; reward: number }> = []

    if (aggregate_score >= VALIDATION_THRESHOLD && global_status !== 'ACTIVE') {
      await client.query(
        `UPDATE spam_master SET global_status = 'ACTIVE' WHERE phone_number = $1`,
        [phoneNumber],
      )

      const { rows: pendingReports } = await client.query(
        `UPDATE spam_reports SET status = 'VALIDATED'
         WHERE phone_number = $1 AND status = 'PENDING'
         RETURNING id, reporter_id, created_at`,
        [phoneNumber],
      )

      if (pendingReports.length > 0) {
        const firstMoverId = pendingReports.sort(
          (a: { created_at: Date }, b: { created_at: Date }) =>
            new Date(a.created_at).getTime() - new Date(b.created_at).getTime(),
        )[0].reporter_id

        for (const report of pendingReports) {
          const isFirstMover = report.reporter_id === firstMoverId
          const reward = isFirstMover
            ? TOKEN_REWARD_BASE * TOKEN_REWARD_FIRST_MOVER_MULTIPLIER
            : TOKEN_REWARD_BASE

          await client.query(
            `INSERT INTO token_ledger (id, user_id, transaction_type, amount)
             VALUES ($1, $2, 'EARN', $3)`,
            [uuidv4(), report.reporter_id, reward],
          )
          validatedReporters.push({ reporter_id: report.reporter_id, reward })
        }
      }

      // 캐시 무효화 — 이제 ACTIVE이므로 다음 조회는 DB 읽어야 함
      await redisDel(`spam:${phoneNumber}`)
    }

    await client.query('COMMIT')

    // 트랜잭션 커밋 후 비동기 FCM 푸시 (실패해도 신고는 성공)
    for (const { reporter_id, reward } of validatedReporters) {
      notifyReportValidated(reporter_id, reward).catch(() => {})
    }

    return { reportId, status: 'PENDING' }
  } catch (err) {
    await client.query('ROLLBACK')
    throw err
  } finally {
    client.release()
  }
}

export async function getTokenBalance(userId: string): Promise<number> {
  const { rows } = await pool.query(
    `SELECT
       COALESCE(SUM(CASE WHEN transaction_type IN ('EARN','TRANSFER_IN') THEN amount ELSE 0 END), 0)
       - COALESCE(SUM(CASE WHEN transaction_type IN ('BURN','TRANSFER_OUT') THEN amount ELSE 0 END), 0)
       AS balance
     FROM token_ledger
     WHERE user_id = $1`,
    [userId],
  )
  return parseInt(rows[0].balance, 10)
}

export async function penalizeReporter(reportId: string) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const { rows } = await client.query(
      `UPDATE spam_reports SET status = 'REJECTED' WHERE id = $1 RETURNING reporter_id`,
      [reportId],
    )
    if (rows.length > 0) {
      await client.query(
        `UPDATE users SET reputation_score = GREATEST(0, reputation_score - $1) WHERE id = $2`,
        [REPUTATION_PENALTY_FALSE_POSITIVE, rows[0].reporter_id],
      )
    }
    await client.query('COMMIT')
  } catch (err) {
    await client.query('ROLLBACK')
    throw err
  } finally {
    client.release()
  }
}
