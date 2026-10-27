export interface Quota {
  amount: string | null
  unit: string
  reset_period: string
  condition: string
}
export interface PricingPlan {
  amount: string | null
  free_quota: Quota
  usage_quota?: Quota
  reset_rule?: string
  benefits?: string[]
  limits: string[]
  region: string
  channel: string
  eligibility: string
  tax_note?: string
  status?: string
}
export interface PricingData { plans: PricingPlan[]; task_usage?: unknown[] }
const planFields = ['reset_rule', 'region', 'channel', 'eligibility', 'tax_note', 'status'] as const
const quotaFields = ['unit', 'reset_period', 'condition'] as const

export function pricingFormFields(data: PricingData): Record<string, unknown> {
  const result: Record<string, unknown> = { task_usage_json: JSON.stringify(data.task_usage || [], null, 2) }
  data.plans.forEach((plan, index) => {
    result[`amount_${index}`] = plan.amount ?? ''
    result[`quota_${index}`] = plan.free_quota.amount ?? ''
    result[`usage_amount_${index}`] = plan.usage_quota?.amount ?? ''
    for (const field of planFields) result[`${field}_${index}`] = plan[field] ?? ''
    for (const field of quotaFields) {
      result[`free_${field}_${index}`] = plan.free_quota[field]
      result[`usage_${field}_${index}`] = plan.usage_quota?.[field] ?? ''
    }
    result[`benefits_${index}`] = (plan.benefits || []).join('\n')
    result[`limits_${index}`] = plan.limits.join('\n')
  })
  return result
}

export function applyPricingFormFields(data: PricingData, values: Record<string, unknown>) {
  let observations: unknown
  try { observations = JSON.parse(String(values.task_usage_json ?? JSON.stringify(data.task_usage || []))) }
  catch { throw new Error('同任务使用记录需要填写合法的 JSON 数组。') }
  if (!Array.isArray(observations)) throw new Error('同任务使用记录必须是数组；没有记录时填写 []。')
  // Do not introduce optional fields into a legacy package on an unchanged save.
  if (data.task_usage !== undefined || observations.length) data.task_usage = observations
  data.plans.forEach((plan, index) => {
    const read = (name: string, fallback: unknown = '') => String(values[`${name}_${index}`] ?? fallback).trim()
    plan.amount = read('amount', plan.amount) || null
    plan.free_quota.amount = read('quota', plan.free_quota.amount) || null
    for (const field of planFields) {
      const value = read(field, plan[field])
      if (value || ['region', 'channel', 'eligibility'].includes(field)) plan[field] = value
      else delete plan[field]
    }
    for (const field of quotaFields) plan.free_quota[field] = read(`free_${field}`, plan.free_quota[field])
    const usage: Quota = { amount: read('usage_amount', plan.usage_quota?.amount) || null,
      unit: read('usage_unit', plan.usage_quota?.unit), reset_period: read('usage_reset_period', plan.usage_quota?.reset_period),
      condition: read('usage_condition', plan.usage_quota?.condition) }
    if (usage.amount !== null || usage.unit || usage.reset_period || usage.condition) plan.usage_quota = usage
    else delete plan.usage_quota
    const lines = (name: string, fallback: string[]) => read(name, fallback.join('\n')).split('\n').map(line => line.trim()).filter(Boolean)
    plan.limits = lines('limits', plan.limits)
    const benefits = lines('benefits', plan.benefits || [])
    if (plan.benefits !== undefined || benefits.length) plan.benefits = benefits
  })
}
