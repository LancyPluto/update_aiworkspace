<template>
  <div class="page-root">
  <!-- 动态光圈 -->
  <div class="orb orb-top-left" :class="{ expand: orbExpand }"></div>
  <div class="orb orb-bottom-right" :class="{ expand: orbExpand }"></div>
  
  <!-- 鼠标跟随光晕 -->
  <div class="mouse-glow" ref="mouseGlow"></div>
  
  <canvas id="cursor-trail"></canvas>

  <div class="container">
    <!-- 品牌区（开屏动画） -->
    <div class="brand animate-item" :class="{ show: brandVisible }">
      <div class="logo">
        <div class="wave-logo">
          <img src="/logo.svg" alt="logo" "/>
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
    <div class="login-container" :class="{ 'compact-login': currentMode === 'passwordLogin' }">
      <div class="login-header">
        <h2 class="login-title">{{ loginTitle }}</h2>
        <p class="login-subtitle">{{ loginSubtitle }}</p>
      </div>

      <div id="aliyun-captcha-element" class="aliyun-captcha-element"></div>
      <button id="aliyun-captcha-trigger" class="aliyun-captcha-trigger" type="button" aria-hidden="true" tabindex="-1"></button>

      <form class="login-card" @submit.prevent="handleSubmit">
        <div v-if="currentMode === 'smsLogin'" class="sms-form">
          <div class="phone-field input-group">
            <span class="country-code">+86</span>
            <input type="tel" class="input-field" v-model="phone" placeholder="请输入手机号" autocomplete="tel" />
          </div>
          <div class="input-group code-field">
            <div class="code-row pill-field">
              <input type="text" class="input-field" v-model="smsCode" placeholder="请输入验证码" maxlength="6" inputmode="numeric" />
              <button type="button" class="code-btn" :disabled="codeSending" @click="handleSendCode('login')">{{ codeBtnText }}</button>
            </div>
          </div>
          <p class="agreement-text">
            注册登录即代表已阅读并同意我们的
            <a href="#" @click.prevent>隐私政策</a>
            ，未注册的手机号将自动注册
          </p>
        </div>

        <div v-else-if="currentMode === 'passwordLogin'" class="password-form">
          <div class="input-group">
            <input type="text" class="input-field pill-field" v-model="account" placeholder="请输入手机号/账号" autocomplete="username" />
          </div>
          <div class="input-group password-field pill-field">
            <input :type="showPassword ? 'text' : 'password'" class="input-field" v-model="password" placeholder="请输入密码" autocomplete="current-password" />
            <button type="button" class="eye-btn" @click="showPassword = !showPassword" aria-label="切换密码显示"><Eye class="eye-icon" /></button>
          </div>
          <p class="agreement-text password-agreement">
            注册登录即代表已阅读并同意我们的
            <a href="#" @click.prevent>隐私政策</a>
          </p>
          <div class="auth-row-links">
            <a href="#" @click.prevent="switchMode('forgotVerify')">忘记密码</a>
            <a href="#" @click.prevent="switchMode('register')">立即注册</a>
          </div>
        </div>

        <div v-else-if="currentMode === 'register'" class="register-form">
          <div class="phone-field input-group">
            <span class="country-code">+86</span>
            <input type="tel" class="input-field" v-model="registerPhone" placeholder="请输入手机号" autocomplete="tel" />
          </div>
          <div class="input-group password-field pill-field">
            <input :type="showRegisterPassword ? 'text' : 'password'" class="input-field" v-model="registerPassword" placeholder="请输入密码" autocomplete="new-password" />
            <button type="button" class="eye-btn" @click="showRegisterPassword = !showRegisterPassword" aria-label="切换密码显示"><Eye class="eye-icon" /></button>
          </div>
          <div class="input-group password-field pill-field">
            <input :type="showRegisterConfirmPassword ? 'text' : 'password'" class="input-field" v-model="registerConfirmPassword" placeholder="请再次输入密码" autocomplete="new-password" />
            <button type="button" class="eye-btn" @click="showRegisterConfirmPassword = !showRegisterConfirmPassword" aria-label="切换密码显示"><Eye class="eye-icon" /></button>
          </div>
          <div class="input-group code-field">
            <div class="code-row pill-field">
              <input type="text" class="input-field" v-model="registerCode" placeholder="请输入验证码" maxlength="6" inputmode="numeric" />
              <button type="button" class="code-btn" :disabled="codeSending" @click="handleSendCode('register')">{{ codeBtnText }}</button>
            </div>
          </div>
          <p class="agreement-text">
            注册即代表已阅读并同意我们的
            <a href="#" @click.prevent>隐私政策</a>
          </p>
        </div>

        <div v-else-if="currentMode === 'forgotVerify'" class="forgot-form">
          <div class="input-group">
            <input type="text" class="input-field pill-field" v-model="resetAccount" placeholder="请输入 +86 手机号" autocomplete="username" />
          </div>
          <div class="input-group code-field">
            <div class="code-row pill-field">
              <input type="text" class="input-field" v-model="resetCode" placeholder="请输入验证码" maxlength="6" inputmode="numeric" />
              <button type="button" class="code-btn" :disabled="codeSending" @click="handleSendCode('reset')">{{ codeBtnText }}</button>
            </div>
          </div>
        </div>

        <div v-else class="forgot-reset-form">
          <div class="input-group password-field pill-field">
            <input :type="showResetPassword ? 'text' : 'password'" class="input-field" v-model="resetPasswordValue" placeholder="请输入新密码" autocomplete="new-password" />
            <button type="button" class="eye-btn" @click="showResetPassword = !showResetPassword" aria-label="切换密码显示"><Eye class="eye-icon" /></button>
          </div>
          <div class="input-group password-field pill-field">
            <input :type="showResetConfirmPassword ? 'text' : 'password'" class="input-field" v-model="resetConfirmPassword" placeholder="请确认新密码" autocomplete="new-password" />
            <button type="button" class="eye-btn" @click="showResetConfirmPassword = !showResetConfirmPassword" aria-label="切换密码显示"><Eye class="eye-icon" /></button>
          </div>
        </div>

        <p v-if="tipMsg" class="tip-message">{{ tipMsg }}</p>
        <p v-if="errorMsg" class="error-message">{{ errorMsg }}</p>

        <button type="submit" class="login-btn" :disabled="submitting">
          {{ submitting ? submitPendingLabel : submitLabel }}
        </button>
      </form>

      <p v-if="currentMode === 'smsLogin'" class="auth-switch">
        <a href="#" @click.prevent="switchMode('passwordLogin')">密码登录</a>
      </p>
      <p v-else-if="currentMode === 'passwordLogin'" class="auth-switch">
        <a href="#" @click.prevent="switchMode('smsLogin')">验证码登录</a>
      </p>
      <p v-else-if="currentMode === 'register' || currentMode === 'forgotVerify'" class="auth-switch plain-link">
        <a href="#" @click.prevent="switchMode('passwordLogin')">返回登录</a>
      </p>
      <p v-else class="auth-switch plain-link">
        <a href="#" @click.prevent="switchMode('forgotVerify')">返回</a>
      </p>
    </div>
  </div>
  </div>
</template>

<script setup>
  import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { Eye, LayoutGrid, Rocket, Shield, Zap } from 'lucide-vue-next';
  import { resetPassword, sendSmsCode } from '@/api';
  import { useAuthStore } from '@/store/authStore';

  // ================= 鼠标跟随效果 =================
  const mouseGlow = ref(null);
  function handleMouseMove(e) {
    if (mouseGlow.value) {
      mouseGlow.value.style.left = e.clientX + 'px';
      mouseGlow.value.style.top = e.clientY + 'px';
    }
  }

  const router = useRouter();
  const route = useRoute();
  const auth = useAuthStore();
  const aliyunCaptchaConfig = {
    enabled: import.meta.env.VITE_ALIYUN_CAPTCHA_ENABLED === 'true',
    region: import.meta.env.VITE_ALIYUN_CAPTCHA_REGION || 'cn',
    prefix: import.meta.env.VITE_ALIYUN_CAPTCHA_PREFIX || '',
    sceneId: import.meta.env.VITE_ALIYUN_CAPTCHA_SCENE_ID || '',
  };
  let aliyunCaptchaInstance = null;
  let aliyunCaptchaLoading = null;
  let pendingCaptchaResolve = null;
  let pendingCaptchaReject = null;

  // ================= 开屏动画控制 =================
  const brandVisible = ref(false);
  const subVisible = ref(false);
  const buttonsVisible = ref(false);
  const titleVisible = ref(false);
  const line1El = ref(null);
  const line2El = ref(null);
  const heroTitleRef = ref(null);

  const fullLine1 = '企业级 AI 工具市场';
  const fullLine2 = '让每位成员都有专属 AI 助手';

  // 打字机效果
  function typeWriter(element, text, speed, callback) {
    let i = 0;
    element.innerHTML = '';
    function addChar() {
      if (i < text.length) {
        const char = text[i];
        const span = document.createElement('span');
        span.textContent = char;
        span.style.display = 'inline-block';
        element.appendChild(span);
        i++;
        setTimeout(addChar, speed);
      } else {
        callback && callback();
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
    setTimeout(() => { brandVisible.value = true; }, 100);
    setTimeout(() => { subVisible.value = true; }, 300);
    setTimeout(() => { buttonsVisible.value = true; }, 500);
    setTimeout(async () => {
      titleVisible.value = true;
      await nextTick();
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
  const account = ref('');
  const password = ref('');
  const phone = ref('');
  const smsCode = ref('');
  const registerPhone = ref('');
  const registerPassword = ref('');
  const registerConfirmPassword = ref('');
  const registerCode = ref('');
  const resetAccount = ref('');
  const resetCode = ref('');
  const verifiedResetPhone = ref('');
  const resetPasswordValue = ref('');
  const resetConfirmPassword = ref('');
  const showPassword = ref(false);
  const showRegisterPassword = ref(false);
  const showRegisterConfirmPassword = ref(false);
  const showResetPassword = ref(false);
  const showResetConfirmPassword = ref(false);
  const nickname = ref('');
  const tipMsg = ref('');
  const errorMsg = ref('');
  const submitting = ref(false);
  const codeSending = ref(false);
  const codeCountdown = ref(0);
  let countdownTimer = null;

  const loginTitle = computed(() => {
    if (currentMode.value === 'register') return '';
    if (currentMode.value === 'forgotVerify' || currentMode.value === 'forgotReset') return '重置统一登录密码';
    return currentMode.value === 'passwordLogin' ? '密码登录' : '手机号登录';
  });
  const loginSubtitle = computed(() => {
    if (currentMode.value === 'register') return '你所在地区仅支持手机号注册，只需一个未来云AI账号，即可访问未来云AI的所有服务。';
    if (currentMode.value === 'forgotVerify') return '请输入你注册的手机号用于接收验证码，我们将为你重置密码。';
    if (currentMode.value === 'forgotReset') return '你正在重置 ' + (verifiedResetPhone.value || resetTargetLabel.value) + ' 的密码，请输入新密码。';
    return currentMode.value === 'passwordLogin' ? '' : '未注册手机号验证后将自动创建账号';
  });
  const resetTargetLabel = computed(() => resetAccount.value.trim() || verifiedResetPhone.value);
  const submitLabel = computed(() => {
    if (currentMode.value === 'register') return '注册';
    if (currentMode.value === 'forgotVerify') return '下一步';
    if (currentMode.value === 'forgotReset') return '重置密码';
    return '登录';
  });
  const submitPendingLabel = computed(() => {
    if (currentMode.value === 'register') return '注册中...';
    if (currentMode.value === 'forgotVerify') return '处理中...';
    if (currentMode.value === 'forgotReset') return '重置中...';
    return '登录中...';
  });
  const codeBtnText = computed(() =>
    codeCountdown.value > 0 ? codeCountdown.value + ' 秒后可再次获取' : '发送验证码'
  );

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
  function normalizePhoneInput(value) {
    return value.trim().replace(/[\s-]/g, '').replace(/^\+?86/, '');
  }
  function validatePhone(value) {
    const phoneNum = normalizePhoneInput(value);
    if (!/^1\d{10}$/.test(phoneNum)) {
      throw new Error('请输入正确的 11 位手机号');
    }
    return phoneNum;
  }
  function validatePasswordPair(first, second) {
    if (!first || first.length < 6) throw new Error('密码至少需要 6 位');
    if (first !== second) throw new Error('两次输入的密码不一致');
  }
  function resetCountdown() {
    if (countdownTimer) clearInterval(countdownTimer);
    countdownTimer = null;
    codeCountdown.value = 0;
    codeSending.value = false;
  }
  function loadAliyunCaptchaScript() {
    if (!aliyunCaptchaConfig.enabled) return Promise.resolve(false);
    if (!aliyunCaptchaConfig.prefix || !aliyunCaptchaConfig.sceneId) {
      return Promise.reject(new Error('阿里云验证码前端配置不完整'));
    }
    if (window.initAliyunCaptcha) return Promise.resolve(true);
    if (aliyunCaptchaLoading) return aliyunCaptchaLoading;
    window.AliyunCaptchaConfig = {
      region: aliyunCaptchaConfig.region,
      prefix: aliyunCaptchaConfig.prefix,
    };
    aliyunCaptchaLoading = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = 'https://o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js';
      script.async = true;
      script.onload = () => resolve(true);
      script.onerror = () => reject(new Error('阿里云验证码脚本加载失败'));
      document.head.appendChild(script);
    });
    return aliyunCaptchaLoading;
  }
  async function ensureAliyunCaptcha() {
    const loaded = await loadAliyunCaptchaScript();
    if (!loaded || aliyunCaptchaInstance) return loaded;
    if (!window.initAliyunCaptcha) {
      throw new Error('阿里云验证码初始化方法不可用');
    }
    window.initAliyunCaptcha({
      SceneId: aliyunCaptchaConfig.sceneId,
      mode: 'popup',
      element: '#aliyun-captcha-element',
      button: '#aliyun-captcha-trigger',
      language: 'cn',
      delayBeforeSuccess: false,
      slideStyle: {
        width: 360,
        height: 40,
      },
      success(captchaVerifyParam) {
        const resolve = pendingCaptchaResolve;
        pendingCaptchaResolve = null;
        pendingCaptchaReject = null;
        if (resolve) resolve(captchaVerifyParam);
      },
      fail(result) {
        console.error(result);
      },
      onError(errorInfo) {
        const reject = pendingCaptchaReject;
        pendingCaptchaResolve = null;
        pendingCaptchaReject = null;
        if (reject) reject(new Error(errorInfo?.msg || '阿里云验证码初始化失败'));
      },
      getInstance(instance) {
        aliyunCaptchaInstance = instance;
      },
    });
    return true;
  }
  async function verifyAliyunCaptcha() {
    const enabled = await ensureAliyunCaptcha();
    if (!enabled) return null;
    return new Promise((resolve, reject) => {
      pendingCaptchaResolve = resolve;
      pendingCaptchaReject = reject;
      const trigger = document.getElementById('aliyun-captcha-trigger');
      if (trigger) {
        trigger.click();
      } else if (aliyunCaptchaInstance?.show) {
        aliyunCaptchaInstance.show();
      } else {
        pendingCaptchaResolve = null;
        pendingCaptchaReject = null;
        reject(new Error('阿里云验证码触发失败'));
      }
    });
  }
  async function handleSendCode(target = 'login') {
    let phoneNum;
    try {
      if (target === 'register') phoneNum = validatePhone(registerPhone.value);
      else if (target === 'reset') phoneNum = validatePhone(resetAccount.value);
      else phoneNum = validatePhone(phone.value);
    } catch (error) {
      showError(error instanceof Error ? error.message : '请输入正确的手机号');
      return;
    }
    clearMessages();
    codeSending.value = true;
    try {
      const scene = target === 'register' ? 'REGISTER' : target === 'reset' ? 'RESET_PASSWORD' : 'LOGIN_OR_REGISTER';
      const captchaVerifyParam = await verifyAliyunCaptcha();
      const res = await sendSmsCode({ phone: phoneNum, scene, captchaVerifyParam });
      codeCountdown.value = res.cooldownSeconds || 60;
      if (countdownTimer) clearInterval(countdownTimer);
      countdownTimer = setInterval(() => {
        if (codeCountdown.value <= 0) {
          clearInterval(countdownTimer);
          countdownTimer = null;
          codeSending.value = false;
        } else {
          codeCountdown.value--;
        }
      }, 1000);
    } catch (e) {
      showError(e instanceof Error ? e.message : '验证码发送失败');
      codeSending.value = false;
    }
  }

  function resetLoginForm() {
    account.value = '';
    password.value = '';
    phone.value = '';
    smsCode.value = '';
    registerPhone.value = '';
    registerPassword.value = '';
    registerConfirmPassword.value = '';
    registerCode.value = '';
    resetAccount.value = '';
    resetCode.value = '';
    verifiedResetPhone.value = '';
    resetPasswordValue.value = '';
    resetConfirmPassword.value = '';
    nickname.value = '';
    resetCountdown();
  }

  function resolvePostLoginRedirect() {
    const raw = route.query.redirect;
    if (
      typeof raw !== 'string' ||
      !raw.startsWith('/') ||
      raw === '/' ||
      raw === '/login' ||
      raw.startsWith('/login?')
    ) {
      return '/marketplace';
    }
    return raw;
  }

  async function enterAfterLogin() {
    if (!auth.isLoggedIn) {
      showError('登录未生效，请检查账号密码或后端服务');
      return;
    }
    loginModalVisible.value = false;
    resetLoginForm();
    await router.replace(resolvePostLoginRedirect());
  }

  async function handleSubmit() {
    if (submitting.value) return;
    clearMessages();
    submitting.value = true;
    try {
      if (currentMode.value === 'passwordLogin') {
        if (!account.value.trim() || !password.value) throw new Error('请输入账号和密码');
        await auth.login({ account: account.value.trim(), password: password.value });
        await enterAfterLogin();
        return;
      }

      if (currentMode.value === 'smsLogin') {
        const phoneNum = validatePhone(phone.value);
        if (!/^\d{6}$/.test(smsCode.value)) throw new Error('请输入 6 位短信验证码');
        await auth.smsLogin({ phone: phoneNum, code: smsCode.value.trim() });
        await enterAfterLogin();
        return;
      }

      if (currentMode.value === 'register') {
        const phoneNum = validatePhone(registerPhone.value);
        validatePasswordPair(registerPassword.value, registerConfirmPassword.value);
        if (!/^\d{6}$/.test(registerCode.value)) throw new Error('请输入 6 位短信验证码');
        await auth.smsRegister({
          phone: phoneNum,
          code: registerCode.value.trim(),
          password: registerPassword.value,
        });
        await enterAfterLogin();
        return;
      }

      if (currentMode.value === 'forgotVerify') {
        const phoneNum = validatePhone(resetAccount.value);
        if (!/^\d{6}$/.test(resetCode.value)) throw new Error('请输入 6 位短信验证码');
        verifiedResetPhone.value = phoneNum;
        currentMode.value = 'forgotReset';
        clearMessages();
        return;
      }

      validatePasswordPair(resetPasswordValue.value, resetConfirmPassword.value);
      const phoneNum = verifiedResetPhone.value || validatePhone(resetAccount.value);
      await resetPassword({
        phone: phoneNum,
        code: resetCode.value.trim(),
        password: resetPasswordValue.value,
      });
      showTip('密码已重置，请使用新密码登录');
      password.value = '';
      account.value = phoneNum;
      currentMode.value = 'passwordLogin';
    } catch (err) {
      showError(err instanceof Error ? err.message : '操作失败');
    } finally {
      submitting.value = false;
    }
  }

  // ================= 生命周期 =================
  onMounted(() => {
    if (auth.isLoggedIn) {
      router.replace(resolvePostLoginRedirect());
      return;
    }
    startEntranceAnimation();
    initTrail();
    window.addEventListener('mousemove', handleMouseMove);
  });
  onBeforeUnmount(() => {
    if (trailAnimationId) cancelAnimationFrame(trailAnimationId);
    if (countdownTimer) clearInterval(countdownTimer);
    if (expandTimeout) clearTimeout(expandTimeout);
    window.removeEventListener('mousemove', handleMouseMove);
  });
</script>

<style>
/* 登录页样式限定在 .page-root，避免跳转后卸载全局 body/* 规则 */
.page-root,
.page-root * {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  user-select: none;
}

.wave-logo {
  width: 44px;
  height: 44px;
  object-fit: contain;
}


.page-root {
  min-height: 100vh;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  font-family: "Inter", "Microsoft YaHei", "PingFang SC", "Segoe UI", system-ui, sans-serif;
  background: radial-gradient(circle at 20% 30%, #e9f0fc, #f4f8ff);
  overflow: hidden;
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

/* ========= 鼠标跟随光晕效果 ========= */
.mouse-glow {
  position: fixed;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(42, 110, 255, 0.15) 0%, rgba(42, 110, 255, 0) 70%);
  pointer-events: none;
  z-index: 0;
  transform: translate(-50%, -50%);
  transition: opacity 0.3s ease;
}

.aliyun-captcha-element,
.aliyun-captcha-trigger {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

/* ========= fade-up 入场（原生 @keyframes，保留 .animate-item / .show class） ========= */
@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* ========= 按钮波纹效果 ========= */
.btn {
  position: relative;
  overflow: hidden;
}
.btn::after {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  width: 0;
  height: 0;
  background: rgba(255, 255, 255, 0.3);
  border-radius: 50%;
  transform: translate(-50%, -50%);
  transition: width 0.6s ease, height 0.6s ease;
}
.btn:active::after {
  width: 300%;
  height: 300%;
}

/* ========= 按钮悬浮增强效果 ========= */
.btn {
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.btn:hover {
  transform: translateY(-3px) scale(1.02);
  box-shadow: 0 12px 24px rgba(42, 110, 255, 0.15);
}

/* ========= 登录弹窗入场动画 ========= */
.login-modal {
  animation: fadeIn 0.3s ease-out;
}
.login-container {
  animation: slideUp 0.4s cubic-bezier(0.25, 0.8, 0.25, 1);
}
@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(30px) scale(0.95);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

/* ========= 输入框聚焦动画 ========= */
.input-field {
  transition: all 0.3s ease;
}
.input-field:focus {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(42, 110, 255, 0.2);
}

/* ========= 标签页切换动画 ========= */
.mode-tabs button {
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.mode-tabs button.active {
  transform: scale(1.05);
}

/* ========= 波浪logo动画 ========= */
.wave-logo {
  animation: waveFloat 4s ease-in-out infinite;
}
@keyframes waveFloat {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-5px); }
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

.animate-item {
  opacity: 0;
  transform: translateY(20px);
}
.animate-item.show {
  animation: fade-up 0.6s ease forwards;
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
  border: 1px solid rgba(42, 110, 255, 0.3); /* 降低透明度，更柔和 */
  animation: ringPulse 2s ease-out infinite;
  pointer-events: none;

  /* 关键修改：从正圆改成横向拉长的椭圆 */
  width: 200px;    /* 横向宽度加大 */
  height: 60px;    /* 纵向高度缩小，变成椭圆/线条感 */
}

.button-glow-ring.second {
  animation-delay: 0.6s;
  width: 300px;    /* 第二层更宽 */
  height: 80px;
}
.button-glow-ring.third {
  animation-delay: 1.2s;
  width: 400px;    /* 第三层最宽 */
  height: 100px;
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
  max-width: 380px;
  margin: 1.25rem;
  background: white;
  border: 1px solid #d8e7ff;
  border-radius: 24px;
  box-shadow: 0 24px 60px rgba(37, 99, 235, 0.18);
  overflow: hidden;
}
.login-container.compact-login .login-card {
  padding-top: 0.875rem;
}
.login-header {
  padding: 1.75rem 1.375rem 0.875rem;
  text-align: center;
}
.login-title {
  font-size: 1.375rem;
  font-weight: 700;
  color: #1455d9;
  margin: 0 0 0.45rem 0;
  letter-spacing: 0;
}
.login-title:empty {
  display: none;
}
.login-subtitle {
  font-size: 0.8125rem;
  color: #5f7fb8;
  margin: 0;
}
.login-card {
  padding: 0.5rem 1.375rem 0;
}
.input-group {
  margin-bottom: 1.125rem;
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
  height: 100%;
  padding: 0 1rem;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: #121826;
  font-size: 0.9375rem;
  box-sizing: border-box;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.input-field:focus {
  outline: none;
  transform: none;
  box-shadow: none;
}
.input-field::placeholder {
  color: #b5bdc9;
}
.pill-field,
.phone-field {
  height: 48px;
  display: flex;
  align-items: center;
  border: 1px solid #cfe0ff;
  border-radius: 999px;
  background: #fff;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.pill-field:focus-within,
.phone-field:focus-within {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}
.phone-field {
  padding-left: 1rem;
}
.phone-field .input-field {
  padding-left: 0.55rem;
}
.country-code {
  color: #1455d9;
  font-size: 0.9375rem;
  white-space: nowrap;
}
.password-field .input-field {
  min-width: 0;
  padding-right: 0.5rem;
}
.eye-btn {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  display: grid;
  place-items: center;
  border: none;
  background: transparent;
  color: #2563eb;
  cursor: pointer;
}
.eye-icon {
  width: 16px;
  height: 16px;
  stroke-width: 2;
}
.code-row {
  overflow: hidden;
}
.code-row .input-field {
  min-width: 0;
}
.code-btn {
  align-self: stretch;
  min-width: 118px;
  padding: 0 0.85rem;
  border: none;
  border-left: 1px solid #d8e7ff;
  background: transparent;
  color: #1455d9;
  font-size: 0.8125rem;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.2s ease, background 0.2s ease;
}
.code-btn:hover:not(:disabled) {
  color: #2563eb;
  background: #eff6ff;
}
.code-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.agreement-text {
  margin: 0.25rem 0 1.375rem;
  color: #6b7280;
  font-size: 0.75rem;
  line-height: 1.7;
}
.agreement-text a {
  color: #1455d9;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.password-agreement {
  margin-top: -0.25rem;
  margin-bottom: 1.25rem;
}
.auth-row-links {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: -0.25rem 0 1.125rem;
  font-size: 0.8125rem;
}
.auth-row-links a {
  color: #1455d9;
  text-decoration: none;
}
.auth-row-links a:hover {
  text-decoration: underline;
  text-underline-offset: 2px;
}
.forgot-reset-form {
  padding-top: 1.5rem;
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
  height: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  border: none;
  border-radius: 999px;
  background: #2563eb;
  color: white;
  font-size: 0.9375rem;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease, opacity 0.2s ease;
}
.login-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 14px 24px rgba(37, 99, 235, 0.28);
}
.login-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.auth-switch {
  text-align: center;
  padding: 1rem 1.5rem 1.5rem;
  font-size: 0.75rem;
  color: #6b7280;
}
.auth-switch a {
  color: #6b7280;
  text-decoration: none;
  border-bottom: 1px solid currentColor;
}
.auth-switch a:hover {
  color: #1455d9;
}
.auth-switch.plain-link a {
  color: #1455d9;
  border-bottom: none;
  font-size: 0.875rem;
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
