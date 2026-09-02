-- SpamCut Database Schema (Prototype)
-- Run this once against your PostgreSQL database

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- phone_number 가 NULL 이면 익명 사용자다.
-- 익명도 users 행을 갖게 해서 reputation·일일 한도·가중치 로직을 그대로 재사용하고,
-- 나중에 번호를 등록하면 이 행에 phone_number 를 채우는 것만으로 신고 이력이 귀속된다.
-- PostgreSQL 의 UNIQUE 는 NULL 을 여러 개 허용하므로 제약은 그대로 둔다.
CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  phone_number VARCHAR(20) UNIQUE, -- E.164 format, e.g. +821012345678 / NULL = 익명
  reputation_score INT NOT NULL DEFAULT 100,
  is_admin BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS spam_reports (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  -- NULL 허용: GDPR 계정 삭제 시 신고는 남기고 신고자만 지운다 (익명화)
  reporter_id UUID REFERENCES users(id),
  phone_number VARCHAR(20) NOT NULL,
  tag_type VARCHAR(10) NOT NULL CHECK (tag_type IN ('RED', 'YELLOW')),
  description TEXT,
  status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VALIDATED', 'REJECTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- spam_master is the authoritative lookup table; kept small for fast reads
CREATE TABLE IF NOT EXISTS spam_master (
  phone_number VARCHAR(20) PRIMARY KEY,
  tag_type VARCHAR(10) NOT NULL CHECK (tag_type IN ('RED', 'YELLOW')),
  aggregate_score INT NOT NULL DEFAULT 0,
  global_status VARCHAR(10) NOT NULL DEFAULT 'INACTIVE' CHECK (global_status IN ('ACTIVE', 'INACTIVE')),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS token_ledger (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id),
  transaction_type VARCHAR(15) NOT NULL CHECK (transaction_type IN ('EARN', 'BURN', 'TRANSFER_OUT', 'TRANSFER_IN')),
  amount INT NOT NULL CHECK (amount > 0),
  counterparty_id UUID REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_subscriptions (
  user_id UUID PRIMARY KEY REFERENCES users(id),
  is_active BOOLEAN NOT NULL DEFAULT FALSE,
  expires_at TIMESTAMPTZ
);

-- Invitation codes (P2P 초대 장치)
CREATE TABLE IF NOT EXISTS invitations (
  code VARCHAR(12) PRIMARY KEY,
  inviter_id UUID NOT NULL REFERENCES users(id),
  invitee_id UUID REFERENCES users(id),
  used_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- B2B 화이트리스트 — 인증된 기업 번호는 스팸 판정에서 제외
CREATE TABLE IF NOT EXISTS whitelist (
  phone_number VARCHAR(20) PRIMARY KEY,
  company_name VARCHAR(200) NOT NULL,
  added_by UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- FCM 푸시 토큰 저장 (1 유저당 복수 디바이스 지원)
CREATE TABLE IF NOT EXISTS fcm_tokens (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, token)
);

-- ─────────────────────────────────────────────────────────────
-- 기존 DB 마이그레이션
-- CREATE TABLE IF NOT EXISTS 는 이미 있는 테이블을 바꾸지 않으므로,
-- 위 정의 변경분을 ALTER 로 다시 적용한다. 모두 반복 실행해도 안전하다.
-- ─────────────────────────────────────────────────────────────
ALTER TABLE users ALTER COLUMN phone_number DROP NOT NULL;
ALTER TABLE spam_reports ALTER COLUMN reporter_id DROP NOT NULL;

-- Indexes for hot paths
CREATE INDEX IF NOT EXISTS idx_spam_reports_phone ON spam_reports(phone_number);
CREATE INDEX IF NOT EXISTS idx_spam_reports_reporter ON spam_reports(reporter_id, created_at);
CREATE INDEX IF NOT EXISTS idx_token_ledger_user ON token_ledger(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_spam_master_status ON spam_master(global_status);
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_user ON fcm_tokens(user_id);
