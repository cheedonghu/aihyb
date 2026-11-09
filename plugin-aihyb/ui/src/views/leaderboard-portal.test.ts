import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { JSDOM } from 'jsdom'
import { afterEach, describe, expect, it } from 'vitest'

const script = readFileSync(resolve(process.cwd(), '../src/main/resources/theme-assets/portal.js'), 'utf8')
const opened: JSDOM[] = []
const markup = `<section data-ranking-catalog>
  <div data-ranking-controls hidden>
    <select data-ranking-source><option value="">全部来源</option><option>来源甲</option><option>来源乙</option></select>
    <input data-ranking-search><select data-ranking-sort><option value="rank">来源名次</option><option value="name">模型名称</option></select>
    <button data-ranking-reset>重置</button>
  </div>
  <a data-ranking-tab data-sources='["来源甲"]' href="#ranking-a">榜单甲</a>
  <a data-ranking-tab data-sources='["来源乙"]' href="#ranking-b">榜单乙</a>
  <section data-ranking-board data-sources='["来源甲"]' id="ranking-a">
    <p data-ranking-count></p><table><tbody>
      <tr data-ranking-row data-name="Zeta high" data-order="0"><td>1</td></tr>
      <tr data-ranking-row data-name="Alpha low" data-order="1"><td>2</td></tr>
    </tbody></table><p data-ranking-empty hidden>无匹配</p>
  </section>
  <section data-ranking-board data-sources='["来源乙"]' id="ranking-b">
    <p data-ranking-count></p><table><tbody>
      <tr data-ranking-row data-name="Beta default" data-order="0"><td>3</td></tr>
    </tbody></table><p data-ranking-empty hidden>无匹配</p>
  </section>
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
function change(dom: JSDOM, selector: string, value: string, event = 'change') {
  element<HTMLInputElement>(dom, selector).value = value
  element(dom, selector).dispatchEvent(new dom.window.Event(event, { bubbles: true }))
}

afterEach(() => opened.splice(0).forEach(dom => dom.window.close()))

describe('published leaderboard interactions', () => {
  it('opens a bookmarked snapshot and switches sources without combining rows', () => {
    const dom = open('#ranking-b')
    expect(element<HTMLElement>(dom, '#ranking-a').hidden).toBe(true)
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(false)
    change(dom, '[data-ranking-source]', '来源甲')
    expect(element<HTMLElement>(dom, '#ranking-a').hidden).toBe(false)
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(true)
    expect(element<HTMLElement>(dom, '[href="#ranking-b"]').hidden).toBe(true)
    expect(dom.window.location.hash).toBe('#ranking-a')
    expect(element(dom, '#ranking-a tbody').children.length).toBe(2)
  })

  it('keeps a snapshot available under either of its independent sources', () => {
    const dom = open()
    for (const selector of ['#ranking-b', '[href="#ranking-b"]']) {
      element<HTMLElement>(dom, selector).dataset.sources = JSON.stringify(['来源甲', '来源乙'])
    }
    change(dom, '[data-ranking-source]', '来源甲')
    expect(element<HTMLElement>(dom, '[href="#ranking-b"]').hidden).toBe(false)
    element<HTMLAnchorElement>(dom, '[href="#ranking-b"]').click()
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(false)
    change(dom, '[data-ranking-source]', '来源乙')
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(false)
  })

  it('searches model versions and reports an empty result without renumbering ranks', () => {
    const dom = open()
    change(dom, '[data-ranking-search]', ' LOW ', 'input')
    const rows = [...dom.window.document.querySelectorAll<HTMLElement>('#ranking-a [data-ranking-row]')]
    expect(rows.map(row => row.hidden)).toEqual([true, false])
    expect(rows[1]?.textContent).toBe('2')
    expect(element(dom, '#ranking-a [data-ranking-count]').textContent).toContain('1 / 2')
    change(dom, '[data-ranking-search]', '不存在', 'input')
    expect(element<HTMLElement>(dom, '#ranking-a [data-ranking-empty]').hidden).toBe(false)
  })

  it('restores source order and original ranks when filters are reset', () => {
    const dom = open()
    change(dom, '[data-ranking-sort]', 'name')
    expect(element(dom, '#ranking-a tbody').firstElementChild?.getAttribute('data-name')).toBe('Alpha low')
    change(dom, '[data-ranking-search]', 'Alpha', 'input')
    element<HTMLButtonElement>(dom, '[data-ranking-reset]').click()
    expect(element(dom, '#ranking-a tbody').firstElementChild?.getAttribute('data-name')).toBe('Zeta high')
    expect(element<HTMLInputElement>(dom, '[data-ranking-search]').value).toBe('')
    expect(element(dom, '#ranking-a [data-ranking-count]').textContent).toContain('2 / 2')
    expect(element(dom, '#ranking-a tbody').textContent?.replace(/\s/g, '')).toBe('12')
  })

  it('allows browser hash navigation to select a different source', () => {
    const dom = open()
    change(dom, '[data-ranking-source]', '来源甲')
    dom.window.location.hash = '#ranking-b'
    dom.window.dispatchEvent(new dom.window.HashChangeEvent('hashchange'))
    expect(element<HTMLSelectElement>(dom, '[data-ranking-source]').value).toBe('')
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(false)
    expect(element(dom, '[href="#ranking-b"]').getAttribute('aria-current')).toBe('true')
  })
})
