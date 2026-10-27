import { describe, expect, it } from 'vitest'
import { applyPricingFormFields, pricingFormFields, type PricingData } from './pricing-fields'

const legacy = (): PricingData => ({ plans: [{ amount: '49.00', free_quota: { amount: null, unit: 'tasks', reset_period: 'month', condition: 'Unknown' },
  limits: ['Check the original source'], region: 'CN', channel: 'official-web', eligibility: 'Monthly billing' }] })

describe('pricing editor', () => {
  it('preserves unknown quota and legacy data on an unchanged save', () => {
    const data = legacy(), original = structuredClone(data)
    applyPricingFormFields(data, pricingFormFields(data))
    expect(data).toEqual(original)
  })
  it('saves quotas, reset rules, limits, regions and task observations together', () => {
    const data = legacy(), fields = pricingFormFields(data)
    Object.assign(fields, { usage_amount_0: '100', usage_unit_0: 'percent', usage_reset_period_0: '5 hours', usage_condition_0: 'Current window',
      reset_rule_0: 'Rolling window', region_0: 'US', benefits_0: 'Writing\nResearch', limits_0: 'Independent weekly cap\nFile limit',
      task_usage_json: JSON.stringify([{ consumed_percent: 12, plan_id: 'monthly' }]) })
    applyPricingFormFields(data, fields)
    expect(data.plans[0]?.usage_quota).toEqual({ amount: '100', unit: 'percent', reset_period: '5 hours', condition: 'Current window' })
    expect(data.plans[0]?.reset_rule).toBe('Rolling window')
    expect(data.plans[0]?.region).toBe('US')
    expect(data.plans[0]?.limits).toEqual(['Independent weekly cap', 'File limit'])
    expect(data.task_usage).toEqual([{ consumed_percent: 12, plan_id: 'monthly' }])
  })
  it('rejects invalid observations before mutating plan data', () => {
    const data = legacy(), original = structuredClone(data)
    expect(() => applyPricingFormFields(data, { task_usage_json: '{"wrong":1}', amount_0: '999' })).toThrow()
    expect(data).toEqual(original)
  })
})
