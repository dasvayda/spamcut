import { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify'
import { z } from 'zod'
import { pool } from '../db/pool'
import { JwtPayload } from '../types'
import { penalizeReporter } from '../controllers/spamController'

async function requireAdmin(request: FastifyRequest, reply: FastifyReply) {
  try {
    await request.jwtVerify()
  } catch {
    return reply.status(401).send({ error: 'Unauthorized' })
  }

  const { userId } = request.user as JwtPayload
  const { rows } = await pool.query(
    `SELECT is_admin FROM users WHERE id = $1`,
    [userId],
  )
  if (!rows[0]?.is_admin) {
    return reply.status(403).send({ error: 'Forbidden' })
  }
}

export async function adminRoutes(fastify: FastifyInstance) {
  // POST /api/v1/admin/reports/:id/reject — 허위 신고 처리 + reputation 감점
  fastify.post<{ Params: { id: string } }>(
    '/admin/reports/:id/reject',
    { preHandler: [requireAdmin] },
    async (request, reply) => {
      await penalizeReporter(request.params.id)
      return reply.send({ success: true })
    },
  )

  // POST /api/v1/admin/spam-master/:number/deactivate — 번호 비활성화
  fastify.post<{ Params: { number: string } }>(
    '/admin/spam-master/:number/deactivate',
    { preHandler: [requireAdmin] },
    async (request, reply) => {
      const { number } = request.params
      const { rowCount } = await pool.query(
        `UPDATE spam_master SET global_status = 'INACTIVE', updated_at = NOW()
         WHERE phone_number = $1`,
        [number],
      )
      if (!rowCount) return reply.status(404).send({ error: 'Number not found' })
      return reply.send({ success: true })
    },
  )

  // POST /api/v1/admin/users/:id/grant-admin — 관리자 권한 부여
  fastify.post<{ Params: { id: string } }>(
    '/admin/users/:id/grant-admin',
    { preHandler: [requireAdmin] },
    async (request, reply) => {
      const { rowCount } = await pool.query(
        `UPDATE users SET is_admin = TRUE WHERE id = $1`,
        [request.params.id],
      )
      if (!rowCount) return reply.status(404).send({ error: 'User not found' })
      return reply.send({ success: true })
    },
  )

  // GET /api/v1/admin/reports?status=PENDING — 신고 목록 조회
  fastify.get<{ Querystring: { status?: string; limit?: string } }>(
    '/admin/reports',
    { preHandler: [requireAdmin] },
    async (request, reply) => {
      const status = request.query.status ?? 'PENDING'
      const limit = Math.min(parseInt(request.query.limit ?? '50', 10), 200)

      const schema = z.enum(['PENDING', 'VALIDATED', 'REJECTED'])
      if (!schema.safeParse(status).success) {
        return reply.status(400).send({ error: 'Invalid status' })
      }

      const { rows } = await pool.query(
        `SELECT r.id, r.phone_number, r.tag_type, r.description, r.status, r.created_at,
                u.phone_number AS reporter_phone, u.reputation_score
         FROM spam_reports r
         JOIN users u ON u.id = r.reporter_id
         WHERE r.status = $1
         ORDER BY r.created_at DESC
         LIMIT $2`,
        [status, limit],
      )
      return reply.send(rows)
    },
  )

  // POST /api/v1/admin/whitelist — B2B 인증 번호 화이트리스트 추가
  fastify.post(
    '/admin/whitelist',
    { preHandler: [requireAdmin] },
    async (request, reply) => {
      const schema = z.object({
        phone_number: z.string().regex(/^\+[1-9]\d{6,14}$/),
        company_name: z.string().min(1).max(200),
      })
      const parse = schema.safeParse(request.body)
      if (!parse.success) return reply.status(400).send({ error: parse.error.flatten() })

      const { userId } = request.user as JwtPayload
      const { phone_number, company_name } = parse.data

      await pool.query(
        `INSERT INTO whitelist (phone_number, company_name, added_by)
         VALUES ($1, $2, $3)
         ON CONFLICT (phone_number) DO UPDATE SET company_name = EXCLUDED.company_name`,
        [phone_number, company_name, userId],
      )
      return reply.send({ success: true })
    },
  )

  // DELETE /api/v1/admin/whitelist/:number — 화이트리스트 제거
  fastify.delete<{ Params: { number: string } }>(
    '/admin/whitelist/:number',
    { preHandler: [requireAdmin] },
    async (request, reply) => {
      const { rowCount } = await pool.query(
        `DELETE FROM whitelist WHERE phone_number = $1`,
        [request.params.number],
      )
      if (!rowCount) return reply.status(404).send({ error: 'Not found' })
      return reply.send({ success: true })
    },
  )

  // GET /api/v1/admin/whitelist — 화이트리스트 목록
  fastify.get('/admin/whitelist', { preHandler: [requireAdmin] }, async (_request, reply) => {
    const { rows } = await pool.query(
      `SELECT w.phone_number, w.company_name, w.created_at, u.phone_number AS added_by_phone
       FROM whitelist w
       JOIN users u ON u.id = w.added_by
       ORDER BY w.created_at DESC`,
    )
    return reply.send(rows)
  })

  // GET /api/v1/admin/stats — 서비스 현황 요약
  fastify.get('/admin/stats', { preHandler: [requireAdmin] }, async (_request, reply) => {
    const { rows } = await pool.query(`
      SELECT
        (SELECT COUNT(*) FROM users) AS total_users,
        (SELECT COUNT(*) FROM spam_master WHERE global_status = 'ACTIVE') AS active_numbers,
        (SELECT COUNT(*) FROM spam_reports WHERE status = 'PENDING') AS pending_reports,
        (SELECT COUNT(*) FROM spam_reports WHERE created_at > NOW() - INTERVAL '24 hours') AS reports_24h,
        (SELECT COALESCE(SUM(amount), 0) FROM token_ledger WHERE transaction_type = 'EARN') AS total_tokens_issued,
        (SELECT COALESCE(SUM(amount), 0) FROM token_ledger WHERE transaction_type = 'BURN') AS total_tokens_burned,
        (SELECT COUNT(*) FROM invitations WHERE invitee_id IS NOT NULL) AS successful_invites
    `)
    return reply.send(rows[0])
  })
}
