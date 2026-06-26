const DEFAULT_OUTPUT_TYPE = "image/webp"
const FALLBACK_OUTPUT_TYPE = "image/jpeg"

function isImageFile(file: File): boolean {
  return file.type.startsWith("image/")
}

function targetSize(width: number, height: number, maxDimension: number) {
  const maxSide = Math.max(width, height)
  if (maxSide <= maxDimension) return { width, height }
  const scale = maxDimension / maxSide
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

async function loadWithImageBitmap(file: File): Promise<{ source: ImageBitmap; width: number; height: number; close: () => void }> {
  if (typeof createImageBitmap !== "function") {
    throw new Error("createImageBitmap is not available")
  }
  const bitmap = await createImageBitmap(file)
  return {
    source: bitmap,
    width: bitmap.width,
    height: bitmap.height,
    close: () => bitmap.close(),
  }
}

async function loadWithImageElement(file: File): Promise<{ source: HTMLImageElement; width: number; height: number; close: () => void }> {
  const url = URL.createObjectURL(file)
  const image = new Image()
  image.decoding = "async"
  image.src = url
  await new Promise<void>((resolve, reject) => {
    image.onload = () => resolve()
    image.onerror = () => reject(new Error("图片解码失败"))
  })
  return {
    source: image,
    width: image.naturalWidth || image.width,
    height: image.naturalHeight || image.height,
    close: () => URL.revokeObjectURL(url),
  }
}

async function loadImageSource(file: File) {
  try {
    return await loadWithImageBitmap(file)
  } catch {
    return loadWithImageElement(file)
  }
}

function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), type, quality)
  })
}

async function exportCanvas(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  const webp = await canvasToBlob(canvas, DEFAULT_OUTPUT_TYPE, quality)
  if (webp && webp.type === DEFAULT_OUTPUT_TYPE) return webp
  const jpeg = await canvasToBlob(canvas, FALLBACK_OUTPUT_TYPE, quality)
  if (jpeg) return jpeg
  throw new Error("图片压缩失败，请更换图片或稍后重试")
}

export async function compressImage(file: File, maxDimension = 512, quality = 0.8): Promise<File> {
  if (!isImageFile(file)) return file
  const loaded = await loadImageSource(file)
  try {
    const { width, height } = targetSize(loaded.width, loaded.height, maxDimension)
    const canvas = document.createElement("canvas")
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext("2d")
    if (!ctx) throw new Error("浏览器不支持图片压缩")
    ctx.drawImage(loaded.source, 0, 0, width, height)
    const blob = await exportCanvas(canvas, quality)
    return new File([blob], file.name, {
      type: blob.type || FALLBACK_OUTPUT_TYPE,
      lastModified: file.lastModified,
    })
  } finally {
    loaded.close()
  }
}
