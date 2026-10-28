import { describe, expect, it } from 'vitest'
import { sourceNumbers } from './sources'

describe('sourceNumbers', () => {
  it('gives one number per file, in order of first appearance, shared by its chunks', () => {
    expect(sourceNumbers([{ path: 'a/A.java' }, { path: 'a/A.java' }, { path: 'b/B.sql' }, { path: 'a/A.java' }]))
      .toEqual([1, 1, 2, 1])
    expect(sourceNumbers([])).toEqual([])
  })
})
