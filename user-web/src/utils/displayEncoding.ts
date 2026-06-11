const WINDOWS_1252_UNICODE_TO_BYTE: Record<number, number> = {
  0x20ac: 0x80,
  0x201a: 0x82,
  0x0192: 0x83,
  0x201e: 0x84,
  0x2026: 0x85,
  0x2020: 0x86,
  0x2021: 0x87,
  0x02c6: 0x88,
  0x2030: 0x89,
  0x0160: 0x8a,
  0x2039: 0x8b,
  0x0152: 0x8c,
  0x017d: 0x8e,
  0x2018: 0x91,
  0x2019: 0x92,
  0x201c: 0x93,
  0x201d: 0x94,
  0x2022: 0x95,
  0x2013: 0x96,
  0x2014: 0x97,
  0x02dc: 0x98,
  0x2122: 0x99,
  0x0161: 0x9a,
  0x203a: 0x9b,
  0x0153: 0x9c,
  0x017e: 0x9e,
  0x0178: 0x9f,
}

const mojibakeMarkerPattern = /[\u0080-\u009f\u00c0-\u00ff\u0192\u02c6\u02dc\u0152\u0153\u0160\u0161\u0178\u017d\u017e\u2018-\u201e\u2020-\u2026\u2030\u2039\u203a\u2122]/
const hanPattern = /[\u3400-\u9fff\uf900-\ufaff]/

function countMatches(value: string, pattern: RegExp): number {
  let count = 0
  for (const char of value) {
    if (pattern.test(char)) count += 1
  }
  return count
}

function mojibakeScore(value: string): number {
  return countMatches(value, mojibakeMarkerPattern)
}

function hanScore(value: string): number {
  return countMatches(value, hanPattern)
}

function toLikelyOriginalBytes(value: string): Uint8Array | null {
  const bytes: number[] = []
  for (const char of value) {
    const code = char.codePointAt(0)
    if (code === undefined) return null
    if (WINDOWS_1252_UNICODE_TO_BYTE[code] !== undefined) {
      bytes.push(WINDOWS_1252_UNICODE_TO_BYTE[code])
    } else if (code <= 0xff) {
      bytes.push(code)
    } else {
      return null
    }
  }
  return Uint8Array.from(bytes)
}

export function repairMojibakeText(value?: string | null): string {
  const raw = value ?? ""
  if (!raw || !mojibakeMarkerPattern.test(raw)) return raw

  const bytes = toLikelyOriginalBytes(raw)
  if (!bytes) return raw

  let repaired = raw
  try {
    repaired = new TextDecoder("utf-8", { fatal: true }).decode(bytes)
  } catch {
    return raw
  }

  const rawMojibake = mojibakeScore(raw)
  const repairedMojibake = mojibakeScore(repaired)
  const rawHan = hanScore(raw)
  const repairedHan = hanScore(repaired)

  if (repairedHan > rawHan && repairedMojibake < rawMojibake) return repaired
  if (rawMojibake >= 2 && repairedHan >= rawHan + 2) return repaired
  return raw
}
