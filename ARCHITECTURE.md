# SpamCut — Architecture

> SpamCut 시스템의 **전체 구조 지도**. 컴포넌트 개요·데이터 흐름·기술 스택·배포 구성을 한눈에 본다.  
> 세부 구현은 아래 §7 문서 인덱스의 **상세 문서**를 참조한다. 이 문서는 지도만 담는다.

---

## 1. 시스템 개요

### 1.1 아키텍처 다이어그램

```
┌──────────────────────────────────────────────────────────────────┐
│                          클라이언트                               │
│  ┌─────────────────────┐  ┌──────────────────┐  ┌─────────────┐ │
│  │  Android 앱 (Kotlin) │  │  웹 SPA (Stage   │  │  SEO 웹     │ │
│  │  - SMS 수신 감지     │  │  2.5, 현재 운영)  │  │ (Stage 4,  │ │
│  │  - 오버레이 경고 UI  │  │  - 번호 조회      │  │  Next.js)  │ │
│  │  - 신고·지갑 UI     │  │  - 신고·지갑·초대 │  │  - AdSense │ │
│  └──────────┬──────────┘  │  Vanilla JS SPA   │  └─────┬───── ┘ │
│             │              │  /public/index.html│        │        │
│             │              └────────┬──────────┘        │        │
└────────────-┼───────────────────────┼───────────────────┼────────┘
              │                       │ 정적 서빙          │ HTTPS
              └───────────────────────┼───────────────────┘
                          HTTPS / REST│
┌─────────────▼────────────────────────────────────────┐
│              Backend API (Railway)                    │
│           Node.js + TypeScript + Fastify             │
│                                                      │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │ /api/v1/    │  │ /api/v1/     │  │ /api/v1/   │  │
│  │ check-spam  │  │ report       │  │ wallet/*   │  │
│  │ (공개)      │  │ (JWT 필요)   │  │ invite/*   │  │
│  └──────┬──────┘  └──────┬───────┘  │ admin/*    │  │
│         │                │           └────────────┘  │
│  ┌──────▼──────────────────────────────────────┐     │
│  │            서비스 계층 (Controllers)          │     │
│  │  spamController  │  walletController         │     │
│  └──────────────────┬──────────────────────────┘     │
└─────────────────────┼────────────────────────────────┘
                      │
┌─────────────────────▼────────────────────────────────┐
│                  데이터 계층                           │
│                                                      │
│  ┌─────────────────────────────────────────────┐    │
│  │         PostgreSQL (Railway)                 │    │
│  │  users · spam_reports · spam_master          │    │
│  │  token_ledger · user_subscriptions           │    │
│  │  invitations                                 │    │
│  └─────────────────────────────────────────────┘    │
│                                                      │
│  ┌─────────────────────────────────────────────┐    │
│  │   Redis (Stage 3 도입 예정, Railway 플러그인) │    │
│  │   spam:{e164} → 24h TTL 캐시                 │    │
│  └─────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────┘
```

### 1.2 기술 스택

| 영역 | 기술 | 비고 |
|------|------|------|
| **Android 앱** | Kotlin, Room DB, Retrofit, WindowManager | minSdk 26 (Android 8+) |
| **웹 클라이언트** | Vanilla HTML/CSS/JS SPA, hash 라우팅 | `backend/public/index.html` — Fastify 정적 서빙 |
| **백엔드** | Node.js 20, TypeScript 5, Fastify 4 | Railway 배포 |
| **데이터베이스** | PostgreSQL 15 | Railway 내장 |
| **캐시** | Redis (Stage 3~) | Railway 플러그인으로 추가 |
| **인증** | JWT (`@fastify/jwt`), EncryptedSharedPreferences | OTP 없음 — 전화번호 즉시 가입 |
| **입력 검증** | Zod (백엔드), E.164 정규화 미들웨어 | |
| **보안** | Rate limiting (`@fastify/rate-limit`) | IP당 200 req/min |

---

## 2. 핵심 도메인 개념

### 2.1 스팸 분류 체계

| 태그 | 의미 | 오버레이 |
|------|------|---------|
| **RED** | 악성 스팸 — 피싱·불법 도박·성인 등 | 빨간 배경 경고 |
| **YELLOW** | 마케팅 문자 — 광고·홍보 | 노란 배경 안내 |

### 2.2 Token 경제 (내부 포인트)

| 이벤트 | Token |
|--------|-------|
| 신규 가입 | +5 |
| 신고 검증 완료 (first mover) | +10 |
| 신고 검증 완료 (참여자) | +5 |
| 초대 완료 (초대자 보너스) | +3 |
| 30일 구독 활성화 | -10 |
| P2P 송금 | ±N (수수료 없음) |

> Token은 **서비스 내부 포인트**. Stage 5 이전까지 블록체인·외부 거래 없음.  
> P2P 송금 = **초대(Invitation) 장치** — 친구에게 Token을 보내는 것이 서비스 소개.

### 2.3 익명 사용자

전화번호 없이도 신고할 수 있다. 익명도 `users` 행을 갖되(`phone_number IS NULL`) 영향력이 제한된다.

| 항목 | 등록 사용자 | 익명 |
|------|------------|------|
| 초기 reputation | 100 | 30 |
| 신고 가중치 (`reputation/20`) | 5 | 1 |
| 일일 신고 한도 | 20건 | 5건 |
| Token | 적립·사용 | **적립만** — 번호 등록 후 사용 |
| 지갑·초대·이력 | 가능 | 번호 등록 필요 |

> 익명 신고는 하루 한도를 다 써도 5점이라 검증 임계값(50)에 도달할 수 없다.
> **익명만으로는 어떤 번호도 스팸으로 확정되지 않는다.**
> 번호 등록 시 `phone_number`를 채우는 것으로 신고 이력이 그대로 귀속된다.

---

## 3. Android 앱 구조

### 3.1 핵심 흐름

```
SMS / 전화 수신
  → SmsReceiver · PhoneStateReceiver
      → [0] recent_contacts 에 발신 번호 기록 (구독 여부 무관 — 항상 로컬 저장)
      → [A] UserSubscription 유효성 확인 (Room DB) — 미구독이면 여기서 종료
      → [B] 로컬 Room DB에서 전화번호 조회
      → [C] 없으면 백엔드 API 조회 (SMS 5초 / 전화 3초 타임아웃)
      → 스팸이면 OverlayService 실행 + 알림 표시
```

### 3.1.1 최근 수신 내역 → 서버 동기화

```
recent_contacts (로컬 전용, 30일 보관)
  → RecentActivity 목록에서 항목 선택
      → [신고하기]      내 기기 → 서버   POST /api/v1/report
      → [공유하기]      내 기기 → 친구   ACTION_SEND (외부 앱)
      → [최신 정보 받기] 서버 → 내 기기  POST /api/v1/check-spam/batch
```

> 수신 번호는 **신고 버튼을 눌러야만** 서버로 올라간다. 그 전까지는 기기 안에만 존재한다.

### 3.2 주요 컴포넌트

| 컴포넌트 | 역할 |
|---------|------|
| `SmsReceiver` | SMS 인터셉트, 최근 수신 내역 기록, 스팸 판별, 오버레이 트리거 |
| `PhoneStateReceiver` | 수신 전화 기록 + 시각적 경고 |
| `RecentActivity` | 최근 수신 내역 목록 + 신고·공유·최신 정보 받기 |
| `PhoneNumbers` | 수신 번호 E.164 정규화 (저장·조회 키 통일) |
| `OverlayService` | `TYPE_APPLICATION_OVERLAY` 경고창 표시 |
| `AppDatabase` (Room) | `spam_numbers`, `user_subscription`, `pending_reports`, `recent_contacts` 로컬 저장 |
| `SessionManager` | JWT 암호화 저장 (EncryptedSharedPreferences) |
| `RetrofitClient` | 백엔드 API 통신 |
| `OnboardingActivity` | 전화번호 가입 + 초대 딥링크 처리 |
| `WalletActivity` | Token 잔액·구독·P2P 송금 UI |
| `ReportActivity` | 스팸 번호 신고 UI |

상세: [`docs/android.md`](docs/android.md) *(구현 진행 시 작성)*

---

## 4. 백엔드 API 구조

### 4.1 엔드포인트 요약

| 메서드 | 경로 | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/v1/auth/anon` | — | 익명 세션 발급 (전화번호 없이 신고 가능) |
| POST | `/api/v1/auth/register` | — | 전화번호 가입·로그인 (JWT 반환) |
| POST | `/api/v1/auth/claim` | JWT | 익명 세션에 번호 등록 → 정식 계정 승격 |
| GET | `/api/v1/auth/me` | JWT | 내 정보 + Token 잔액 |
| GET | `/api/v1/check-spam?number=` | — | 번호 스팸 조회 (Offline-first 폴백) |
| POST | `/api/v1/check-spam/batch` | — | 번호 여러 개 일괄 조회 (최대 100) — 앱 "최신 정보 받기" |
| POST | `/api/v1/report` | JWT | 스팸 신고 제출 |
| GET | `/api/v1/reports/my` | JWT | 내 신고 이력 (cursor 페이지네이션) |
| GET | `/api/v1/wallet` | JWT | 잔액·구독 상태·거래 이력 |
| POST | `/api/v1/wallet/activate` | JWT + 번호 등록 | 30일 구독 활성화 (10 Token 소모) |
| POST | `/api/v1/wallet/transfer` | JWT + 번호 등록 | P2P Token 송금 |
| POST | `/api/v1/invite/generate` | JWT + 번호 등록 | 초대 코드 생성 |
| GET | `/api/v1/invite/my` | JWT | 내 초대 현황 |
| POST | `/api/v1/admin/*` | JWT + is_admin | 관리자 전용 (신고 처리·통계) |
| GET | `/health` | — | 헬스체크 |

상세: [`docs/backend-api.md`](docs/backend-api.md) *(구현 진행 시 작성)*

### 4.2 신고 처리 흐름

```
POST /api/v1/report
  → 일일 한도 확인 (20건/일)
  → 중복 신고 확인 (동일 번호 24h)
  → spam_reports INSERT
  → spam_master aggregate_score 누적
  → 임계값(50) 초과 시:
      spam_master.global_status = 'ACTIVE'
      → 관련 신고 VALIDATED 처리
      → token_ledger EARN (first mover 2배)
```

---

## 5. 데이터베이스 스키마 개요

| 테이블 | 역할 |
|--------|------|
| `users` | 계정 정보, reputation_score, is_admin |
| `spam_reports` | 개별 신고 기록, 상태 (PENDING / VALIDATED / REJECTED) |
| `spam_master` | 번호별 집계 결과, 글로벌 활성 상태 — 조회 핫패스 |
| `token_ledger` | 모든 Token 입출금 기록 (EARN / BURN / TRANSFER_IN / TRANSFER_OUT) |
| `user_subscriptions` | 구독 활성 여부, 만료 시각 |
| `invitations` | 초대 코드, 초대자·피초대자 연결 |

> 스키마 파일: `backend/src/db/schema.sql`  
> 상세 설명: [`docs/database.md`](docs/database.md) *(구현 진행 시 작성)*

---

## 6. 배포 구성

```
Railway Project
├── spamcut-backend (Service)
│   ├── 시작 명령: npm run start
│   ├── 포트: $PORT (Railway 자동 주입)
│   └── 환경변수: DATABASE_URL, JWT_SECRET, NODE_ENV
│
├── PostgreSQL (Database Plugin)
│   └── DATABASE_URL 자동 주입
│
└── Redis (Stage 3 예정)
    └── REDIS_URL 주입 예정
```

> Android 앱 배포: Google Play Store (Stage 3 목표)  
> iOS 배포: App Store (Stage 4 목표)  
> 상세: [`docs/deployment.md`](docs/deployment.md) *(구현 진행 시 작성)*

---

## 7. 개발 로드맵

| Stage | 이름 | 상태 | 핵심 목표 |
|-------|------|------|-----------|
| 1 | Prototype | ✅ 완료 | 백엔드 API + Android 스켈레톤 |
| 2 | Alpha | ✅ 완료 | 초대 시스템 + 어뷰징 방어 + JWT 암호화 |
| 2.5 | Web Client | ✅ 완료 | Vanilla JS SPA — 앱 없이 웹에서 완결된 서비스 |
| 3 | Beta | 🔜 다음 | AI 검증 + FCM + Play Store 출시 |
| 4 | Production | — | iOS + SEO 웹(Next.js) + B2B + 글로벌 인프라 |
| 5 | Blockchain | — | 온체인 Token 전환 + DEX 스왑 (조건부) |

상세 태스크: [`to-do-list.md`](to-do-list.md)

---

## 8. 문서 인덱스

> 각 문서는 **필요할 때만** 열어본다. 모든 내용을 컨텍스트에 올리지 않는다.

| 문서 | 경로 | 읽는 시점 |
|------|------|-----------|
| **작업 규칙·역할** | `CLAUDE.md` | 항상 (자동 로드) |
| **로드맵·할 일** | `to-do-list.md` | 다음 작업 확인 시 |
| **백엔드 API 상세** | `docs/backend-api.md` | API 추가·수정 시 |
| **DB 스키마 상세** | `docs/database.md` | 스키마 변경 시 |
| **Android 구조 상세** | `docs/android.md` | Android 작업 시 |
| **Token 경제 규칙** | `docs/token-economy.md` | Token 로직 변경 시 |
| **배포 가이드** | `docs/deployment.md` | Railway 배포 시 |
| **안티어뷰징 설계** | `docs/anti-abuse.md` | Reputation·한도 조정 시 |

> `docs/` 하위 문서는 해당 주제 작업 시 생성하며, 생성 전 `CLAUDE.md §3 Confirm` 규칙을 따른다.

---

*최초 작성: 2026-06-13 | Stage 2.5 Web Client 기준*
