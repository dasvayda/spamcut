import { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify'
import { z } from 'zod'
import { requireAuth } from '../middleware/auth'
import { activateSubscription, transferTokens, getWalletInfo, getWalletHistory } from '../controllers/walletController'
import { JwtPayload } from '../types'

// Token 적립은 익명도 가능하지만 사용은 번호 등록 후부터다.
// 이 잠금이 곧 번호 등록의 유인이 된다 — 잔액 조회는 열어두어 "얼마가 쌓였는지" 보이게 한다.
function requireRegistered(request: FastifyRequest, reply: FastifyReply): boolean {
  const { phoneNumber } = request.user as JwtPayload
  if (phoneNumber == null) {
    reply.status(403).send({ error: 'registration_required' })
    return false
  }
  return true
}

export async function walletRoutes(fastify: FastifyInstance) {
  // GET /api/v1/wallet — 익명도 조회 가능 (적립 현황 확인용)
  fastify.get('/wallet', { preHandler: [requireAuth] }, async (request, reply) => {
    const { userId, phoneNumber } = request.user as JwtPayload
    const info = await getWalletInfo(userId)
    return reply.send({ ...info, is_anonymous: phoneNumber == null })
  })

  // POST /api/v1/wallet/activate  — burn 10 $STK for 30-day subscription
  fastify.post('/wallet/activate', { preHandler: [requireAuth] }, async (request, reply) => {
    if (!requireRegistered(request, reply)) return

    const { userId } = request.user as JwtPayload
    const result = await activateSubscription(userId)

    if ('error' in result) {
      return reply.status(402).send({ error: result.error, balance: result.balance, required: result.required })
    }
    return reply.send(result)
  })

  // GET /api/v1/wallet/history?limit=20&before=<cursor>
  fastify.get<{ Querystring: { limit?: string; before?: string } }>(
    '/wallet/history',
    { preHandler: [requireAuth] },
    async (request, reply) => {
      const { userId } = request.user as JwtPayload
      const limit = Math.min(parseInt(request.query.limit ?? '20', 10), 100)
      const result = await getWalletHistory(userId, limit, request.query.before)
      return reply.send(result)
    },
  )

  // POST /api/v1/wallet/transfer
  fastify.post('/wallet/transfer', { preHandler: [requireAuth] }, async (request, reply) => {
    if (!requireRegistered(request, reply)) return

    const schema = z.object({
      target_phone_number: z.string().regex(/^\+[1-9]\d{6,14}$/),
      amount: z.number().int().min(1),
    })

    const parse = schema.safeParse(request.body)
    if (!parse.success) return reply.status(400).send({ error: parse.error.flatten() })

    const { userId } = request.user as JwtPayload
    const { target_phone_number, amount } = parse.data

    const result = await transferTokens(userId, target_phone_number, amount)

    if ('error' in result) {
      const statusMap: Record<string, number> = {
        recipient_not_found: 404,
        insufficient_balance: 402,
        cannot_transfer_to_self: 400,
        invalid_amount: 400,
      }
      return reply.status(statusMap[result.error] ?? 400).send(result)
    }
    return reply.send(result)
  })
}
