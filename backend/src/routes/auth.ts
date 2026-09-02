import { FastifyInstance } from 'fastify'
import { z } from 'zod'
import { pool } from '../db/pool'
import { v4 as uuidv4 } from 'uuid'
import { requireAuth } from '../middleware/auth'
import { JwtPayload, ANON_REPUTATION_SCORE } from '../types'

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

  // POST /api/v1/auth/anon — 전화번호 없이 즉시 시작
  //
  // 문자를 공유해 신고하려는 순간에 전화번호를 요구하면 대부분 이탈한다.
  // 익명 사용자도 users 행을 발급받아 신고할 수 있게 하되, reputation 을 낮춰 영향력을 제한한다.
  // 발급된 JWT 는 등록 사용자와 동일하게 동작하므로 신고 한도·중복 방지가 그대로 적용된다.
  fastify.post('/auth/anon', async (request, reply) => {
    const userId = uuidv4()

    await pool.query(
      `INSERT INTO users (id, phone_number, reputation_score) VALUES ($1, NULL, $2)`,
      [userId, ANON_REPUTATION_SCORE],
    )

    const token = fastify.jwt.sign({ userId, phoneNumber: null })
    return reply.status(201).send({ token, userId, is_anonymous: true })
  })

  // POST /api/v1/auth/claim — 익명 세션에 전화번호를 등록해 정식 계정으로 승격
  //
  // 이미 그 번호로 계정이 있으면 익명 신고·Token 을 기존 계정으로 옮기고 익명 행을 지운다.
  // 어느 경로든 그동안의 신고가 사라지지 않는 것이 핵심이다.
  fastify.post('/auth/claim', { preHandler: [requireAuth] }, async (request, reply) => {
    const parse = registerSchema.safeParse(request.body)
    if (!parse.success) return reply.status(400).send({ error: parse.error.flatten() })

    const { userId: anonId } = request.user as JwtPayload
    const { phone_number, invite_code } = parse.data
    const client = await pool.connect()

    try {
      await client.query('BEGIN')

      const { rows: selfRows } = await client.query(
        `SELECT phone_number FROM users WHERE id = $1 FOR UPDATE`,
        [anonId],
      )
      if (selfRows.length === 0) {
        await client.query('ROLLBACK')
        return reply.status(404).send({ error: 'user_not_found' })
      }
      if (selfRows[0].phone_number !== null) {
        await client.query('ROLLBACK')
        return reply.status(409).send({ error: 'already_registered' })
      }

      // 같은 번호의 기존 계정 확인
      const { rows: existing } = await client.query(
        `SELECT id FROM users WHERE phone_number = $1`,
        [phone_number],
      )

      let finalUserId = anonId
      let merged = false

      if (existing.length > 0) {
        // 기존 계정으로 병합 — 익명으로 쌓은 신고와 Token 을 옮긴다
        finalUserId = existing[0].id
        merged = true

        await client.query(
          `UPDATE spam_reports SET reporter_id = $1 WHERE reporter_id = $2`,
          [finalUserId, anonId],
        )
        await client.query(
          `UPDATE token_ledger SET user_id = $1 WHERE user_id = $2`,
          [finalUserId, anonId],
        )
        await client.query(`DELETE FROM user_subscriptions WHERE user_id = $1`, [anonId])
        await client.query(`DELETE FROM users WHERE id = $1`, [anonId])
      } else {
        // 익명 행을 그대로 승격 — 신고 이력이 이미 이 행에 달려 있어 이관이 필요 없다
        await client.query(
          `UPDATE users SET phone_number = $1, reputation_score = GREATEST(reputation_score, 100)
           WHERE id = $2`,
          [phone_number, anonId],
        )

        await client.query(
          `INSERT INTO user_subscriptions (user_id) VALUES ($1) ON CONFLICT DO NOTHING`,
          [finalUserId],
        )

        // 신규 가입 선지급
        await client.query(
          `INSERT INTO token_ledger (id, user_id, transaction_type, amount)
           VALUES ($1, $2, 'EARN', $3)`,
          [uuidv4(), finalUserId, TOKEN_SIGNUP_GRANT],
        )

        if (invite_code) {
          const { rows: invRows } = await client.query(
            `SELECT id, inviter_id FROM invitations WHERE code = $1 AND invitee_id IS NULL`,
            [invite_code],
          )
          if (invRows.length > 0) {
            await client.query(
              `UPDATE invitations SET invitee_id = $1, used_at = NOW() WHERE id = $2`,
              [finalUserId, invRows[0].id],
            )
            await client.query(
              `INSERT INTO token_ledger (id, user_id, transaction_type, amount, counterparty_id)
               VALUES ($1, $2, 'EARN', $3, $4)`,
              [uuidv4(), invRows[0].inviter_id, TOKEN_INVITER_BONUS, finalUserId],
            )
          }
        }
      }

      await client.query('COMMIT')

      const token = fastify.jwt.sign({ userId: finalUserId, phoneNumber: phone_number })
      return reply.send({ token, userId: finalUserId, merged })
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
