import 'dotenv/config'
import Fastify from 'fastify'
import fastifyJwt from '@fastify/jwt'
import fastifyCors from '@fastify/cors'
import fastifyRateLimit from '@fastify/rate-limit'
import fastifyStatic from '@fastify/static'
import path from 'path'
import { authRoutes } from './routes/auth'
import { spamRoutes } from './routes/spam'
import { walletRoutes } from './routes/wallet'
import { inviteRoutes } from './routes/invite'
import { adminRoutes } from './routes/admin'
import { normalizePhoneMiddleware } from './middleware/normalizePhone'
import { pool } from './db/pool'
import { notifySubscriptionExpiringSoon } from './services/fcmService'

if (!process.env.JWT_SECRET) {
  throw new Error('JWT_SECRET environment variable is required')
}

const server = Fastify({
  logger: {
    transport:
      process.env.NODE_ENV !== 'production'
        ? { target: 'pino-pretty', options: { colorize: true } }
        : undefined,
  },
})

server.register(fastifyCors, { origin: true })

// 웹 클라이언트 정적 파일 서빙 — API 라우트보다 먼저 등록해야 /가 index.html을 반환
server.register(fastifyStatic, {
  root: path.join(__dirname, '../public'),
  prefix: '/',
  // API 경로와 충돌하지 않도록 index.html만 fallback
  wildcard: false,
})

server.register(fastifyJwt, { secret: process.env.JWT_SECRET })

// Rate limiting: /check-spam은 엄격하게, 나머지는 일반 제한
server.register(fastifyRateLimit, {
  global: true,
  max: 200,
  timeWindow: '1 minute',
  keyGenerator: (request) => request.ip,
})

// E.164 전화번호 정규화 — 모든 라우트에 적용
server.addHook('preValidation', normalizePhoneMiddleware)

server.register(authRoutes, { prefix: '/api/v1' })
server.register(spamRoutes, { prefix: '/api/v1' })
server.register(walletRoutes, { prefix: '/api/v1' })
server.register(inviteRoutes, { prefix: '/api/v1' })
server.register(adminRoutes, { prefix: '/api/v1' })

// check-spam은 별도로 더 엄격한 제한 적용
server.after(() => {
  server.route({
    method: 'GET',
    url: '/api/v1/check-spam-strict',
    config: { rateLimit: { max: 100, timeWindow: '1 minute' } },
    handler: async () => ({ note: 'Use /api/v1/check-spam' }),
  })
})

server.get('/health', async () => ({ status: 'ok', version: '0.3.0-beta' }))

// ── 백그라운드 스케줄러 ────────────────────────────────────────
// Reputation 점수 회복 — 매 시간 실행
// 최근 7일 내 신고 거절이 없는 계정에 +1씩 회복 (최대 100)
function startReputationRecovery() {
  setInterval(async () => {
    try {
      await pool.query(`
        UPDATE users
        SET reputation_score = LEAST(100, reputation_score + 1)
        WHERE reputation_score < 100
          AND id NOT IN (
            SELECT DISTINCT reporter_id FROM spam_reports
            WHERE status = 'REJECTED'
              AND reporter_id IS NOT NULL
              AND created_at > NOW() - INTERVAL '7 days'
          )
      `)
    } catch (err) {
      server.log.warn({ err }, '[Scheduler] Reputation 회복 실패')
    }
  }, 60 * 60 * 1000) // 1시간
}

// 구독 만료 D-3 알림 — 매 24시간 실행
function startSubscriptionExpiryNotifier() {
  setInterval(async () => {
    try {
      const { rows } = await pool.query(`
        SELECT user_id,
               EXTRACT(DAY FROM (expires_at - NOW()))::INT AS days_remaining
        FROM user_subscriptions
        WHERE is_active = TRUE
          AND expires_at BETWEEN NOW() + INTERVAL '2 days' AND NOW() + INTERVAL '3 days'
      `)
      for (const row of rows) {
        notifySubscriptionExpiringSoon(row.user_id, row.days_remaining).catch(() => {})
      }
    } catch (err) {
      server.log.warn({ err }, '[Scheduler] 구독 만료 알림 실패')
    }
  }, 24 * 60 * 60 * 1000) // 24시간
}

const PORT = parseInt(process.env.PORT ?? '3000', 10)

server.listen({ port: PORT, host: '0.0.0.0' }, (err) => {
  if (err) {
    server.log.error(err)
    process.exit(1)
  }
  startReputationRecovery()
  startSubscriptionExpiryNotifier()
})
