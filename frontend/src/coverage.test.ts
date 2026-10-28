import { describe, expect, it } from 'vitest'
import { coverageDetail, coverageSummary } from './coverage'
import type { Omitted } from './api'

const omitted = (o: Partial<Omitted>): Omitted => ({
  byExtension: {},
  tooLarge: 0,
  tooLargeSamples: [],
  ignoredFiles: 0,
  ignoredDirs: 0,
  ignoredSamples: [],
  ...o,
})

describe('coverageSummary', () => {
  it('is empty when nothing was left out', () => {
    expect(coverageSummary({})).toBe('')
    expect(coverageSummary({ omitted: omitted({}), filesBinary: 0 })).toBe('')
  })

  it('lists omitted extensions with counts, then large, binary and ignored files', () => {
    expect(
      coverageSummary({
        omitted: omitted({
          byExtension: { png: 40, pdf: 3, '.env': 1, '(sin extension)': 2 },
          tooLarge: 2,
          ignoredFiles: 1,
          ignoredDirs: 3,
        }),
        filesBinary: 1,
      }),
    ).toBe(
      'sin indexar por extensión: 40 .png, 3 .pdf, 1 .env, 2 (sin extension) · 2 de más de 1 MB · 1 binario · ' +
        '1 fichero y 3 carpetas por .gitignore/.localisignore',
    )
  })

  it('collapses the tail beyond six extensions into a total', () => {
    const many = { a: 9, b: 8, c: 7, d: 6, e: 5, f: 4, g: 3, h: 2 }
    expect(coverageSummary({ omitted: omitted({ byExtension: many }) })).toBe(
      'sin indexar por extensión: 9 .a, 8 .b, 7 .c, 6 .d, 5 .e, 4 .f y 5 más',
    )
  })
})

describe('coverageDetail', () => {
  it('lists sample paths per reason', () => {
    expect(coverageDetail({})).toBe('')
    expect(
      coverageDetail({ omitted: omitted({ tooLargeSamples: ['big.sql'], ignoredSamples: ['.env', 'build/'] }) }),
    ).toBe('De más de 1 MB: big.sql\nExcluidos por .gitignore/.localisignore: .env, build/')
  })
})
