// SpamCut 서비스 워커
//
// 목적은 두 가지다.
//  1) 홈 화면 설치(PWA) 조건 충족 — 설치되어야 문자 앱의 "공유" 목록에 SpamCut이 뜬다
//  2) 오프라인에서도 앱 껍데기가 뜨도록 최소 캐시
//
// API 응답은 절대 캐시하지 않는다. JWT가 실린 요청이고, 스팸 판정은 항상 최신이어야 한다.

const CACHE = 'spamcut-shell-v1'
const SHELL = ['/', '/manifest.json', '/icons/icon-192.png', '/icons/icon-512.png']

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url)

  // API·타 오리진·비 GET 요청은 그대로 통과 (캐시 개입 없음)
  if (
    event.request.method !== 'GET' ||
    url.origin !== self.location.origin ||
    url.pathname.startsWith('/api/')
  ) {
    return
  }

  // 네트워크 우선 — 실패했을 때만 캐시로 폴백
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        const copy = response.clone()
        caches.open(CACHE).then((cache) => cache.put(event.request, copy)).catch(() => {})
        return response
      })
      .catch(() => caches.match(event.request).then((hit) => hit || caches.match('/'))),
  )
})
