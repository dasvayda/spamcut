import { FastifyInstance } from 'fastify'
import { z } from 'zod'
import { pool } from '../db/pool'
import { v4 as uuidv4 } from 'uuid'
import { requireAuth } from '../middleware/auth'
import { JwtPayload } from '../types'

const TOKEN_SIGNUP_GRANT = 5     // 가입 즉시 지급
const TOKEN_INVITER_BONUS = 3    // 초대자 추가 보상

const registerSchema = z.object({
  phone_number: z.string().regex(/^\+[1-9]\d{6,14}$/, 'E.164 format required, e.g. +821012345678'),
  invite_code: z.string().length(12).optional(),
})

export async function authRoutes(fastify: FastifyInstance) {
  fastify.post('/auth/register', async (request, reply) => {
    const result = registerSchema.safeParse(request.body)
    if (!result.success) {
      return reply.status(400).send({ error: result.error.flatten() })
    }

    const { phone_number, invite_code } = result.data
    const client = await pool.connect()

    try {
      await client.query('BEGIN')

      // Upsert user — idempotent so the same number can re-login
      const { rows } = await client.query<{ id: string; is_new: boolean }>(
        `INSERT INTO users (id, phone_number)
         VALUES ($1, $2)
         ON CONFLICT (phone_number) DO UPDATE SET phone_number = EXCLUDED.phone_number
         RETURNING id, (xmax = 0) AS is_new`,
        [uuidv4(), phone_number],
      )

      const userId = rows[0].id
      const isNew = rows[0].is_new

      // Ensure subscription row exists
      await client.query(
        `INSERT INTO user_subscriptions (user_id) VALUES ($1) ON CONFLICT DO NOTHING`,
        [userId],
      )

      if (isNew) {
        // 신규 가입자 token 선지급 — 진입 장벽 제거
        await client.query(
          `INSERT INTO token_ledger (id, user_id, transaction_type, amount)
           VALUES ($1, $2, 'EARN', $3)`,
          [uuidv4(), userId, TOKEN_SIGNUP_GRANT],
        )

        // 초대 코드 처리
        if (invite_code) {
          const { rows: invRows } = await client.query(
            `SELECT id, inviter_id FROM invitations
             WHERE code = $1 AND invitee_id IS NULL`,
            [invite_code],
          )

          if (invRows.length > 0) {
            const { id: invId, inviter_id: inviterId } = invRows[0]

            // 초대 완료 기록
            await client.query(
              `UPDATE invitations SET invitee_id = $1, used_at = NOW() WHERE id = $2`,
              [userId, invId],
            )

            // 초대자 보상
            await client.query(
              `INSERT INTO token_ledger (id, user_id, transaction_type, amount, counterparty_id)
               VALUES ($1, $2, 'EARN', $3, $4)`,
              [uuidv4(), inviterId, TOKEN_INVITER_BONUS, userId],
            )
          }
        }
      }

      await client.query('COMMIT')

      const token = fastify.jwt.sign({ userId, phoneNumber: phone_number })
      return reply.send({ token, userId, is_new: isNew })
    } catch (err) {
      await client.query('ROLLBACK')
      throw err
    } finally {
      client.release()
    }
  })

  // POST /api/v1/auth/fcm-token — FCM 푸시 토큰 등록
  fastify.post(
    '/auth/fcm-token',
    { preHandler: [requireAuth] },
    async (request, reply) => {
      const schema = z.object({ token: z.string().min(10) })
      const parse = schema.safeParse(request.body)
      if (!parse.success) return reply.status(400).send({ error: parse.error.flatten() })

      const { userId } = request.user as JwtPayload
      await pool.query(
        `INSERT INTO fcm_tokens (id, user_id, token, updated_at)
         VALUES ($1, $2, $3, NOW())
         ON CONFLICT (user_id, token) DO UPDATE SET updated_at = NOW()`,
        [uuidv4(), userId, parse.data.token],
      )
      return reply.send({ success: true })
    },
  )

  // DELETE /api/v1/users/me — GDPR 계정 삭제 (신고 데이터 익명화)
  fastify.delete(
    '/users/me',
    { preHandler: [requireAuth] },
    async (request, reply) => {
      const { userId } = request.user as JwtPayload
      const client = await pool.connect()
      try {
        await client.query('BEGIN')
        // 신고 데이터는 공익 목적으로 유지하되 reporter_id 익명화
        await client.query(
          `UPDATE spam_reports SET reporter_id = NULL WHERE reporter_id = $1`,
          [userId],
        )
        await client.query(`DELETE FROM fcm_tokens WHERE user_id = $1`, [userId])
        await client.query(`DELETE FROM token_ledger WHERE user_id = $1`, [userId])
        await client.query(`DELETE FROM user_subscriptions WHERE user_id = $1`, [userId])
        await client.query(`DELETE FROM invitations WHERE inviter_id = $1`, [userId])
        await client.query(`DELETE FROM users WHERE id = $1`, [userId])
        await client.query('COMMIT')
        return reply.send({ success: true })
      } catch (err) {
        await client.query('ROLLBACK')
        throw err
      } finally {
        client.release()
      }
    },
  )

  fastify.get(
    '/auth/me',
    {
      preHandler: [
        async (req, rep) => {
          try { await req.jwtVerify() } catch { rep.status(401).send({ error: 'Unauthorized' }) }
        },
      ],
    },
    async (request, reply) => {
      const { userId } = request.user as { userId: string }
      const { rows } = await pool.query(
        `SELECT u.id, u.phone_number, u.reputation_score, u.is_admin, u.created_at,
                COALESCE(SUM(CASE WHEN tl.transaction_type IN ('EARN','TRANSFER_IN') THEN tl.amount ELSE 0 END), 0)
                - COALESCE(SUM(CASE WHEN tl.transaction_type IN ('BURN','TRANSFER_OUT') THEN tl.amount ELSE 0 END), 0)
                AS balance
         FROM users u
         LEFT JOIN token_ledger tl ON tl.user_id = u.id
         WHERE u.id = $1
         GROUP BY u.id`,
        [userId],
      )
      if (!rows[0]) return reply.status(404).send({ error: 'User not found' })
      return reply.send(rows[0])
    },
  )
}
