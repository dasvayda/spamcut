# SpamGuard — Post-Prototype To-Do List

## 서비스 핵심 철학

- **광고 없음** — 수익은 웹 SEO(AdSense) + 미래 B2B 화이트리스트로만 충당
- **Token은 참여 인센티브** — 신고 활성화 + 초대(Invitation) 장치. 블록체인 아닌 서비스 내부 포인트
- **진입 장벽 최소화** — OTP 없이 전화번호만으로 즉시 사용 가능. 어뷰징은 reputation + 일일 한도로 방어
- **P2P 송금 = 초대 메커니즘** — 친구에게 token을 보내는 행위 자체가 서비스 초대

## Roadmap Overview

| Stage | Name | 목표 | 상태 |
|-------|------|------|------|
| 1 | **Prototype** | 백엔드 API + Android SMS 감지 + 오버레이 + 지갑 UI | ✅ 완료 |
| 2 | **Alpha** | 초대 시스템 + 어뷰징 방어 + Android 내부 테스트 가능 | ✅ 완료 |
| 2.5 | **Web Client** | Vanilla JS SPA — 앱 없이 웹에서 완결된 서비스 (조회·신고·지갑·초대) | ✅ 완료 |
| 3 | **Beta** | AI 검증 + FCM 푸시 + Play Store 출시 (Android) | 🔜 다음 |
| 4 | **Production** | iOS + SEO 웹(Next.js) + B2B API + 글로벌 인프라 | — |
| 5 | **Blockchain** | 사용자 기반 충분 시 — 온체인 토큰 전환 + DEX 스왑 | — |

> **앱 출시 타임라인:** 웹 → Stage 2.5(완료), Android → Stage 3(Play Store), iOS → Stage 4, SEO 웹 → Stage 4

---

## Stage 2 — Alpha ✅

### 완료된 항목

**Backend**
- [x] **신규 가입 5 token 선지급** — `auth.ts`에서 `is_new` 판별 후 자동 EARN 기록
- [x] **초대 코드 시스템** — `invitations` 테이블 + `POST /api/v1/invite/generate` + `GET /api/v1/invite/my`
  - 초대 코드로 가입 시 피초대자 +5 token (기존 가입 지급), 초대자 +3 token 보너스
  - 딥링크: `spamguard://invite?code=XXXX`
- [x] **Admin 엔드포인트** — `users.is_admin` 컬럼 + `/api/v1/admin/*` 라우트
  - 신고 거절, 번호 비활성화, 관리자 권한 부여, 신고 목록, 서비스 통계
- [x] **E.164 정규화 미들웨어** — 공백/대시 자동 제거, 모든 라우트에 전역 적용
- [x] **Rate limiting** — `@fastify/rate-limit` 적용 (IP당 200 req/min 전역)
- [x] **페이지네이션** — `GET /api/v1/reports/my?limit=20&before=<cursor>` cursor 방식

**Android**
- [x] **JWT 암호화 저장** — `SessionManager` → `EncryptedSharedPreferences` (AES256-GCM)
- [x] **부팅 시 구독 동기화** — `BootReceiver`에서 `/api/v1/wallet` 호출 → Room DB 갱신
- [x] **SMS 감지 알림** — `SmsReceiver`에서 "신고하기" 액션 버튼 포함 알림 표시
- [x] **초대 딥링크 처리** — `OnboardingActivity` intent-filter + `invite_code` API 전달
- [x] **UI 한국어화** — 주요 UI 텍스트 한국어로 통일

---

## Stage 2.5 — Web Client ✅

### 완료된 항목

- [x] **`@fastify/static` 등록** — `backend/public/` 디렉토리를 Fastify가 정적 서빙
- [x] **`backend/public/index.html`** — 완결된 Vanilla JS SPA (빌드 도구 없음)
  - hash 기반 라우팅: `#` (조회) · `#report` · `#wallet` · `#history` · `#invite` · `#login`
  - **번호 조회** — 로그인 없이 공개, RED/YELLOW/안전 결과 표시
  - **가입·로그인** — 전화번호 입력 → JWT 발급 (localStorage 저장)
  - **스팸 신고** — RED/YELLOW 선택, 설명 입력, 제출
  - **지갑** — Token 잔액, 구독 활성화(−10), P2P 송금
  - **초대** — 초대 코드 생성, Web Share API로 링크 공유
  - **내 신고 이력** — 신고 목록 + 상태 표시 (cursor 페이지네이션)
  - 모바일 퍼스트 반응형 디자인, Android 앱과 동일 색상 체계 (RED `#D32F2F` / YELLOW `#F9A825`)
  - E.164 자동 전환 (+82 국가 코드), `apiFetch()` 헬퍼 (JWT 자동 주입 + 401 리다이렉트)

> **접근:** 백엔드 서버 기동 후 `http://localhost:3000` — 앱 설치 없이 즉시 사용 가능  
> Railway 배포 후 스마트폰 브라우저에서도 동일 URL로 접근

---

## Stage 3 — Beta ✅

### Token 경제 고도화
- [x] **Token 잔액 부족 시 UX** — 구독 활성화 불가 시 "친구 초대하면 token을 받을 수 있어요" 인라인 메시지 + 초대 화면 이동 버튼
- [x] **Token 거래 내역 화면** — `GET /api/v1/wallet/history` (cursor 페이지네이션) + 웹 `#tx-history` 화면 (cursor 더보기)
- [x] **초대 현황 화면** — 발송 수·가입 인원·획득 Token 합계 표시 (`+3 × 가입 수`)

### 검증 품질 향상
- [x] **규칙 기반 AI 검증** — `spamValidator.ts`: 한국어 스팸 키워드 패턴 매칭으로 명백한 스팸 가중치 boosting (최소 weight 3) + 설명 기반 자동 분류
- [x] **Reputation 서서히 회복** — 서버 시작 시 1시간 인터벌 스케줄러: 최근 7일 거절 이력 없는 계정 +1/h (최대 100)

### 접근성 — 최근 수신 내역 기반 신고 (앱 내 완결)
- [x] **수신 번호 로컬 자동 저장** — `recent_contacts` Room 테이블 + `SmsReceiver`/`PhoneStateReceiver`가 구독 여부와 무관하게 발신 번호·문자 미리보기 기록 (30일 보관, `CacheEvictionWorker` 정리)
- [x] **최근 수신 내역 화면** — `RecentActivity`: 번호별 상태(위험/마케팅/안전/확인 필요/신고함) + 수신 요약 목록
- [x] **3개 액션 메뉴** — 데이터 방향이 드러나는 네이밍
  - **신고하기** (내 기기 → 서버) — 번호·문자 내용 자동 입력된 신고 화면, 오프라인이면 큐에 저장 후 자동 재전송
  - **공유하기** (내 기기 → 친구) — 시스템 공유 시트, 판정 결과 + 서비스 링크
  - **최신 정보 받기** (서버 → 내 기기) — 목록 전체 판정 일괄 갱신
- [x] **배치 조회 API** — `POST /api/v1/check-spam/batch` (최대 100건, Redis 캐시 우선)
- [x] **E.164 정규화 유틸** — `PhoneNumbers.kt`: 통신사가 주는 제각각인 번호 형식을 저장 시점에 통일

### 배포 준비 (Android)
- [x] **FCM 푸시 알림** — 백엔드: `fcmService.ts` (firebase-admin, FIREBASE_SERVICE_ACCOUNT 환경변수) + 신고 검증 완료 시 자동 발송 + 구독 만료 D-3 스케줄러
  - Android: `SpamGuardFirebaseService.kt` (FCM 수신·채널 생성·토큰 갱신 서버 업로드)
  - **사용자 작업 필요:** Firebase 콘솔에서 프로젝트 생성 + `google-services.json` → `android/app/` 배치 + `FIREBASE_SERVICE_ACCOUNT` Railway 환경변수 설정
- [x] **캐시 만료 정리** — `CacheEvictionWorker.kt`: WorkManager PeriodicWork (1일, 배터리 절약 제약)
- [x] **Hilt DI 완성** — `@HiltAndroidApp` (SpamGuardApp) + `AppModule.kt` (DB·DAO·API·SessionManager 주입) + HiltWorkerFactory WorkManager 통합
- [x] **오프라인 대응** — `PendingReport` Room 엔티티 + `PendingReportDao` + `PendingReportWorker.kt` (네트워크 복귀 시 자동 재전송, 3회 실패 시 폐기)
- [x] **CallScreeningService** — `CallScreeningService.kt`: `ROLE_CALL_SCREENING` 수신 전화 RED 스팸 자동 거절 (2초 타임아웃, 실패 시 통화 허용)
- [x] **ProGuard/R8 규칙** — `proguard-rules.pro`: Retrofit·Gson·Room·Hilt·Firebase·WorkManager·EncryptedSharedPreferences 보호
- [x] **GDPR 계정 삭제** — `DELETE /api/v1/users/me`: 신고 데이터 익명화(reporter_id = NULL) + 토큰·구독·FCM 토큰·초대 이력 삭제
- [x] **B2B 화이트리스트** — `POST/DELETE/GET /api/v1/admin/whitelist`: 인증 기업 번호 스팸 제외 + `checkSpam()` 우선 조회
- [ ] **Play Store 제출** — 스크린샷, feature graphic, 개인정보처리방침 URL *(사용자 직접 진행)*

### 인프라
- [x] **Redis 도입** — `redisClient.ts`: `ioredis` + `spam:{e164}` 키 24h TTL + graceful degradation (Redis 없으면 PG 직접 조회)
- [x] **Docker Compose** — `docker-compose.yml`: postgres 15 + redis 7 + backend (healthcheck 포함)
- [ ] **Message Queue (BullMQ)** — 실제 부하 발생 시 도입 (Redis 인프라 준비 완료)

---

## Stage 4 — Production

### 플랫폼 확장
- [ ] **iOS (Swift / IdentityLookup)** — `ILMessageFilterExtension` SMS 필터링; 동일 백엔드 공유
- [ ] **SEO 웹 프론트엔드** — Next.js 정적 사이트; 번호별 조회 페이지 Google 인덱싱
  - 웹은 AdSense 허용 — 앱은 광고 없음 유지
- [ ] **CDN + 엣지 캐싱** — Upstash 또는 Cloudflare KV 글로벌 저지연

### 인프라 / 운영
- [ ] **CI/CD** — GitHub Actions: lint → test → build APK → Railway 배포
- [ ] **모니터링** — Sentry(에러) + Grafana + Prometheus
- [ ] **부하 테스트** — `/check-spam` 1,000 req/s 기준 검증
- [ ] **DB 백업** — 일일 PG 덤프 S3

---

## Stage 5 — Blockchain (사용자 기반 충분 시)

> **진입 조건:** DAU 또는 누적 사용자 수가 의미 있는 규모에 도달한 시점에 결정

- [ ] **온체인 토큰 설계** — ERC-20 또는 레이어2(Polygon, Base 등) 기반 $STK 스마트 컨트랙트
- [ ] **DB 레저 → 온체인 마이그레이션** — 기존 잔액 스냅샷 후 지갑 연결 시 클레임 방식
- [ ] **지갑 연결** — MetaMask / WalletConnect 연동
- [ ] **DEX 스왑** — Uniswap 또는 파트너 DEX에 $STK 유동성 풀 개설
- [ ] **토큰 거버넌스 (선택)** — $STK 보유량 기반 스팸 분류 정책 투표

---

## 알림(Alert) 설계 메모

### SMS 알림
| 방식 | 구현 | 비고 |
|------|------|------|
| **사전** — 수신 즉시 (`SmsReceiver` priority 999 → OverlayService + 알림) | ✅ 완료 | 구독자만 작동 |
| 사후 — 미구현 | — | 사전이 커버하므로 불필요 |

### 전화 알림
| 방식 | 구현 | 비고 |
|------|------|------|
| **사전 경고** — 전화 울리는 중 (`PhoneStateReceiver` RINGING → OverlayService 30초 자동닫기 + 알림) | ✅ 완료 | RED/YELLOW 모두 |
| **사전 차단** — RED 자동 거절 (`CallScreeningService`) | ✅ 완료 | 사용자가 기본 스팸 차단 앱으로 설정 필요 |
| 사후 — 미구현 | — | 사전이 커버하므로 현 단계에서 불필요 |

### 역할 분리 원칙
- `PhoneStateReceiver`: **시각적 경고** 담당 (오버레이 + 알림, RED/YELLOW)
- `CallScreeningService`: **자동 차단** 담당 (거절 결정, RED만)
- `SmsReceiver`: **SMS 감지 + 경고** 담당
- `OverlayService`: `EXTRA_AUTO_DISMISS_MS` 인자로 자동 닫기 제어
  - SMS: 0(수동 닫기), 전화: 30초 자동 닫기

### 주요 Android 제약
- `PhoneStateReceiver` + `EXTRA_INCOMING_NUMBER`: Android 9 이전에는 누구나 읽을 수 있었으나,
  Android 9부터 `READ_CALL_LOG` 또는 `READ_PHONE_STATE` 권한 필요 (이미 보유)
- `CallScreeningService`: 사용자가 설정에서 직접 SpamGuard를 선택해야 활성화됨
- 오버레이(`TYPE_APPLICATION_OVERLAY`): `SYSTEM_ALERT_WINDOW` 권한 + 사용자 허용 필요

---

## 설계 메모

- **Token 부트스트래핑** — 가입 시 5 token 선지급으로 해결. 초대 완료 시 초대자 +3 추가
- **Aggregate score 조정** — 현재 공식 `reputation / 20` → 가중치 1–5, 임계값 50. 실데이터 수집 후 A/B 튜닝 필요
- **Token은 포인트, 블록체인은 나중에** — Stage 5 전까지 서비스 내부 포인트로만 운영
- **Android 14+ 오버레이** — `TYPE_APPLICATION_OVERLAY` 동작 변경; API 34 기기 잠금화면 해제 후 동작 확인 필요
- **Railway Redis** — Stage 3 도입 시 Railway 대시보드에서 Redis 플러그인 추가 (클릭 몇 번)
