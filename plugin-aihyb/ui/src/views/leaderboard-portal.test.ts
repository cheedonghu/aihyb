import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { JSDOM } from 'jsdom'
import { afterEach, describe, expect, it } from 'vitest'

const script = readFileSync(resolve(process.cwd(), '../src/main/resources/theme-assets/portal.js'), 'utf8')
const opened: JSDOM[] = []
const markup = `<section data-ranking-catalog>
  <div data-ranking-controls hidden>
    <input data-ranking-search><select data-ranking-sort><option value="rank">默认排名</option><option value="name">模型名称</option></select>
    <button data-ranking-reset>重置</button>
  </div>
  <a data-ranking-tab href="#ranking-a">能力榜甲</a>
  <a data-ranking-tab href="#ranking-b">能力榜乙</a>
  <section data-ranking-board id="ranking-a">
    <p data-ranking-count></p><table><tbody>
      <tr data-ranking-row data-name="Zeta" data-order="0"><td>1</td></tr>
      <tr data-ranking-row data-name="Alpha" data-order="1"><td>2</td></tr>
    </tbody></table><p data-ranking-empty hidden>无匹配</p>
  </section>
  <section data-ranking-board id="ranking-b">
    <p data-ranking-count></p><table><tbody>
      <tr data-ranking-row data-name="Beta" data-order="0"><td>3</td></tr>
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

describe('simplified leaderboard interactions', () => {
  it('initializes and switches bookmarked boards without source controls', () => {
    const dom = open('#ranking-b')
    expect(element<HTMLElement>(dom, '[data-ranking-controls]').hidden).toBe(false)
    expect(dom.window.document.querySelector('[data-ranking-source]')).toBeNull()
    expect(element<HTMLElement>(dom, '#ranking-a').hidden).toBe(true)
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(false)
    element<HTMLAnchorElement>(dom, '[href="#ranking-a"]').click()
    expect(element<HTMLElement>(dom, '#ranking-a').hidden).toBe(false)
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(true)
    expect(dom.window.location.hash).toBe('#ranking-a')
    expect(element(dom, '#ranking-a tbody').children.length).toBe(2)
    expect(element(dom, '#ranking-b tbody').children.length).toBe(1)
  })

  it('searches model names and reports an empty result without renumbering ranks', () => {
    const dom = open()
    change(dom, '[data-ranking-search]', ' ALPHA ', 'input')
    const rows = [...dom.window.document.querySelectorAll<HTMLElement>('#ranking-a [data-ranking-row]')]
    expect(rows.map(row => row.hidden)).toEqual([true, false])
    expect(rows[1]?.textContent).toBe('2')
    expect(element(dom, '#ranking-a [data-ranking-count]').textContent).toBe('显示 1 / 2 个模型')
    change(dom, '[data-ranking-search]', '不存在', 'input')
    expect(element<HTMLElement>(dom, '#ranking-a [data-ranking-empty]').hidden).toBe(false)
  })

  it('restores default order and original ranks when search and sorting are reset', () => {
    const dom = open()
    change(dom, '[data-ranking-sort]', 'name')
    expect(element(dom, '#ranking-a tbody').firstElementChild?.getAttribute('data-name')).toBe('Alpha')
    change(dom, '[data-ranking-search]', 'Alpha', 'input')
    element<HTMLButtonElement>(dom, '[data-ranking-reset]').click()
    expect(element(dom, '#ranking-a tbody').firstElementChild?.getAttribute('data-name')).toBe('Zeta')
    expect(element<HTMLInputElement>(dom, '[data-ranking-search]').value).toBe('')
    expect(element(dom, '#ranking-a [data-ranking-count]').textContent).toBe('显示 2 / 2 个模型')
    expect(element(dom, '#ranking-a tbody').textContent?.replace(/\s/g, '')).toBe('12')
  })

  it('follows browser hash navigation between independent boards', () => {
    const dom = open()
    dom.window.location.hash = '#ranking-b'
    dom.window.dispatchEvent(new dom.window.HashChangeEvent('hashchange'))
    expect(element<HTMLElement>(dom, '#ranking-b').hidden).toBe(false)
    expect(element(dom, '[href="#ranking-b"]').getAttribute('aria-current')).toBe('true')
  })
})
