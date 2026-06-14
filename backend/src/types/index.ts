export type TagType = 'RED' | 'YELLOW'
export type ReportStatus = 'PENDING' | 'VALIDATED' | 'REJECTED'
export type TransactionType = 'EARN' | 'BURN' | 'TRANSFER_OUT' | 'TRANSFER_IN'

export interface User {
  id: string
  phone_number: string
  reputation_score: number
  created_at: Date
}

export interface SpamReport {
  id: string
  reporter_id: string
  phone_number: string
  tag_type: TagType
  description: string | null
  status: ReportStatus
  created_at: Date
}

export interface SpamMaster {
  phone_number: string
  tag_type: TagType
  aggregate_score: number
  global_status: 'ACTIVE' | 'INACTIVE'
  updated_at: Date
}

export interface TokenLedger {
  id: string
  user_id: string
  transaction_type: TransactionType
  amount: number
  counterparty_id: string | null
  created_at: Date
}

export interface UserSubscription {
  user_id: string
  is_active: boolean
  expires_at: Date | null
}

export interface JwtPayload {
  userId: string
  phoneNumber: string
}

// Validation threshold: aggregate score >= this value triggers VALIDATED status
export const VALIDATION_THRESHOLD = 50

// Token rewards
export const TOKEN_REWARD_BASE = 5
export const TOKEN_REWARD_FIRST_MOVER_MULTIPLIER = 2
export const TOKEN_BURN_SUBSCRIPTION_30D = 10

// Reputation
export const REPUTATION_PENALTY_FALSE_POSITIVE = 10
export const DAILY_REPORT_LIMIT = 20
