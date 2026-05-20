<template>
  <div class="page-root">
  <!-- 动态光圈 -->
  <div class="orb orb-top-left" :class="{ expand: orbExpand }"></div>
  <div class="orb orb-bottom-right" :class="{ expand: orbExpand }"></div>
  <canvas id="cursor-trail"></canvas>

  <div class="container">
    <!-- 品牌区（开屏动画） -->
    <div class="brand animate-item" :class="{ show: brandVisible }">
      <div class="logo">
        <div class="wave-logo">
          <svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M8 24C8 18 12 14 18 14C24 14 28 18 28 24C28 30 32 34 38 34C44 34 48 30 48 24" stroke="url(#grad)" stroke-width="3.5" stroke-linecap="round" fill="none"/>
            <path d="M40 24C40 18 36 14 30 14C24 14 20 18 20 24C20 30 16 34 10 34C4 34 0 30 0 24" stroke="url(#grad2)" stroke-width="3.5" stroke-linecap="round" fill="none"/>
            <defs>
              <linearGradient id="grad" x1="8" y1="14" x2="48" y2="34" gradientUnits="userSpaceOnUse">
                <stop stop-color="#3a7bb5"/>
                <stop offset="1" stop-color="#6bb5a0"/>
              </linearGradient>
              <linearGradient id="grad2" x1="0" y1="14" x2="40" y2="34" gradientUnits="userSpaceOnUse">
                <stop stop-color="#6bb5a0"/>
                <stop offset="1" stop-color="#8ac4d8"/>
              </linearGradient>
            </defs>
          </svg>
        </div>
        <span class="brand-text">AI Tool Market</span>
      </div>
    </div>

    <!-- 主标题容器（打字机效果） -->
    <div class="hero-title" ref="heroTitleRef" :style="{ visibility: titleVisible ? 'visible' : 'hidden', opacity: titleVisible ? 1 : 0 }">
      <div class="hero-title-line" ref="line1El"></div>
      <div class="hero-title-line" ref="line2El"></div>
    </div>

    <!-- 副标题（开屏动画） -->
    <div class="hero-sub animate-item" :class="{ show: subVisible }">
      统一管理工具、任务、算力与素材，让团队更快完成内容生产和知识沉淀。
    </div>

    <!-- 三个基础按钮（开屏动画） -->
    <div class="button-group animate-item" :class="{ show: buttonsVisible }">
      <button class="btn"><LayoutGrid class="btn-icon" /> 工具市场</button>
      <button class="btn"><Shield class="btn-icon" /> 安全会话</button>
      <button class="btn"><Zap class="btn-icon" /> 即时创作</button>
    </div>

    <!-- 立即使用按钮单独一行居中 -->
    <div class="cta-wrapper">
      <div class="button-glow-ring"></div>
      <div class="button-glow-ring second"></div>
      <div class="button-glow-ring third"></div>
      <button class="btn cta-btn" @click="startExpand"><Rocket class="btn-icon" /> 立即使用</button>
    </div>
  </div>



  <!-- 登录弹窗 -->
  <div v-if="loginModalVisible" class="login-modal" @click.self="loginModalVisible = false">
    <div class="login-container">
      <div class="login-header">
        <h2 class="login-title">{{ loginTitles[currentMode].title }}</h2>
        <p class="login-subtitle">{{ loginTitles[currentMode].subtitle }}</p>
      </div>

      <div class="mode-tabs">
        <button type="button" :class="{ active: currentMode === 'smsLogin' }" @click="switchMode('smsLogin')">
          <svg class="tab-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
          </svg>
          短信登录
        </button>
        <button type="button" :class="{ active: currentMode === 'passwordLogin' }" @click="switchMode('passwordLogin')">
          <svg class="tab-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
            <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
          </svg>
          密码登录
        </button>
        <button type="button" :class="{ active: currentMode === 'register' }" @click="switchMode('register')">
          <svg class="tab-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"></path>
            <circle cx="9" cy="7" r="4"></circle>
            <path d="M22 21v-2a4 4 0 0 0-3-3.87"></path>
            <path d="M16 3.13a4 4 0 0 1 0 7.75"></path>
          </svg>
          注册
        </button>
      </div>

      <form class="login-card" @submit.prevent="handleSubmit">
        <!-- 密码登录表单 -->
        <div v-if="currentMode === 'passwordLogin'">
          <div class="input-group">
            <label class="input-label">账号或手机号</label>
            <input type="text" class="input-field" v-model="account" placeholder="请输入账号或手机号" autocomplete="username" />
          </div>
          <div class="input-group">
            <label class="input-label">登录密码</label>
            <input type="password" class="input-field" v-model="password" placeholder="请输入密码" autocomplete="current-password" />
          </div>
        </div>

        <!-- 短信登录/注册表单 -->
        <div v-else>
          <div class="input-group">
            <label class="input-label">手机号</label>
            <input type="tel" class="input-field" v-model="phone" placeholder="请输入 11 位手机号" autocomplete="tel" />
          </div>
          <div class="input-group" v-if="currentMode === 'register'">
            <label class="input-label">昵称</label>
            <input type="text" class="input-field" v-model="nickname" placeholder="可选" autocomplete="nickname" />
          </div>
          <div class="input-group">
            <label class="input-label">短信验证码</label>
            <div class="code-row">
              <input type="text" class="input-field" v-model="smsCode" placeholder="6 位验证码" maxlength="6" />
              <button type="button" class="code-btn" :disabled="codeSending" @click="handleSendCode">{{ codeBtnText }}</button>
            </div>
          </div>
        </div>

        <p v-if="tipMsg" class="tip-message">{{ tipMsg }}</p>
        <p v-if="errorMsg" class="error-message">{{ errorMsg }}</p>

        <button type="submit" class="login-btn" :disabled="submitting">
          {{ currentMode === 'register' ? '注册并登录' : '登录工作台' }}
        </button>
      </form>

      <p class="footer-link">
        <a href="#" @click.prevent="toolstoreLink">进入 AI 工具市场</a>
      </p>
    </div>
  </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { LayoutGrid, Rocket, Shield, Zap } from 'lucide-vue-next';
import { sendSmsCode } from '@/api';
import { useAuthStore } from '@/store/authStore';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();

// ================= 开屏动画控制 =================
const brandVisible = ref(false);
const subVisible = ref(false);
const buttonsVisible = ref(false);
const titleVisible = ref(false);
const line1El = ref(null);
const line2El = ref(null);
const heroTitleRef = ref(null);

const fullLine1 = '企业级 AI 工具中台';
const fullLine2 = '让每位成员都有专属 AI 助手';

let isPageActive = true;
const entranceTimers = [];

function safeTimeout(fn, delay) {
  const id = window.setTimeout(() => {
    if (!isPageActive) return;
    fn();
  }, delay);
  entranceTimers.push(id);
  return id;
}

function clearEntranceTimers() {
  entranceTimers.forEach((id) => window.clearTimeout(id));
  entranceTimers.length = 0;
}

// 打字机效果
function typeWriter(element, text, speed, callback) {
  if (!element || !isPageActive) {
    callback?.();
    return;
  }
  let i = 0;
  element.innerHTML = '';
  function addChar() {
    if (!isPageActive || !element) return;
    if (i < text.length) {
      const char = text[i];
      const span = document.createElement('span');
      span.textContent = char;
      span.style.display = 'inline-block';
      element.appendChild(span);
      i++;
      safeTimeout(addChar, speed);
    } else {
      callback?.();
    }
  }
  addChar();
}

// 拆分标题为独立字符（用于跳动效果）
function splitTitleToChars() {
  const lines = [line1El.value, line2El.value];
  lines.forEach(line => {
    if (!line) return;
    const text = line.innerText;
    if (!text) return;
    const chars = text.split('');
    line.innerHTML = '';
    chars.forEach(ch => {
      const span = document.createElement('span');
      span.className = 'char';
      if (ch === ' ') {
        span.innerHTML = '&nbsp;';
        span.style.opacity = '0.4';
      } else {
        span.textContent = ch;
      }
      line.appendChild(span);
    });
  });
  bindCharEvents();
}

// 字符跳动事件（防抖）
let debounceMap = new Map();
const DELAY = 50;
function bounceChar(span) {
  span.classList.remove('char-bounce');
  void span.offsetWidth;
  span.classList.add('char-bounce');
  span.addEventListener('animationend', () => {
    span.classList.remove('char-bounce');
  }, { once: true });
}
function onEnter(span) {
  if (debounceMap.has(span)) clearTimeout(debounceMap.get(span));
  const timer = setTimeout(() => {
    bounceChar(span);
    debounceMap.delete(span);
  }, DELAY);
  debounceMap.set(span, timer);
}
function onLeave(span) {
  if (debounceMap.has(span)) {
    clearTimeout(debounceMap.get(span));
    debounceMap.delete(span);
  }
}
function bindCharEvents() {
  document.querySelectorAll('.char').forEach(c => {
    c.removeEventListener('mouseenter', () => onEnter(c));
    c.removeEventListener('mouseleave', () => onLeave(c));
    c.addEventListener('mouseenter', () => onEnter(c));
    c.addEventListener('mouseleave', () => onLeave(c));
  });
}

function startEntranceAnimation() {
  clearEntranceTimers();
  safeTimeout(() => { brandVisible.value = true; }, 100);
  safeTimeout(() => { subVisible.value = true; }, 300);
  safeTimeout(() => { buttonsVisible.value = true; }, 500);
  safeTimeout(async () => {
    titleVisible.value = true;
    await nextTick();
    if (!isPageActive) return;
    typeWriter(line1El.value, fullLine1, 50, () => {
      typeWriter(line2El.value, fullLine2, 50, () => {
        splitTitleToChars();
      });
    });
  }, 800);
}

// ================= 光标跟随残影效果 =================
let trailCanvas = null;
let ctx = null;
let trailWidth = 0, trailHeight = 0;
let particles = [];
let trailAnimationId = null;
function resizeTrailCanvas() {
  if (!trailCanvas) return;
  trailWidth = window.innerWidth;
  trailHeight = window.innerHeight;
  trailCanvas.width = trailWidth;
  trailCanvas.height = trailHeight;
}
function addTrailPoint(x, y) {
  particles.push({ x, y, life: 1.0, size: 12 });
  if (particles.length > 35) particles.shift();
}
function updateTrail() {
  for (let i = 0; i < particles.length; i++) {
    particles[i].life -= 0.035;
    if (particles[i].life <= 0) {
      particles.splice(i, 1);
      i--;
    }
  }
}
function drawTrail() {
  if (!ctx) return;
  ctx.clearRect(0, 0, trailWidth, trailHeight);
  for (let p of particles) {
    const alpha = p.life * 0.5;
    const size = p.size * p.life;
    ctx.beginPath();
    ctx.fillStyle = `rgba(150, 190, 225, ${alpha * 0.6})`;
    ctx.arc(p.x, p.y, size * 0.6, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.fillStyle = `rgba(120, 170, 215, ${alpha * 0.3})`;
    ctx.arc(p.x, p.y, size * 0.9, 0, Math.PI * 2);
    ctx.fill();
  }
}
function animateTrail() {
  updateTrail();
  drawTrail();
  trailAnimationId = requestAnimationFrame(animateTrail);
}
function initTrail() {
  trailCanvas = document.getElementById('cursor-trail');
  if (!trailCanvas) return;
  ctx = trailCanvas.getContext('2d');
  resizeTrailCanvas();
  animateTrail();
  window.addEventListener('mousemove', (e) => addTrailPoint(e.clientX, e.clientY));
  window.addEventListener('resize', () => resizeTrailCanvas());
}

// ================= 光圈扩大 + 登录弹窗 =================
const orbExpand = ref(false);
const loginModalVisible = ref(false);
let expandTimeout = null;

function resetOrbs() {
  const orbTop = document.querySelector('.orb-top-left');
  const orbBottom = document.querySelector('.orb-bottom-right');
  if (orbTop) orbTop.classList.remove('expand');
  if (orbBottom) orbBottom.classList.remove('expand');
}
function startExpand() {
  if (expandTimeout) return;
  orbExpand.value = true;
  expandTimeout = setTimeout(() => {
    loginModalVisible.value = true;
    setTimeout(() => {
      orbExpand.value = false;
      expandTimeout = null;
    }, 300);
  }, 600);
}

// ================= 登录弹窗逻辑 =================
const currentMode = ref('smsLogin');
const loginTitles = {
  smsLogin: { title: '手机号登录', subtitle: '输入短信验证码，安全进入工作台' },
  passwordLogin: { title: '账号密码登录', subtitle: '使用账号或手机号和密码登录' },
  register: { title: '手机号注册', subtitle: '验证手机号后自动创建账号并登录' }
};
const account = ref('');
const password = ref('');
const phone = ref('');
const nickname = ref('');
const smsCode = ref('');
const tipMsg = ref('');
const errorMsg = ref('');
const submitting = ref(false);
const codeSending = ref(false);
const codeCountdown = ref(0);
let countdownTimer = null;

const codeBtnText = computed(() => {
  if (codeCountdown.value > 0) return `${codeCountdown.value}s`;
  return codeSending.value ? '发送中...' : '获取验证码';
});

function resolveRedirectTarget() {
  const redirect = route.query.redirect;
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect;
  }
  return '/agent';
}

async function navigateAfterLogin() {
  loginModalVisible.value = false;
  account.value = '';
  password.value = '';
  phone.value = '';
  smsCode.value = '';
  nickname.value = '';
  await router.replace(resolveRedirectTarget());
}

function clearMessages() {
  tipMsg.value = '';
  errorMsg.value = '';
}
function switchMode(mode) {
  currentMode.value = mode;
  clearMessages();
}
function showTip(msg) {
  tipMsg.value = msg;
  errorMsg.value = '';
}
function showError(msg) {
  errorMsg.value = msg;
  tipMsg.value = '';
}
async function handleSendCode() {
  const phoneNum = phone.value.trim();
  if (!/^1\d{10}$/.test(phoneNum)) {
    showError('请输入正确的 11 位手机号');
    return;
  }
  clearMessages();
  codeSending.value = true;
  try {
    const scene = currentMode.value === 'register' ? 'REGISTER' : 'LOGIN';
    const res = await sendSmsCode({ phone: phoneNum, scene });
    const cooldown = res.cooldownSeconds ?? 60;
    showTip(res.debugCode ? `验证码已发送（调试码：${res.debugCode}）` : '验证码已发送');
    codeCountdown.value = cooldown;
    const updateCountdown = () => {
      if (codeCountdown.value <= 0) {
        if (countdownTimer) clearInterval(countdownTimer);
        codeSending.value = false;
      } else {
        codeCountdown.value--;
      }
    };
    if (countdownTimer) clearInterval(countdownTimer);
    countdownTimer = setInterval(updateCountdown, 1000);
  } catch (e) {
    showError('验证码发送失败');
    codeSending.value = false;
  }
}
async function handleSubmit() {
  if (submitting.value) return;
  clearMessages();
  submitting.value = true;
  try {
    if (currentMode.value === 'passwordLogin') {
      if (!account.value.trim() || !password.value) throw new Error('请输入账号和密码');
      await auth.login({ account: account.value.trim(), password: password.value });
    } else {
      const phoneNum = phone.value.trim();
      if (!/^1\d{10}$/.test(phoneNum)) throw new Error('请输入正确的 11 位手机号');
      if (!/^\d{6}$/.test(smsCode.value)) throw new Error('请输入 6 位短信验证码');
      const body = {
        phone: phoneNum,
        code: smsCode.value,
        nickname: nickname.value.trim() || undefined,
      };
      if (currentMode.value === 'register') {
        await auth.smsRegister(body);
      } else {
        await auth.smsLogin(body);
      }
    }
    await navigateAfterLogin();
  } catch (err) {
    showError(err?.message ?? '登录失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}
function toolstoreLink() {
  loginModalVisible.value = false;
  router.push('/marketplace');
}

// ================= 生命周期 =================
onMounted(() => {
  startEntranceAnimation();
  initTrail();
});
onBeforeUnmount(() => {
  isPageActive = false;
  clearEntranceTimers();
  if (trailAnimationId) cancelAnimationFrame(trailAnimationId);
  if (countdownTimer) clearInterval(countdownTimer);
  if (expandTimeout) clearTimeout(expandTimeout);
});
</script>

<style>
/* 原样式完整复制，不做任何修改，保证视觉效果一致 */
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  user-select: none;
}

body {
  font-family: "Inter", "Microsoft YaHei", "PingFang SC", "Segoe UI", system-ui, sans-serif;
  background: radial-gradient(circle at 20% 30%, #e9f0fc, #f4f8ff);
  overflow: hidden;
  height: 100vh;
  position: relative;
}

#app {
  height: 100%;
}

.page-root {
  min-height: 100vh;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
}

/* ========= 动态光圈 (左上 + 右下) ========= */
.orb {
  position: fixed;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.5;
  pointer-events: none;
  z-index: 0;
  transition: all 0.6s cubic-bezier(0.2, 1.2, 0.4, 1);
  will-change: transform, width, height, top, left, bottom, right;
  animation: breathe 6s ease-in-out infinite alternate;
}
.orb-top-left {
  top: -350px;
  left: -350px;
  width: 900px;
  height: 900px;
  background: radial-gradient(circle, rgba(100, 160, 230, 0.55), rgba(150, 200, 255, 0.3), rgba(200, 220, 255, 0));
  animation-delay: 0s;
}
.orb-bottom-right {
  bottom: -320px;
  right: -320px;
  width: 950px;
  height: 950px;
  background: radial-gradient(circle, rgba(80, 140, 220, 0.5), rgba(120, 180, 240, 0.25), rgba(180, 210, 255, 0));
  animation-delay: -3s;
}
@keyframes breathe {
  0% { transform: scale(0.9); opacity: 0.5; }
  50% { transform: scale(1.15); opacity: 0.85; }
  100% { transform: scale(0.9); opacity: 0.5; }
}
.orb-top-left.expand {
  top: 50% !important;
  left: 50% !important;
  transform: translate(-50%, -50%) scale(1) !important;
  width: 80vw;
  height: 80vw;
  max-width: 600px;
  max-height: 600px;
  animation: none !important;
}
.orb-bottom-right.expand {
  bottom: auto !important;
  right: auto !important;
  top: 50% !important;
  left: 50% !important;
  transform: translate(-50%, -50%) scale(1) !important;
  width: 80vw;
  height: 80vw;
  max-width: 600px;
  max-height: 600px;
  animation: none !important;
}

/* 残影画布层 */
#cursor-trail {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 9999;
}

.container {
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  padding: 2rem 1rem;
  text-align: center;
  position: relative;
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

/* 开屏动画基础样式：初始透明并向下偏移 */
.animate-item {
  opacity: 0;
  transform: translateY(20px);
  transition: opacity 0.6s ease, transform 0.6s ease;
}
.animate-item.show {
  opacity: 1;
  transform: translateY(0);
}

/* 品牌区 */
.brand {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 1rem;
  margin-bottom: 2rem;
  flex-wrap: wrap;
}
.logo {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.wave-logo svg {
  width: 44px;
  height: 44px;
  filter: drop-shadow(0 2px 4px rgba(0,0,0,0.02));
}
.brand-text {
  font-size: 1.6rem;
  font-weight: 700;
  background: linear-gradient(135deg, #1a2a4a, #2c4a6a);
  background-clip: text;
  -webkit-background-clip: text;
  color: transparent;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

/* 主标题容器 - 打字机效果时显示，初始透明 */
.hero-title {
  font-size: clamp(2.2rem, 6vw, 3.8rem);
  font-weight: 500;
  letter-spacing: -0.02em;
  line-height: 1.2;
  margin-bottom: 1rem;
  width: 100%;
  text-align: center;
  transition: opacity 0.35s ease;
  background: linear-gradient(135deg, #1e3a5f 0%, #4a90a4 50%, #7ab8c9 100%);
  background-clip: text;
  -webkit-background-clip: text;
  color: transparent;
  text-shadow: 
    0 1px 0 #163050,
    0 2px 0 #123048,
    0 3px 0 #0e2840,
    0 4px 0 #0a2038,
    0 5px 0 #061830,
    0 6px 10px rgba(30, 58, 95, 0.3),
    0 10px 20px rgba(74, 144, 164, 0.2),
    0 15px 30px rgba(122, 184, 201, 0.15);
}
.hero-title-line {
  display: block;
  white-space: pre-wrap;
}
/* 打字机光标 */
.typewriter-cursor {
  display: inline-block;
  width: 2px;
  height: 1.2em;
  background-color: #13334b;
  margin-left: 2px;
  animation: blink 0.8s step-end infinite;
  vertical-align: middle;
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

/* 副标题、按钮区 */
.hero-sub {
  font-size: 1.1rem;
  color: #4a627a;
  max-width: 600px;
  margin: 1rem auto 0;
  line-height: 1.6;
}
.button-group {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 1.5rem;
  margin-top: 2rem;
}
.btn {
  display: inline-flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.9rem 2rem;
  font-size: 1rem;
  font-weight: 500;
  color: #1f2e3a;
  background: #ffffff;
  border: 1px solid #dce5ef;
  border-radius: 48px;
  cursor: pointer;
  transition: all 0.25s ease;
}
.btn-icon {
  width: 1.2rem;
  height: 1.2rem;
  color: #3a7bb5;
  flex-shrink: 0;
}
.btn:hover {
  border-color: #8bb4d6;
  background: #fbfeff;
  transform: translateY(-2px);
}

/* 立即使用按钮容器 - 单独一行居中 */
.cta-wrapper {
  display: flex;
  justify-content: center;
  margin-top: 1.5rem;
  position: relative;
}
/* 1. 基础样式：改成横向椭圆/线条 */
.button-glow-ring {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  border-radius: 50%;
  border: 2px solid rgba(42, 110, 255, 0.6);
  animation: ringPulse 2s ease-out infinite;
  pointer-events: none;
}
.button-glow-ring.second {
  animation-delay: 0.6s;
}
.button-glow-ring.third {
  animation-delay: 1.2s;
}

/* 2. 修改动画，适配椭圆扩散 */
@keyframes ringPulse {
  0% {
    opacity: 0.8;
    transform: translate(-50%, -50%) scale(0.5);
  }
  100% {
    opacity: 0;
    transform: translate(-50%, -50%) scale(1.8); /* 扩大倍数，适配椭圆效果 */
  }
}
@keyframes ringPulse {
  0% { width: 100%; height: 100%; opacity: 0.8; border-width: 2px; }
  100% { width: 200%; height: 200%; opacity: 0; border-width: 1px; }
}
.cta-btn {
  background: #2a6eff;
  border: none;
  color: white;
  box-shadow: 0 4px 12px rgba(42, 110, 255, 0.3);
}
.cta-btn .btn-icon {
  color: white;
}
.cta-btn:hover {
  background: #1a5ae8;
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(42, 110, 255, 0.4);
}

/* 登录弹窗 */
.login-modal {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(8px);
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-container {
  width: 100%;
  max-width: 28rem;
  margin: 1.5rem;
  background: white;
  border-radius: 0.75rem;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
  overflow: hidden;
}
.login-header {
  padding: 1.5rem;
  text-align: center;
}
.login-title {
  font-size: 1.5rem;
  font-weight: 600;
  color: #1f2e3a;
  margin: 0 0 0.5rem 0;
}
.login-subtitle {
  font-size: 0.875rem;
  color: #6b7a8c;
  margin: 0;
}
.mode-tabs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.25rem;
  padding: 0.25rem;
  background: #f5f7fa;
  border-bottom: 1px solid #e8ecef;
}
.mode-tabs button {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.375rem;
  height: 2.5rem;
  border: none;
  border-radius: 0.375rem;
  background: transparent;
  color: #6b7a8c;
  font-size: 0.8125rem;
  cursor: pointer;
  transition: all 0.2s ease;
}
.mode-tabs button.active {
  background: white;
  color: #1f2e3a;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}
.login-card {
  padding: 1.5rem;
}
.input-group {
  margin-bottom: 1rem;
}
.input-label {
  display: block;
  margin-bottom: 0.5rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: #3a5a7a;
}
.input-field {
  width: 100%;
  height: 2.5rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid #dce5ef;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  box-sizing: border-box;
  transition: border-color 0.2s ease;
}
.input-field:focus {
  outline: none;
  border-color: #4a8cdf;
}
.code-row {
  display: grid;
  grid-template-columns: 1fr 7rem;
  gap: 0.5rem;
}
.code-btn {
  height: 2.5rem;
  padding: 0 1rem;
  border: 1px solid #dce5ef;
  border-radius: 0.375rem;
  background: white;
  color: #3a5a7a;
  font-size: 0.875rem;
  cursor: pointer;
  transition: all 0.2s ease;
}
.code-btn:hover:not(:disabled) {
  background: #f5f7fa;
}
.code-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.tip-message,
.error-message {
  font-size: 0.8rem;
  padding: 0.5rem;
  border-radius: 0.375rem;
  margin: 0.5rem 0;
}
.tip-message {
  color: #4a8cdf;
  background: rgba(74, 140, 223, 0.1);
  border: 1px solid rgba(74, 140, 223, 0.3);
}
.error-message {
  color: #e54d42;
  background: rgba(229, 77, 66, 0.1);
  border: 1px solid rgba(229, 77, 66, 0.35);
}
.login-btn {
  width: 100%;
  height: 2.75rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  border: none;
  border-radius: 0.375rem;
  background: #2a6eff;
  color: white;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.2s ease;
}
.login-btn:hover:not(:disabled) {
  opacity: 0.9;
}
.login-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.footer-link {
  text-align: center;
  padding: 1rem 1.5rem 1.5rem;
  font-size: 0.75rem;
  color: #6b7a8c;
}
.footer-link a {
  color: #4a8cdf;
  text-decoration: none;
}
.footer-link a:hover {
  text-decoration: underline;
}
.spin-icon {
  animation: spin 1s linear infinite;
  width: 1rem;
  height: 1rem;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.tab-icon {
  width: 1rem;
  height: 1rem;
}

/* 字符跳动动画 */
.char {
  display: inline-block;
  cursor: default;
}
.char-bounce {
  animation: bounce 0.7s cubic-bezier(0.2, 1.1, 0.4, 1) forwards;
}
@keyframes bounce {
  0% { transform: translateY(0); filter: drop-shadow(0 2px 3px rgba(0,0,0,0.1)); }
  30% { transform: translateY(-38px); filter: drop-shadow(0 20px 22px rgba(0,0,0,0.5)) drop-shadow(0 0 12px rgba(0,0,0,0.4)); }
  70% { transform: translateY(-4px); filter: drop-shadow(0 8px 10px rgba(0,0,0,0.3)); }
  100% { transform: translateY(0); filter: drop-shadow(0 2px 3px rgba(0,0,0,0.1)); }
}

.scroll-hint {
  position: fixed;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(220,235,250,0.85);
  backdrop-filter: blur(8px);
  padding: 6px 16px;
  border-radius: 40px;
  color: #3a5a7a;
  font-size: 12px;
  font-family: monospace;
  z-index: 100;
  pointer-events: none;
  white-space: nowrap;
}

@media (max-width: 700px) {
  .hero-title { font-size: 1.8rem; }
  .brand-text { font-size: 1.3rem; }
  .orb-top-left, .orb-bottom-right { width: 250px; height: 250px; filter: blur(40px); }
}
</style>