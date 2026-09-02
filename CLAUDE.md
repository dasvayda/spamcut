# SpamCut — CLAUDE.md

> Claude Code 가 이 프로젝트에서 작업할 때 따르는 **역할 정의·작업 규칙·코딩 컨벤션** 문서.  
> 모든 구현 전에 이 파일을 먼저 읽고 규칙을 따른다.

---

## 1. 프로젝트 역할 (Role)

| 항목 | 내용 |
|------|------|
| **서비스명** | SpamCut |
| **목적** | 군중 기반(Crowd-sourced) SMS 스팸·마케팅 문자 필터링 앱 |
| **플랫폼** | Android (Kotlin) + Node.js 백엔드 (TypeScript) |
| **배포** | Railway (백엔드 + PostgreSQL) |
| **패키지명** | `com.spamcut.app` (Android) / 도메인 `spamcut.com` |
| **현재 단계** | Stage 3 Beta 완료. 다음은 Stage 3.5 Launch Readiness — `to-do-list.md` 참고 |

### 핵심 설계 원칙 (변경 금지)
- **광고 없음** — 앱 내 광고 없이 운영. 수익은 SEO 웹(AdSense) + B2B 화이트리스트
- **Token = 내부 포인트** — 블록체인 아님. Stage 5 이전까지 DB 레저로만 운영
- **P2P 송금 = 초대 장치** — 친구에게 Token을 보내는 것이 곧 서비스 초대
- **OTP 없음** — 전화번호만으로 즉시 가입. 어뷰징은 reputation score + 일일 한도로 방어

---

## 2. 문서 구조와 읽기 순서

### 2.1 문서 계층

```
CLAUDE.md          (지금 이 파일 — 역할·규칙)
    ↓
ARCHITECTURE.md    (시스템 지도·컴포넌트 개요·문서 인덱스)
    ↓
docs/              (주제별 상세 문서 — 필요할 때만 읽음)
    ↓
코드 작성
```

> **원칙:** 상세 구현은 `docs/` 하위 문서에 둔다. `ARCHITECTURE.md`는 지도 역할만 한다.  
> AI가 모든 문서를 컨텍스트에 올리지 않도록, **주제별로 해당 문서만 참조**한다.

### 2.2 주요 문서 인덱스

| 문서 | 경로 | 읽는 시점 |
|------|------|-----------|
| 시스템 지도 | `ARCHITECTURE.md` | 구조·컴포넌트 파악 시 |
| 로드맵·할 일 | `to-do-list.md` | 다음 작업 확인 시 |
| 백엔드 API | `docs/backend-api.md` | API 추가·수정 시 |
| DB 스키마 | `docs/database.md` | 스키마 변경 시 |
| Android 구조 | `docs/android.md` | Android 코드 작업 시 |
| Token 경제 | `docs/token-economy.md` | Token 로직 변경 시 |
| 배포 가이드 | `docs/deployment.md` | Railway 배포 시 |

> 위 `docs/` 문서들은 구현 진행에 따라 생성한다. 존재하지 않는 문서는 작성 전 Confirm.

---

## 3. Confirm 규칙 (반드시 사용자 확인 후 진행)

다음 작업은 **실행 전에 사용자 확인**을 받는다:

- 새 `.md` 문서 생성
- DB 스키마 변경 (`schema.sql` 수정)
- API 인터페이스 변경 (엔드포인트·요청·응답 형식)
- Token 경제 규칙 변경 (지급량·소모량·조건)
- Android `AndroidManifest.xml` 권한 추가
- 핵심 설계 원칙(1절) 에 저촉되는 기능 추가
- 여러 파일에서 참조되는 코드 제거

---

## 4. 작업 흐름 규칙

### 4.1 구현 전 확인
1. `to-do-list.md`에서 현재 단계(Stage) 확인
2. `ARCHITECTURE.md`로 영향 받는 컴포넌트 파악
3. 해당 주제 `docs/` 문서 존재 시 먼저 읽기
4. 10줄 이상 변경 시 구현 계획을 한 문장으로 먼저 말하고 진행

### 4.2 구현 중 규칙
- **기존 파일 우선** — 새 파일 생성보다 기존 파일 수정을 먼저 고려
- **단계 준수** — `to-do-list.md`의 현재 Stage 범위 밖 기능은 임의로 추가하지 않음
- **병렬 도구 사용** — 독립적인 파일 읽기·쓰기는 동시에 실행
- **중간 보고** — 긴 작업은 핵심 발견 사항을 짧게 보고하며 진행

### 4.3 구현 후 정리
- 변경한 API가 있으면 `docs/backend-api.md` 갱신 (없으면 생성 전 Confirm)
- DB 스키마 변경이 있으면 `docs/database.md` 갱신
- `to-do-list.md`의 완료 항목에 `[x]` 표시

---

## 5. 코딩 컨벤션

### 5.1 공통 규칙

| 항목 | 규칙 |
|------|------|
| **주석 언어** | 한국어. WHY 중심으로 작성 |
| **변수·함수명** | 영어, camelCase (TS) / camelCase (Kotlin) |
| **이모지** | 코드·주석에 사용 금지 |
| **TODO** | `// TODO(Stage N):` 형식으로 단계 명시 |

### 5.2 TypeScript (백엔드)

```typescript
// 타입 명시 필수 — any 금지
async function checkSpam(phoneNumber: string): Promise<SpamResult> {
  // E.164 형식만 허용 (+국가코드 포함)
}

// Zod 로 입력 검증 — try/catch 없이 .safeParse() 사용
const result = schema.safeParse(request.body)
if (!result.success) return reply.status(400).send(...)

// DB 트랜잭션은 항상 try/finally 로 client 반환 보장
const client = await pool.connect()
try {
  await client.query('BEGIN')
  // ...
  await client.query('COMMIT')
} catch (err) {
  await client.query('ROLLBACK')
  throw err
} finally {
  client.release()
}
```

### 5.3 Kotlin (Android)

```kotlin
// suspend 함수는 IO Dispatcher에서 실행
withContext(Dispatchers.IO) {
    dao.findByPhoneNumber(number)
}

// BroadcastReceiver에서 코루틴 사용 시 반드시 goAsync() + finally
val pendingResult = goAsync()
CoroutineScope(Dispatchers.IO).launch {
    try { ... } finally { pendingResult.finish() }
}

// null 안전 처리 — !! 연산자 사용 금지
val number = messages.firstOrNull()?.originatingAddress ?: return
```

### 5.4 에러 처리

- SMS 수신 콜백에서는 **절대 예외가 앱을 크래시시키지 않도록** 방어
- 네트워크 오류는 조용히 실패 (Silent fail) — 알림은 UI 레이어에서만
- 백엔드 에러는 구체적인 `error` 코드 키로 반환 (`{ error: 'daily_limit_exceeded' }`)

### 5.5 보안 규칙

- JWT는 Android `EncryptedSharedPreferences` (AES256-GCM)에 저장
- 전화번호는 항상 E.164 형식으로 저장·전송 (`+821012345678`)
- SQL은 **파라미터 바인딩** 필수 — 문자열 연결 금지
- `admin` 엔드포인트는 `is_admin` DB 컬럼으로 서버에서 검증

---

## 6. 환경 변수 관리

새 환경 변수 추가 시 **항상 `.env.example`도 함께 수정**한다.

```bash
# backend/.env.example 형식
DATABASE_URL=postgresql://user:password@localhost:5432/spamguard
JWT_SECRET=replace_with_64_char_random_string
PORT=3000
NODE_ENV=development
```

- 실제 시크릿은 `.env.example`에 절대 포함하지 않음
- 환경 변수 누락 시 서버 시작에서 명확한 에러 메시지로 즉시 실패 (조용한 폴백 금지)
- Railway 배포 환경 변수는 `docs/deployment.md`에 목록 유지

---

## 7. Git 규칙

### 커밋 메시지 형식

```
<type>: <한국어 요약>

<선택: 변경 이유·영향 범위>
```

| type | 용도 |
|------|------|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 코드 개선 |
| `docs` | 문서 수정 |
| `chore` | 빌드·설정 변경 |

### 금지 사항
- `--no-verify` (훅 우회) 금지
- `main` 브랜치 직접 force push 금지
- 확인 없이 `.env` 파일 커밋 금지

---

## 8. Railway 배포 참고

- 백엔드: `backend/` 디렉터리 루트에서 `npm run start`
- DB 마이그레이션: `npm run db:migrate` (배포 후 수동 실행)
- Redis는 Stage 3 이후 Railway 플러그인으로 추가 예정
- 상세: `docs/deployment.md` (생성 예정)

---

*최초 작성: 2026-06-13 | Stage 2 Alpha 기준*
