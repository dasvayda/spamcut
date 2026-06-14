// Strips whitespace and dashes from phone number fields before they reach controllers.
// Applies to request body and query string; mutates the parsed object in-place.

const PHONE_FIELDS = ['phone_number', 'target_phone_number', 'number']

function normalize(value: unknown): unknown {
  if (typeof value !== 'string') return value
  return value.replace(/[\s\-()]/g, '')
}

export function normalizePhoneFields(obj: Record<string, unknown>) {
  for (const field of PHONE_FIELDS) {
    if (field in obj) obj[field] = normalize(obj[field])
  }
}

import { FastifyRequest, FastifyReply, HookHandlerDoneFunction } from 'fastify'

export function normalizePhoneMiddleware(
  request: FastifyRequest,
  _reply: FastifyReply,
  done: HookHandlerDoneFunction,
) {
  if (request.body && typeof request.body === 'object') {
    normalizePhoneFields(request.body as Record<string, unknown>)
  }
  if (request.query && typeof request.query === 'object') {
    // query string uses 'number' param for check-spam
    normalizePhoneFields(request.query as Record<string, unknown>)
  }
  done()
}
