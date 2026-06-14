import { FastifyInstance } from 'fastify'
import { z } from 'zod'
import { requireAuth } from '../middleware/auth'
import { activateSubscription, transferTokens, getWalletInfo, getWalletHistory } from '../controllers/walletController'
import { JwtPayload } from '../types'

export async function walletRoutes(fastify: FastifyInstance) {
  // GET /api/v1/wallet
  fastify.get('/wallet', { preHandler: [requireAuth] }, async (request, reply) => {
    const { userId } = request.user as JwtPayload
    const info = await getWalletInfo(userId)
    return reply.send(info)
  })

  // POST /api/v1/wallet/activate  — burn 10 $STK for 30-day subscription
  fastify.post('/wallet/activate', { preHandler: [requireAuth] }, async (request, reply) => {
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
