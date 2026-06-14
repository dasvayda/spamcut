# SpamGuard — Crowd-Sourced SMS Spam Filter

Prototype implementation. See [to-do-list.md](to-do-list.md) for the full roadmap.

## Project Structure

```
spam-guard/
  backend/          Node.js + TypeScript + Fastify backend
  android/          Android (Kotlin) native app
  to-do-list.md     Staged roadmap & post-prototype tasks
```

## Quick Start — Backend

### Prerequisites
- Node.js 20+
- PostgreSQL 15+

### Setup

```bash
cd backend
cp .env.example .env
# Edit .env with your DATABASE_URL and JWT_SECRET

npm install
npm run db:migrate:dev   # Creates all tables
npm run dev              # Starts on port 3000
```

### API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/auth/register` | — | Register / login by phone number |
| GET | `/api/v1/auth/me` | JWT | Current user info + balance |
| GET | `/api/v1/check-spam?number=+82...` | — | Lookup number (offline-first on Android) |
| POST | `/api/v1/report` | JWT | Submit a spam report |
| GET | `/api/v1/reports/my` | JWT | My report history |
| GET | `/api/v1/wallet` | JWT | Balance + subscription status |
| POST | `/api/v1/wallet/activate` | JWT | Burn 10 $STK → 30-day subscription |
| POST | `/api/v1/wallet/transfer` | JWT | P2P token transfer |
| GET | `/health` | — | Health check |

## Quick Start — Android

1. Open `android/` in Android Studio Iguana or newer.
2. In [app/build.gradle.kts](android/app/build.gradle.kts), set `API_BASE_URL` to your backend URL.
   - Emulator default: `http://10.0.2.2:3000/`
3. Build and run on API 26+ device or emulator.
4. Grant **Draw Over Other Apps** permission when prompted.

## Token Economy (Prototype Rules)

| Action | $STK |
|--------|------|
| Report validated (first mover) | +10 |
| Report validated (others) | +5 |
| Activate 30-day Real-Time Alert | -10 |
| P2P transfer | ±amount (zero fee) |

Validation threshold: aggregate reporter score ≥ 50 → number goes ACTIVE.

## Architecture Notes

**Prototype simplifications vs. final design:**
- No Redis — all lookups hit PostgreSQL directly
- No message queue — report processing is synchronous in the HTTP handler
- No OTP — registration accepts any E.164 number without verification
- No AI validation — pure crowd-sourced threshold logic

All of the above are tracked in [to-do-list.md](to-do-list.md) Stage 2+.
