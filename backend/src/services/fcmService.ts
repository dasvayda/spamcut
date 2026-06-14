import { pool } from '../db/pool'

// Firebase Admin SDK는 환경변수 FIREBASE_SERVICE_ACCOUNT 가 있을 때만 초기화
// 없으면 모든 FCM 함수가 silent fail — 배포 전 Firebase 콘솔에서 서비스 계정 JSON 발급 필요
let admin: typeof import('firebase-admin') | null = null

async function getAdmin() {
  if (admin) return admin
  if (!process.env.FIREBASE_SERVICE_ACCOUNT) return null

  try {
    admin = await import('firebase-admin')
    if (!admin.apps.length) {
      const serviceAccount = JSON.parse(
        Buffer.from(process.env.FIREBASE_SERVICE_ACCOUNT, 'base64').toString('utf-8'),
      )
      admin.initializeApp({ credential: admin.credential.cert(serviceAccount) })
    }
    return admin
  } catch (err) {
    console.warn('[FCM] firebase-admin 초기화 실패:', (err as Error).message)
    admin = null
    return null
  }
}

export async function sendPushToUser(
  userId: string,
  title: string,
  body: string,
  data: Record<string, string> = {},
): Promise<void> {
  const sdk = await getAdmin()
  if (!sdk) return

  try {
    const { rows } = await pool.query(
      `SELECT token FROM fcm_tokens WHERE user_id = $1`,
      [userId],
    )
    if (rows.length === 0) return

    const tokens = rows.map((r: { token: string }) => r.token)

    const response = await sdk.messaging().sendEachForMulticast({
      tokens,
      notification: { title, body },
      data,
      android: { priority: 'high' },
    })

    // 만료된 토큰 정리
    const expiredTokens: string[] = []
    response.responses.forEach((res, idx) => {
      if (!res.success && res.error?.code === 'messaging/registration-token-not-registered') {
        expiredTokens.push(tokens[idx])
      }
    })

    if (expiredTokens.length > 0) {
      await pool.query(
        `DELETE FROM fcm_tokens WHERE user_id = $1 AND token = ANY($2)`,
        [userId, expiredTokens],
      )
    }
  } catch (err) {
    console.warn('[FCM] 푸시 발송 실패:', (err as Error).message)
  }
}

export async function notifyReportValidated(userId: string, reward: number): Promise<void> {
  await sendPushToUser(
    userId,
    '신고가 검증되었습니다',
    `+${reward} Token이 지급되었습니다`,
    { type: 'report_validated', reward: String(reward) },
  )
}

export async function notifySubscriptionExpiringSoon(
  userId: string,
  daysRemaining: number,
): Promise<void> {
  await sendPushToUser(
    userId,
    '구독 만료 알림',
    `${daysRemaining}일 후 실시간 알림 구독이 만료됩니다`,
    { type: 'subscription_expiring', days: String(daysRemaining) },
  )
}
