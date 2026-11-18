import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import LeaderboardFields from './LeaderboardFields.vue'
import { saveBoardEntries, sourcedModelIds, type BoardEntry } from './leaderboard-fields'

it('locks the model identity of externally sourced open-model rows only', () => {
  const fixture = JSON.parse(readFileSync(resolve(process.cwd(), '../../contracts/examples/leaderboard-open-models.json'), 'utf8'))
  const entries: BoardEntry[] = fixture.data.entries
  fixture.sources[0].material_type = 'self_test'
  const ids = sourcedModelIds('open-models', entries, fixture.sources)
  expect([...ids]).toEqual(['fictional-b', 'fictional-c'])
  const wrapper = mount(LeaderboardFields, {props: {modelValue: entries, sources: fixture.sources, lockedModels: ids}})
  const rows = wrapper.findAll('fieldset')
  expect(rows[0]!.findAll('input[readonly]')).toHaveLength(0)
  expect(rows[1]!.findAll('input[readonly]').map(input => (input.element as HTMLInputElement).value)).toEqual([entries[1]!.tool_name, entries[1]!.model_version])
  expect(rows[1]!.text()).toContain('重新导入')
  expect(sourcedModelIds('overall', entries, fixture.sources).size).toBe(0)
  fixture.sources[0].material_type = 'dataset'
  expect(sourcedModelIds('open-models', entries, fixture.sources).size).toBe(3)
  wrapper.unmount()
})

it('preserves complete hardware and reference fields when saving an imported ranking', () => {
  const fixture = JSON.parse(readFileSync(resolve(process.cwd(), '../../contracts/examples/leaderboard-open-models.json'), 'utf8'))
  const entries: BoardEntry[] = fixture.data.entries
  const edited = structuredClone(entries)
  edited[1]!.reason = '更新说明'
  edited[1]!.product_url = ' '
  const saved = saveBoardEntries(edited)
  expect(saved[1]).toEqual({ ...entries[1], reason: '更新说明' })
  expect(saved[2]).toEqual(entries[2])
  expect(saved.map(entry => entry.rank)).toEqual([1, 2, 2])
  expect(saved[0]).not.toHaveProperty('hardware')
  expect(saveBoardEntries(entries, true).every(entry => entry.score === null)).toBe(true)
})
