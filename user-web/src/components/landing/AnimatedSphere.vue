<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'

const canvasRef = ref<HTMLCanvasElement | null>(null)
let animationId: number | null = null
let resizeHandler: (() => void) | null = null

onMounted(() => {
  const canvas = canvasRef.value
  if (!canvas) return

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const resize = () => {
    canvas.width = canvas.offsetWidth * window.devicePixelRatio
    canvas.height = canvas.offsetHeight * window.devicePixelRatio
    ctx.scale(window.devicePixelRatio, window.devicePixelRatio)
  }
  
  resizeHandler = resize
  resize()
  window.addEventListener('resize', resize)

  let centerX = canvas.offsetWidth / 2
  let centerY = canvas.offsetHeight / 2
  let radius = Math.min(centerX, centerY) * 0.8

  let rotationX = 0
  let rotationY = 0

  const points: { x: number; y: number; z: number; brightness: number; hue: number }[] = []
  const numPoints = 420

  for (let i = 0; i < numPoints; i++) {
    const theta = Math.random() * Math.PI * 2
    const phi = Math.acos(2 * Math.random() - 1)
    const r = radius * (0.7 + Math.random() * 0.3)
    
    points.push({
      x: r * Math.sin(phi) * Math.cos(theta),
      y: r * Math.sin(phi) * Math.sin(theta),
      z: r * Math.cos(phi),
      brightness: 0.3 + Math.random() * 0.7,
      hue: 184 + Math.random() * 30
    })
  }

  const animate = () => {
    ctx.clearRect(0, 0, canvas.offsetWidth, canvas.offsetHeight)

    rotationX += 0.003
    rotationY += 0.005

    const gradient = ctx.createRadialGradient(centerX, centerY, 0, centerX, centerY, radius * 1.5)
    gradient.addColorStop(0, 'rgba(34, 211, 238, 0.16)')
    gradient.addColorStop(0.5, 'rgba(37, 99, 235, 0.08)')
    gradient.addColorStop(1, 'rgba(37, 99, 235, 0)')
    ctx.fillStyle = gradient
    ctx.beginPath()
    ctx.arc(centerX, centerY, radius * 1.5, 0, Math.PI * 2)
    ctx.fill()

    points.forEach((point) => {
      const cosX = Math.cos(rotationX)
      const sinX = Math.sin(rotationX)
      const cosY = Math.cos(rotationY)
      const sinY = Math.sin(rotationY)

      let x = point.x
      let y = point.y * cosX - point.z * sinX
      let z = point.y * sinX + point.z * cosX

      x = x * cosY + z * sinY
      z = -x * sinY + z * cosY

      const scale = 200 / (200 + z)
      const screenX = centerX + x * scale
      const screenY = centerY + y * scale

      const alpha = Math.max(0.1, Math.min(1, (z + radius) / (radius * 2)) * point.brightness)
      const size = Math.max(1.5, scale * (2.5 + point.brightness * 3))

      const lightness = 50 + point.brightness * 30
      const saturation = 70 + point.brightness * 20

      ctx.beginPath()
      ctx.arc(screenX, screenY, size, 0, Math.PI * 2)
      ctx.fillStyle = `hsla(${point.hue}, ${saturation}%, ${lightness}%, ${alpha})`
      ctx.shadowColor = `hsla(${point.hue}, ${saturation}%, ${lightness}%, ${alpha * 0.8})`
      ctx.shadowBlur = size * 2
      ctx.fill()
      ctx.shadowBlur = 0
    })

    const visiblePoints = points.map((point) => {
      const cosX = Math.cos(rotationX)
      const sinX = Math.sin(rotationX)
      const cosY = Math.cos(rotationY)
      const sinY = Math.sin(rotationY)

      let x = point.x
      let y = point.y * cosX - point.z * sinX
      let z = point.y * sinX + point.z * cosX

      x = x * cosY + z * sinY
      z = -x * sinY + z * cosY

      const scale = 200 / (200 + z)
      return {
        x: centerX + x * scale,
        y: centerY + y * scale,
        z,
        original: point
      }
    }).filter(p => p.z > -radius * 0.5)

    const connectionPoints = visiblePoints.filter((_, index) => index % 3 === 0)
    connectionPoints.forEach((p1, i) => {
      connectionPoints.slice(i + 1).forEach((p2) => {
        const dx = p1.x - p2.x
        const dy = p1.y - p2.y
        const distance = Math.sqrt(dx * dx + dy * dy)

        if (distance < 40) {
          const alpha = (1 - distance / 40) * 0.2
          const hue = (p1.original.hue + p2.original.hue) / 2
          ctx.beginPath()
          ctx.moveTo(p1.x, p1.y)
          ctx.lineTo(p2.x, p2.y)
          ctx.strokeStyle = `hsla(${hue}, 80%, 60%, ${alpha})`
          ctx.lineWidth = 0.5
          ctx.stroke()
        }
      })
    })

    if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      animationId = requestAnimationFrame(animate)
    }
  }

  animate()
})

onUnmounted(() => {
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
  if (animationId) cancelAnimationFrame(animationId)
})
</script>

<template>
  <canvas 
    ref="canvasRef" 
    class="w-full h-full"
  />
</template>
