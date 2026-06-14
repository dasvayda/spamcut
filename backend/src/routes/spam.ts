import { FastifyInstance } from 'fastify'
import { z } from 'zod'
import { requireAuth } from '../middleware/auth'
import { checkSpam, submitReport, penalizeReporter } from '../controllers/spamController'
import { JwtPayload, TagType } from '../types'

const e164 = z.string().regex(/^\+[1-9]\d{6,14}$/, 'Must be E.164 format')

export async function spamRoutes(fastify: FastifyInstance) {
  // GET /api/v1/check-spam?number=+821012345678
  // Public endpoint – no auth required (clients may call without a logged-in user)
  fastify.get<{ Querystring: { number: string } }>('/check-spam', async (request, reply) => {
    const parse = e164.safeParse(request.query.number)
    if (!parse.success) {
      return reply.status(400).send({ error: 'Invalid phone number format. Use E.164, e.g. +821012345678' })
    }
    const result = await checkSpam(parse.data)
    return reply.send(result)
  })

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
