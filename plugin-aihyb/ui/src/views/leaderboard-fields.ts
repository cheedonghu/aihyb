export type BoardEntry = {
  entry_id: string
  tool_id: string
  tool_name: string
  model_version: string
  rank: number
  score: string | null
  reason: string
  source_id: string
  product_url?: string
  license?: string
  weights_bytes?: number
  hardware?: {
    device: string
    gpu_count: number
    quantization: string
    runtime: string
    input_tokens: number
    output_tokens: number
    batch_size: number
    memory_mb: number
    evidence: 'vendor' | 'community' | 'estimate'
    source_id: string
  }[]
}

export function sourcedModelIds(task: string, entries: BoardEntry[], sources: {source_id: string; material_type: string}[]) {
  const ids = new Set<string>()
  if (task !== 'open-models') return ids
  const external = new Set(sources.filter(source => source.material_type !== 'self_test').map(source => source.source_id))
  for (const entry of entries) {
    if (external.has(entry.source_id) || entry.hardware?.some(record => external.has(record.source_id))) {
      ids.add(entry.entry_id)
    }
  }
  return ids
}

export function saveBoardEntries(entries: BoardEntry[], overall = false): BoardEntry[] {
  return entries.map(item => {
    const copy = { ...item, rank: Number(item.rank), score: overall ? null : String(item.score ?? '').trim() || null }
    if (!copy.product_url?.trim()) delete copy.product_url
    return copy
  }).sort((a, b) => a.rank - b.rank)
}
