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

function open(hash = '', html = markup) {
  const dom = new JSDOM(html, { url: 'https://aihyb.invalid/aihyb/leaderboard' + hash, runScripts: 'outside-only' })
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

function budgetPanel() {
  const rows = ['', '12000', '8000.5', '16000', '24000'].map((mb, i) => `
    <li data-ranking-row value="${[1, 2, 2, 4, 5][i]}">${mb ? `<span data-hardware-record data-measured-mb="${mb}"></span>` : ''}
      <span data-hardware-record>其他条件或估算</span></li>`)
  return `<section data-ranking-board id="ranking-open">
    <label data-hardware-control hidden>显存预算<select data-hardware-budget>
      ${['', '8000', '12000', '16000', '24000'].map(mb => `<option value="${mb}">${mb}</option>`).join('')}
    </select></label><p data-ranking-count>5 个模型</p><div data-ranking-chart><ol>${rows.join('')}</ol></div>
    <p data-hardware-empty hidden>暂无匹配</p></section>`
}

function budget(dom: JSDOM, mb: string) {
  const select = element<HTMLSelectElement>(dom, '[data-hardware-budget]')
  select.value = mb
  select.dispatchEvent(new dom.window.Event('change'))
  return [...dom.window.document.querySelectorAll('#ranking-open [data-ranking-row]:not([hidden])')].map(row => row.getAttribute('value'))
}

describe('open-model hardware budgets', () => {
  it('works without a tab catalog, preserves ties, handles empty results and restores every record', () => {
    const dom = open('', budgetPanel())
    expect(element(dom, '[data-hardware-control]').hidden).toBe(false)
    expect(budget(dom, '8000')).toEqual([])
    expect(element(dom, '[data-hardware-empty]').hidden).toBe(false)
    expect(element(dom, '[data-ranking-chart]').hidden).toBe(true)
    expect(budget(dom, '12000')).toEqual(['2', '2'])
    expect(element(dom, '[data-ranking-count]').textContent).toBe('符合条件 2 / 5 个模型')
    expect(dom.window.document.querySelector('[data-hardware-record]:not([data-measured-mb]):not([hidden])')).toBeNull()
    expect(budget(dom, '16000')).toEqual(['2', '2', '4'])
    expect(budget(dom, '24000')).toEqual(['2', '2', '4', '5'])
    expect(budget(dom, '')).toEqual(['1', '2', '2', '4', '5'])
    expect(element(dom, '[data-ranking-count]').textContent).toBe('5 个模型')
    expect(dom.window.document.querySelector('[data-hardware-record][hidden]')).toBeNull()
    expect(element(dom, '[data-hardware-empty]').hidden).toBe(true)
    expect(element(dom, '[data-ranking-chart]').hidden).toBe(false)
  })

  it('scopes filtering to its board and keeps the active budget across tab switches', () => {
    const html = markup.replace('<a data-ranking-tab', `<a data-ranking-tab href="#ranking-open">开放权重</a>${budgetPanel()}<a data-ranking-tab`)
    const dom = open('#ranking-open', html)
    budget(dom, '12000')
    element<HTMLAnchorElement>(dom, '[href="#ranking-a"]').click()
    expect(element(dom, '#ranking-a').hidden).toBe(false)
    expect(dom.window.document.querySelectorAll('#ranking-a li:not([hidden])')).toHaveLength(3)
    element<HTMLAnchorElement>(dom, '[href="#ranking-open"]').click()
    expect(element(dom, '#ranking-open').hidden).toBe(false)
    expect(dom.window.document.querySelectorAll('#ranking-open [data-ranking-row]:not([hidden])')).toHaveLength(2)
  })
})

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
