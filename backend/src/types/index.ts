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
  // 익명 세션이면 null — 번호 등록(claim) 전까지 Token 사용과 지갑 기능이 잠긴다
  phoneNumber: string | null
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

// 익명 사용자 — 진입 장벽 없이 신고부터 받되 영향력은 제한한다.
// reputation 30 → 가중치 floor(30/20) = 1. 하루 한도 5건을 모두 써도 5점이라
// 검증 임계값(50)에 한참 못 미치므로, 익명 신고만으로는 어떤 번호도 확정되지 않는다.
export const ANON_REPUTATION_SCORE = 30
export const ANON_DAILY_REPORT_LIMIT = 5
