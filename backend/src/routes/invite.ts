import { FastifyInstance } from 'fastify'
import { requireAuth } from '../middleware/auth'
import { pool } from '../db/pool'
import { JwtPayload } from '../types'
// nanoid v3 is CommonJS compatible
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { nanoid } = require('nanoid')

const MAX_ACTIVE_INVITES = 10  // 동시에 사용 가능한 초대 코드 상한

export async function inviteRoutes(fastify: FastifyInstance) {
  // POST /api/v1/invite/generate
  fastify.post('/invite/generate', { preHandler: [requireAuth] }, async (request, reply) => {
    const { userId } = request.user as JwtPayload

    // 미사용 초대 코드 수 확인
    const { rows: activeRows } = await pool.query(
      `SELECT COUNT(*) AS cnt FROM invitations
       WHERE inviter_id = $1 AND invitee_id IS NULL`,
      [userId],
    )
    if (parseInt(activeRows[0].cnt, 10) >= MAX_ACTIVE_INVITES) {
      return reply.status(429).send({ error: 'too_many_active_invites', max: MAX_ACTIVE_INVITES })
    }

    const code = nanoid(12)  // URL-safe 12자 랜덤 코드
    await pool.query(
      `INSERT INTO invitations (code, inviter_id) VALUES ($1, $2)`,
      [code, userId],
    )

    return reply.send({
      code,
      deep_link: `spamguard://invite?code=${code}`,
      // 공유용 웹 fallback (Stage 4 SEO 사이트 준비 후 실제 URL로 교체)
      share_url: `https://spamguard.app/join?code=${code}`,
    })
  })

  // GET /api/v1/invite/my — 내 초대 현황
  fastify.get('/invite/my', { preHandler: [requireAuth] }, async (request, reply) => {
    const { userId } = request.user as JwtPayload

    const { rows } = await pool.query(
      `SELECT i.code, i.used_at,
              u.phone_number AS invitee_phone
       FROM invitations i
       LEFT JOIN users u ON u.id = i.invitee_id
       WHERE i.inviter_id = $1
       ORDER BY i.created_at DESC
       LIMIT 50`,
      [userId],
    )

    const total = rows.length
    const accepted = rows.filter((r: { used_at: string | null }) => r.used_at !== null).length

    return reply.send({ total_sent: total, accepted, invitations: rows })
  })
}
