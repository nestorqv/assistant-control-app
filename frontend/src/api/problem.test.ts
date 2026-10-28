import { describe, expect, test } from 'vitest'
import { apiRequest } from './client'
import { ApiProblem, NetworkError, parseRetryAfter, TimeoutError, toProblem } from './problem'
import { stubFetch } from '../test/appHarness'
import { problemResponse } from '../test/fixtures'

describe('toProblem', () => {
  test('a JSON problem gives the code and the status', async () => {
    const problem = await toProblem(problemResponse(409, 'ALREADY_CLOCKED_IN'))
    expect(problem).toBeInstanceOf(ApiProblem)
    expect(problem.status).toBe(409)
    expect(problem.code).toBe('ALREADY_CLOCKED_IN')
  })

  test('an empty 401 and an empty 403 have no code', async () => {
    expect((await toProblem(new Response(null, { status: 401 }))).code).toBeUndefined()
    expect((await toProblem(new Response('', { status: 403 }))).code).toBeUndefined()
  })

  test('an HTML 502 from a proxy is handled without a parse error', async () => {
    const problem = await toProblem(new Response('<html>Bad gateway</html>', { status: 502, headers: { 'content-type': 'text/html' } }))
    expect(problem.status).toBe(502)
    expect(problem.code).toBeUndefined()
  })

  test('a JSON content type with a broken body keeps the status', async () => {
    const problem = await toProblem(new Response('{oops', { status: 500, headers: { 'content-type': 'application/json' } }))
    expect(problem.status).toBe(500)
    expect(problem.code).toBeUndefined()
  })

  test('Retry-After seconds are parsed', async () => {
    const problem = await toProblem(problemResponse(429, 'TOO_MANY_ATTEMPTS', { 'Retry-After': '300' }))
    expect(problem.retryAfterSeconds).toBe(300)
  })
})

describe('parseRetryAfter', () => {
  test('delta seconds only; an HTTP date or garbage is ignored', () => {
    expect(parseRetryAfter('30')).toBe(30)
    expect(parseRetryAfter(' 5 ')).toBe(5)
    expect(parseRetryAfter('Wed, 21 Oct 2026 07:28:00 GMT')).toBeUndefined()
    expect(parseRetryAfter('-1')).toBeUndefined()
    expect(parseRetryAfter(null)).toBeUndefined()
  })
})

describe('apiRequest', () => {
  test('a TypeError from fetch becomes a NetworkError', async () => {
    stubFetch()
    await expect(apiRequest('/api/time')).rejects.toBeInstanceOf(NetworkError)
  })

  test('a timeout becomes a TimeoutError', async () => {
    stubFetch(() => {
      throw new DOMException('timed out', 'TimeoutError')
    })
    await expect(apiRequest('/api/time')).rejects.toBeInstanceOf(TimeoutError)
  })

  test('204 resolves to undefined and a non-2xx rejects with the problem', async () => {
    stubFetch((url) => (url === '/api/ok' ? new Response(null, { status: 204 }) : problemResponse(404, 'NOT_FOUND')))
    await expect(apiRequest('/api/ok')).resolves.toBeUndefined()
    await expect(apiRequest('/api/nope')).rejects.toMatchObject({ status: 404, code: 'NOT_FOUND' })
  })
})
