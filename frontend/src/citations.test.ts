import { describe, expect, it } from 'vitest'
import { citationText } from './citations'
import type { Citation } from './api'

const c = (status: Citation['status'], start = 3, end = 3): Citation => ({
  raw: '[a/B.java:3]',
  path: 'a/B.java',
  startLine: start,
  endLine: end,
  status,
})

describe('citations', () => {
  it('formats single lines and ranges', () => {
    expect(citationText(c('VERIFIED'))).toBe('a/B.java:3')
    expect(citationText(c('VERIFIED', 3, 9))).toBe('a/B.java:3-9')
  })
})
