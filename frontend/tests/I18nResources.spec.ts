import { describe, expect, it } from 'vitest'
import { messages } from '../src/i18n/messages'
import { supportedLocaleCodes } from '../src/i18n/locales'

describe('translation resources', () => {
  it('contains the same keys for every supported locale', () => {
    const expected = Object.keys(messages['zh-CN']).sort()
    expect(Object.keys(messages).sort()).toEqual([...supportedLocaleCodes].sort())
    for (const locale of supportedLocaleCodes) {
      expect(Object.keys(messages[locale]).sort()).toEqual(expected)
      expect(Object.values(messages[locale]).every(Boolean)).toBe(true)
    }
  })
})
