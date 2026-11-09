import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { JSDOM } from 'jsdom'
import { afterEach, describe, expect, it } from 'vitest'

const script = readFileSync(resolve(process.cwd(), '../src/main/resources/theme-assets/portal.js'), 'utf8')
const opened: JSDOM[] = []
const markup = `<section data-ranking-catalog>
  <a data-ranking-tab href="#ranking-a">能力榜甲</a>
  <a data-ranking-tab href="#ranking-b">能力榜乙</a>
  <section data-ranking-board id="ranking-a"><ol class="ranking-chart-list">
    <li data-ranking-row value="1">Zeta 90</li>
    <li data-ranking-row value="1">Alpha 90</li>
    <li data-ranking-row value="3">Gamma 60</li>
  </ol></section>
  <section data-ranking-board id="ranking-b"><ol class="ranking-chart-list">
    <li data-ranking-row value="1">Beta 80</li>
  </ol></section>
</section>`

function open(hash = '') {
  const dom = new JSDOM(markup, { url: 'https://aihyb.invalid/aihyb/leaderboard' + hash, runScripts: 'outside-only' })
  opened.push(dom)
  dom.window.eval(script)
  dom.window.document.dispatchEvent(new dom.window.Event('DOMContentLoaded'))
  return dom
}
function element<T extends Element = HTMLElement>(dom: JSDOM, selector: string): T {
  const value = dom.window.document.querySelector<T>(selector)
  if (!value) throw new Error('Missing test element: ' + selector)
  return value
}

afterEach(() => opened.splice(0).forEach(dom => dom.window.close()))

describe('horizontal ranking chart navigation', () => {
  it('initializes without search or sorting controls and keeps every original tie and row', () => {
    const dom = open()
    expect(element(dom, '[data-ranking-catalog]').getAttribute('data-enhanced')).toBe('true')
    expect(dom.window.document.querySelector('input,select,button')).toBeNull()
    expect(element<HTMLElement>(dom, '#ranking-a').hidden).toBe(false)
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(true)
    expect([...dom.window.document.querySelectorAll('#ranking-a li')].map(row => row.getAttribute('value'))).toEqual(['1', '1', '3'])
    expect([...dom.window.document.querySelectorAll('#ranking-a li')].map(row => row.textContent)).toEqual(['Zeta 90', 'Alpha 90', 'Gamma 60'])
  })

  it('opens a bookmarked chart and switches without filtering models', () => {
    const dom = open('#ranking-b')
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(false)
    element<HTMLAnchorElement>(dom, '[href="#ranking-a"]').click()
    expect(element<HTMLElement>(dom, '#ranking-a').hidden).toBe(false)
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(true)
    expect(dom.window.location.hash).toBe('#ranking-a')
    expect(dom.window.document.querySelectorAll('#ranking-a li')).toHaveLength(3)
    expect(dom.window.document.querySelectorAll('#ranking-b li')).toHaveLength(1)
  })

  it('follows browser hash navigation and ignores unrelated fragments', () => {
    const dom = open()
    dom.window.location.hash = '#ranking-b'
    dom.window.dispatchEvent(new dom.window.HashChangeEvent('hashchange'))
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(false)
    expect(element(dom, '[href="#ranking-b"]').getAttribute('aria-current')).toBe('true')
    dom.window.location.hash = '#unrelated'
    dom.window.dispatchEvent(new dom.window.HashChangeEvent('hashchange'))
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(false)
  })
})
