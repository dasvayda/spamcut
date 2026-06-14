import { TagType } from '../types'

// 규칙 기반 스팸 자동 분류기
// 명백한 키워드 패턴으로 VALIDATION_THRESHOLD 없이도 즉시 AUTO_VALIDATED 처리
// LLM 기반 분류는 Stage 4 이후 도입 예정

interface ValidationResult {
  shouldAutoValidate: boolean
  suggestedTag: TagType | null
  reason: string | null
}

// RED 패턴 — 피싱·불법·악성
const RED_PATTERNS = [
  /대출[^을]*(즉시|빠른|무직|무담보|신용)/i,
  /피싱|보이스피싱/i,
  /개인정보.*(입력|제공|요청)/i,
  /(주민등록번호|계좌번호|비밀번호).*(알려|보내|입력)/i,
  /불법.*대출/i,
  /사기/i,
  /환급.*받[아으]/i,
  /당첨.*축하/i,
  /국세청|검찰청|경찰청.*전화/i,
  /가상화폐|코인.*(투자|수익|보장)/i,
  /성인.*(사이트|콘텐츠)/i,
  /도박|카지노/i,
]

// YELLOW 패턴 — 마케팅·광고
const YELLOW_PATTERNS = [
  /\[광고\]/,
  /수신거부|080/,
  /할인.*(쿠폰|혜택)/i,
  /이벤트.*(참여|당첨)/i,
  /포인트.*(지급|적립)/i,
  /(무료|공짜).*(체험|배송)/i,
  /앱.*(다운로드|설치)/i,
  /특가|한정|오늘만/i,
]

export function analyzeMessage(description: string | null): ValidationResult {
  if (!description) return { shouldAutoValidate: false, suggestedTag: null, reason: null }

  const text = description.trim()

  for (const pattern of RED_PATTERNS) {
    if (pattern.test(text)) {
      return {
        shouldAutoValidate: true,
        suggestedTag: 'RED',
        reason: `자동 분류: RED 키워드 감지 (${pattern.source.slice(0, 30)}...)`,
      }
    }
  }

  for (const pattern of YELLOW_PATTERNS) {
    if (pattern.test(text)) {
      return {
        shouldAutoValidate: true,
        suggestedTag: 'YELLOW',
        reason: `자동 분류: YELLOW 키워드 감지 (${pattern.source.slice(0, 30)}...)`,
      }
    }
  }

  return { shouldAutoValidate: false, suggestedTag: null, reason: null }
}

// 짧은 발신번호 (대부분 마케팅) 판별
export function isShortCode(phoneNumber: string): boolean {
  const digits = phoneNumber.replace(/\D/g, '')
  return digits.length <= 6
}
