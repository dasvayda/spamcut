import { pool } from '../db/pool'
import { v4 as uuidv4 } from 'uuid'
import { TOKEN_BURN_SUBSCRIPTION_30D } from '../types'
import { getTokenBalance } from './spamController'

export async function activateSubscription(userId: string) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')

    const balance = await getTokenBalance(userId)
    if (balance < TOKEN_BURN_SUBSCRIPTION_30D) {
      await client.query('ROLLBACK')
      return { error: 'insufficient_balance', balance, required: TOKEN_BURN_SUBSCRIPTION_30D }
    }

    // Burn tokens
    await client.query(
      `INSERT INTO token_ledger (id, user_id, transaction_type, amount)
       VALUES ($1, $2, 'BURN', $3)`,
      [uuidv4(), userId, TOKEN_BURN_SUBSCRIPTION_30D],
    )

    // Upsert subscription with 30-day extension stacked on existing expiry
    await client.query(
      `INSERT INTO user_subscriptions (user_id, is_active, expires_at)
       VALUES ($1, TRUE, NOW() + INTERVAL '30 days')
       ON CONFLICT (user_id) DO UPDATE
         SET is_active = TRUE,
             expires_at = GREATEST(user_subscriptions.expires_at, NOW()) + INTERVAL '30 days'`,
      [userId],
    )

    const { rows } = await client.query(
      `SELECT expires_at FROM user_subscriptions WHERE user_id = $1`,
      [userId],
    )

    await client.query('COMMIT')
    return { success: true, expires_at: rows[0].expires_at }
  } catch (err) {
    await client.query('ROLLBACK')
    throw err
  } finally {
    client.release()
  }
}

export async function transferTokens(
  senderId: string,
  targetPhoneNumber: string,
  amount: number,
) {
  if (amount <= 0) return { error: 'invalid_amount' }

  const client = await pool.connect()
  try {
    await client.query('BEGIN')

    // Resolve recipient
    const { rows: recipientRows } = await client.query(
      `SELECT id FROM users WHERE phone_number = $1`,
      [targetPhoneNumber],
    )
    if (recipientRows.length === 0) {
      await client.query('ROLLBACK')
      return { error: 'recipient_not_found' }
    }

    const recipientId = recipientRows[0].id
    if (recipientId === senderId) {
      await client.query('ROLLBACK')
      return { error: 'cannot_transfer_to_self' }
    }

    const balance = await getTokenBalance(senderId)
    if (balance < amount) {
      await client.query('ROLLBACK')
      return { error: 'insufficient_balance', balance, required: amount }
    }

    const txId = uuidv4()
    await client.query(
      `INSERT INTO token_ledger (id, user_id, transaction_type, amount, counterparty_id)
       VALUES ($1, $2, 'TRANSFER_OUT', $3, $4)`,
      [txId, senderId, amount, recipientId],
    )
    await client.query(
      `INSERT INTO token_ledger (id, user_id, transaction_type, amount, counterparty_id)
       VALUES ($1, $2, 'TRANSFER_IN', $3, $4)`,
      [uuidv4(), recipientId, amount, senderId],
    )

    await client.query('COMMIT')
    return { success: true, transferred: amount, to: targetPhoneNumber }
  } catch (err) {
    await client.query('ROLLBACK')
    throw err
  } finally {
    client.release()
  }
}

export async function getWalletHistory(
  userId: string,
  limit: number,
  before?: string,
) {
  const params: unknown[] = [userId, limit]
  let cursorClause = ''

  if (before) {
    params.push(before)
    cursorClause = `AND tl.created_at < (SELECT created_at FROM token_ledger WHERE id = $3 AND user_id = $1)`
  }

  const { rows } = await pool.query(
    `SELECT tl.id, tl.transaction_type, tl.amount, tl.created_at,
            u.phone_number AS counterparty_phone
     FROM token_ledger tl
     LEFT JOIN users u ON u.id = tl.counterparty_id
     WHERE tl.user_id = $1
       ${cursorClause}
     ORDER BY tl.created_at DESC
     LIMIT $2`,
    params,
  )

  const nextCursor = rows.length === limit ? rows[rows.length - 1].id : null
  return { items: rows, next_cursor: nextCursor }
}

export async function getWalletInfo(userId: string) {
  const balance = await getTokenBalance(userId)

  const { rows: subRows } = await pool.query(
    `SELECT is_active, expires_at FROM user_subscriptions WHERE user_id = $1`,
    [userId],
  )
  const sub = subRows[0] ?? { is_active: false, expires_at: null }

  // Auto-expire subscription
  const isActive =
    sub.is_active && sub.expires_at && new Date(sub.expires_at) > new Date()

  const { rows: txRows } = await pool.query(
    `SELECT transaction_type, amount, counterparty_id, created_at
     FROM token_ledger
     WHERE user_id = $1
     ORDER BY created_at DESC
     LIMIT 20`,
    [userId],
  )

  return {
    balance,
    subscription: {
      is_active: isActive,
      expires_at: sub.expires_at,
      days_remaining: isActive
        ? Math.ceil((new Date(sub.expires_at).getTime() - Date.now()) / 86400000)
        : 0,
    },
    recent_transactions: txRows,
  }
}
