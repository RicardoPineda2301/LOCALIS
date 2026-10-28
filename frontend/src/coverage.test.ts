import { describe, expect, it } from 'vitest'
import { coverageSummary } from './coverage'

describe('coverageSummary', () => {
  it('is empty when nothing was left out', () => {
    expect(coverageSummary({})).toBe('')
    expect(coverageSummary({ omittedByExtension: {}, omittedTooLarge: 0, filesBinary: 0 })).toBe('')
  })

  it('lists omitted extensions with counts, then large and binary files', () => {
    expect(
      coverageSummary({
        omittedByExtension: { png: 40, pdf: 3, '.env': 1, '(sin extension)': 2 },
        omittedTooLarge: 2,
        filesBinary: 1,
      }),
    ).toBe('sin indexar por extensión: 40 .png, 3 .pdf, 1 .env, 2 (sin extension) · 2 de más de 1 MB · 1 binario')
  })

  it('collapses the tail beyond six extensions into a total', () => {
    const many = { a: 9, b: 8, c: 7, d: 6, e: 5, f: 4, g: 3, h: 2 }
    expect(coverageSummary({ omittedByExtension: many })).toBe(
      'sin indexar por extensión: 9 .a, 8 .b, 7 .c, 6 .d, 5 .e, 4 .f y 5 más',
    )
  })
})
