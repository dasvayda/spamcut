import Redis from 'ioredis'

let redis: Redis | null = null
let connectionFailed = false

function getRedis(): Redis | null {
  if (!process.env.REDIS_URL) return null
  if (connectionFailed) return null

  if (!redis) {
    redis = new Redis(process.env.REDIS_URL, {
      lazyConnect: true,
      maxRetriesPerRequest: 1,
      enableOfflineQueue: false,
      connectTimeout: 3000,
    })

    redis.on('error', (err) => {
      // Redis 연결 실패 시 graceful degradation — PG 직접 조회로 폴백
      if (!connectionFailed) {
        console.warn('[Redis] 연결 실패, 직접 DB 조회로 폴백:', err.message)
        connectionFailed = true
        redis = null
      }
    })

    redis.on('connect', () => {
      connectionFailed = false
      console.info('[Redis] 연결 성공')
    })
  }

  return redis
}

export async function redisGet(key: string): Promise<string | null> {
  try {
    return (await getRedis()?.get(key)) ?? null
  } catch {
    return null
  }
}

export async function redisSet(key: string, value: string, ttlSeconds: number): Promise<void> {
  try {
    await getRedis()?.set(key, value, 'EX', ttlSeconds)
  } catch {
    // silent fail — Redis 없어도 기능 동작
  }
}

export async function redisDel(key: string): Promise<void> {
  try {
    await getRedis()?.del(key)
  } catch {
    // silent fail
  }
}

export function isRedisAvailable(): boolean {
  return !!process.env.REDIS_URL && !connectionFailed
}
