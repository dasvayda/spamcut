import { FastifyInstance } from 'fastify'
import { z } from 'zod'
import { requireAuth } from '../middleware/auth'
import {
  checkSpam,
  checkSpamBatch,
  listConfirmedSpam,
  submitReport,
  penalizeReporter,
} from '../controllers/spamController'
import { JwtPayload, TagType } from '../types'

const e164 = z.string().regex(/^\+[1-9]\d{6,14}$/, 'Must be E.164 format')

async function optionalUserId(request: { jwtVerify: () => Promise<unknown>; user?: JwtPayload }) {
  try {
    await request.jwtVerify()
    return (request.user as JwtPayload).userId
  } catch {
    return undefined
  }
}

export async function spamRoutes(fastify: FastifyInstance) {
  // GET /api/v1/check-spam?number=+821012345678
  // 공개 조회. JWT가 있으면 내 PENDING 신고를 결과에 붙여 준다.
  fastify.get<{ Querystring: { number: string } }>('/check-spam', async (request, reply) => {
    const parse = e164.safeParse(request.query.number)
    if (!parse.success) {
      return reply.status(400).send({ error: 'Invalid phone number format. Use E.164, e.g. +821012345678' })
    }
    const userId = await optionalUserId(request)
    const result = await checkSpam(parse.data, userId)
    return reply.send(result)
  })

  // POST /api/v1/check-spam/batch  { numbers: ["+8210...", ...] }
  // 앱 "최신 정보 받기" 전용 — 최근 수신 내역의 스팸 판정을 한 번의 요청으로 갱신한다.
  // check-spam과 동일하게 공개 엔드포인트 (로그인 없이도 조회 가능)
  fastify.post('/check-spam/batch', async (request, reply) => {
    const schema = z.object({ numbers: z.array(e164).min(1).max(100) })

    const parse = schema.safeParse(request.body)
    if (!parse.success) {
      return reply.status(400).send({ error: 'invalid_numbers' })
    }

    const results = await checkSpamBatch(parse.data.numbers)
    return reply.send({ results })
  })

  // GET /api/v1/spam/confirmed?since=<ISO>&limit=500
  // 동기화 동의 후 확정 목록을 폰으로 받는다. 점수는 포함하지 않는다.
  fastify.get<{ Querystring: { since?: string; limit?: string } }>(
    '/spam/confirmed',
    { preHandler: [requireAuth] },
    async (request, reply) => {
      const limitRaw = parseInt(request.query.limit ?? '500', 10)
      const limit = Number.isFinite(limitRaw) ? Math.min(Math.max(limitRaw, 1), 500) : 500
      const since = request.query.since
      const result = await listConfirmedSpam(since, limit)
      return reply.send(result)
    },
  )

  // POST /api/v1/report
  fastify.post('/report', { preHandler: [requireAuth] }, async (request, reply) => {
    const schema = z.object({
      phone_number: e164,
      tag_type: z.enum(['RED', 'YELLOW']),
      description: z.string().max(500).nullable().optional(),
    })

    const parse = schema.safeParse(request.body)
    if (!parse.success) return reply.status(400).send({ error: parse.error.flatten() })

    const { userId } = request.user as JwtPayload
    const { phone_number, tag_type, description } = parse.data

    const result = await submitReport(userId, phone_number, tag_type as TagType, description ?? null)

    if ('error' in result) {
      const statusMap: Record<string, number> = {
        daily_limit_exceeded: 429,
        duplicate_report: 409,
      }
      return reply.status(statusMap[result.error] ?? 400).send({ error: result.error })
    }

    return reply.status(201).send(result)
  })

  // GET /api/v1/reports/my?limit=20&before=<created_at cursor>
  fastify.get<{ Querystring: { limit?: string; before?: string } }>(
    '/reports/my',
    { preHandler: [requireAuth] },
    async (request, reply) => {
      const { userId } = request.user as JwtPayload
      const limit = Math.min(parseInt(request.query.limit ?? '20', 10), 100)
      const before = request.query.before

      const { rows } = await (await import('../db/pool')).pool.query(
        `SELECT id, phone_number, tag_type, description, status, created_at
         FROM spam_reports
         WHERE reporter_id = $1
           ${before ? 'AND created_at < $3' : ''}
         ORDER BY created_at DESC
         LIMIT $2`,
        before ? [userId, limit, before] : [userId, limit],
      )

      const nextCursor = rows.length === limit ? rows[rows.length - 1].created_at : null
      return reply.send({ items: rows, next_cursor: nextCursor })
    },
  )

  // POST /api/v1/reports/:id/reject  (admin use in prototype — no role guard yet)
  fastify.post<{ Params: { id: string } }>(
    '/reports/:id/reject',
    { preHandler: [requireAuth] },
    async (request, reply) => {
      await penalizeReporter(request.params.id)
      return reply.send({ success: true })
    },
  )
}
