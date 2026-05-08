/**
 * Normalize tool detail into input fields for dynamic form.
 * Supports multiple backend shapes.
 * @param {Record<string, unknown>} tool
 * @returns {{ toolCode: string, toolName: string, description: string, coverUrl: string, estimatedCreditCost: number|null, categoryName: string, status: string, fields: Array<Record<string, unknown>> }}
 */
export function normalizeToolForForm(tool) {
  const toolCode = String(tool.toolCode ?? tool.code ?? '')
  const toolName = String(tool.toolName ?? tool.name ?? toolCode)
  const description = String(tool.description ?? '')
  const coverUrl = String(tool.coverUrl ?? tool.cover ?? '')
  const categoryName = String(
    tool.categoryName ?? tool.category?.name ?? '',
  )
  const estimatedCreditCost =
    tool.estimatedCreditCost != null
      ? Number(tool.estimatedCreditCost)
      : tool.creditCost != null
        ? Number(tool.creditCost)
        : null

  const status = String(
    tool.status ?? tool.shelfStatus ?? tool.publishStatus ?? 'ACTIVE',
  ).toUpperCase()

  let fields = []
  if (Array.isArray(tool.inputFields)) fields = tool.inputFields
  else if (Array.isArray(tool.fields)) fields = tool.fields
  else if (tool.inputSchema && typeof tool.inputSchema === 'object') {
    const schema = /** @type {Record<string, unknown>} */ (tool.inputSchema)
    if (Array.isArray(schema.fields)) fields = schema.fields
    else if (Array.isArray(schema.properties))
      fields = schema.properties
  } else if (tool.form && Array.isArray(tool.form.fields)) {
    fields = tool.form.fields
  }

  fields = fields.map(normalizeField).filter((f) => f.key && f.type)

  return {
    toolCode,
    toolName,
    description,
    coverUrl,
    estimatedCreditCost: Number.isFinite(estimatedCreditCost)
      ? estimatedCreditCost
      : null,
    categoryName,
    status,
    fields,
  }
}

function normalizeField(raw) {
  const key = String(raw.name ?? raw.key ?? raw.field ?? '')
  const label = String(raw.label ?? raw.title ?? key)
  const type = String(raw.type ?? 'text').toLowerCase()
  const required = Boolean(raw.required ?? raw.isRequired)
  const placeholder = raw.placeholder != null ? String(raw.placeholder) : ''
  let options = []
  if (Array.isArray(raw.options)) {
    options = raw.options.map((o) => {
      if (typeof o === 'string') return { value: o, label: o }
      return {
        value: String(o.value ?? o.id ?? o.key ?? ''),
        label: String(o.label ?? o.name ?? o.value ?? ''),
      }
    })
  }
  const min = raw.min != null ? Number(raw.min) : undefined
  const max = raw.max != null ? Number(raw.max) : undefined
  const step = raw.step != null ? Number(raw.step) : undefined

  return {
    key,
    label,
    type: ['text', 'textarea', 'select', 'number'].includes(type)
      ? type
      : 'text',
    required,
    placeholder,
    options,
    min,
    max,
    step,
  }
}
