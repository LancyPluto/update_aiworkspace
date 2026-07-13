<template>
  <div class="page-root">
    <main class="relative min-h-screen overflow-x-hidden">
      <Navigation @open-login="openLoginModal" />
      <HeroSection @open-login="openLoginModal" />
      <FeaturesSection />
      <HowItWorksSection />
      <InfrastructureSection />
      <MetricsSection />
      <IntegrationsSection />
      <SecuritySection />
      <DevelopersSection />
      <TestimonialsSection />
      <PricingSection @open-login="openLoginModal" />
      <CtaSection @open-login="openLoginModal" />
      <FooterSection />
    </main>

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
            <RouterLink to="/legal/privacy">隐私政策</RouterLink>
            和<RouterLink to="/legal/terms">服务条款</RouterLink>
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
            <RouterLink to="/legal/privacy">隐私政策</RouterLink>
            和<RouterLink to="/legal/terms">服务条款</RouterLink>
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
            <RouterLink to="/legal/privacy">隐私政策</RouterLink>
            和<RouterLink to="/legal/terms">服务条款</RouterLink>
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
  import { ref, computed, onMounted, onBeforeUnmount, defineAsyncComponent } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { Eye } from 'lucide-vue-next';
  import { resetPassword, sendSmsCode } from '@/api';
  import { useAuthStore } from '@/store/authStore';
  import { Navigation, HeroSection } from '@/components/landing';

  const FeaturesSection = defineAsyncComponent(() => import('@/components/landing/FeaturesSection.vue'));
  const HowItWorksSection = defineAsyncComponent(() => import('@/components/landing/HowItWorksSection.vue'));
  const InfrastructureSection = defineAsyncComponent(() => import('@/components/landing/InfrastructureSection.vue'));
  const MetricsSection = defineAsyncComponent(() => import('@/components/landing/MetricsSection.vue'));
  const IntegrationsSection = defineAsyncComponent(() => import('@/components/landing/IntegrationsSection.vue'));
  const SecuritySection = defineAsyncComponent(() => import('@/components/landing/SecuritySection.vue'));
  const DevelopersSection = defineAsyncComponent(() => import('@/components/landing/DevelopersSection.vue'));
  const TestimonialsSection = defineAsyncComponent(() => import('@/components/landing/TestimonialsSection.vue'));
  const PricingSection = defineAsyncComponent(() => import('@/components/landing/PricingSection.vue'));
  const CtaSection = defineAsyncComponent(() => import('@/components/landing/CtaSection.vue'));
  const FooterSection = defineAsyncComponent(() => import('@/components/landing/FooterSection.vue'));

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

  const loginModalVisible = ref(false);

  function openLoginModal() {
    loginModalVisible.value = true;
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

  const inviteCode = computed(() => {
    const raw = route.query.invite;
    return typeof raw === 'string' && raw.trim() ? raw.trim() : undefined;
  });

  const loginTitle = computed(() => {
    if (currentMode.value === 'register') return '';
    if (currentMode.value === 'forgotVerify' || currentMode.value === 'forgotReset') return '重置统一登录密码';
    return currentMode.value === 'passwordLogin' ? '密码登录' : '手机号登录';
  });
  const loginSubtitle = computed(() => {
    if (currentMode.value === 'register') return '你所在地区仅支持手机号注册，只需一个科创点AI账号，即可访问科创点AI的所有服务。';
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
      raw === '/home' ||
      raw === '/login' ||
      raw.startsWith('/login?')
    ) {
      return '/home';
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
        await auth.smsLogin({ phone: phoneNum, code: smsCode.value.trim(), inviteCode: inviteCode.value });
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
          inviteCode: inviteCode.value,
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
    if (inviteCode.value) {
      loginModalVisible.value = true;
      currentMode.value = 'register';
    }
  });
  onBeforeUnmount(() => {
    if (countdownTimer) clearInterval(countdownTimer);
  });
</script>

<style>
.page-root .login-card,
.page-root .login-card * {
  box-sizing: border-box;
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

/* 登录弹窗 — 与项目暗色 + 紫色 primary 风格一致 */
.login-modal {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgb(0 0 0 / 0.65);
  backdrop-filter: blur(12px);
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-container {
  width: 100%;
  max-width: 400px;
  margin: 1.25rem;
  background:
    radial-gradient(circle at 82% 0%, rgb(176 92 255 / 0.12), transparent 42%),
    linear-gradient(180deg, rgb(29 29 34 / 0.98), rgb(22 22 28 / 0.98));
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 28px;
  box-shadow:
    0 24px 80px rgb(0 0 0 / 0.55),
    0 0 0 1px rgb(255 255 255 / 0.04) inset,
    0 12px 40px rgb(176 92 255 / 0.12);
  overflow: hidden;
  color: var(--foreground);
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
  color: var(--foreground);
  margin: 0 0 0.45rem 0;
  letter-spacing: 0;
}
.login-title:empty {
  display: none;
}
.login-subtitle {
  font-size: 0.8125rem;
  color: var(--muted-foreground);
  margin: 0;
  line-height: 1.6;
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
  color: var(--muted-foreground);
}
.input-field {
  width: 100%;
  height: 100%;
  padding: 0 1rem;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--foreground);
  font-size: 0.9375rem;
  box-sizing: border-box;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.input-field:focus {
  outline: none;
}
.input-field::placeholder {
  color: rgb(255 255 255 / 0.35);
}
.pill-field,
.phone-field {
  height: 48px;
  display: flex;
  align-items: center;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 999px;
  background: rgb(255 255 255 / 0.04);
  transition: border-color 0.2s ease, box-shadow 0.2s ease, background 0.2s ease;
}
.pill-field:focus-within,
.phone-field:focus-within {
  border-color: rgb(176 92 255 / 0.55);
  background: rgb(255 255 255 / 0.06);
  box-shadow: 0 0 0 3px rgb(176 92 255 / 0.18);
}
.phone-field {
  padding-left: 1rem;
}
.phone-field .input-field {
  padding-left: 0.55rem;
}
.country-code {
  color: var(--primary);
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
  color: var(--primary);
  cursor: pointer;
}
.eye-btn:hover {
  color: var(--foreground);
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
  border-left: 1px solid rgb(255 255 255 / 0.1);
  background: transparent;
  color: var(--primary);
  font-size: 0.8125rem;
  font-weight: 500;
  cursor: pointer;
  transition: color 0.2s ease, background 0.2s ease;
}
.code-btn:hover:not(:disabled) {
  color: var(--foreground);
  background: rgb(176 92 255 / 0.12);
}
.code-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.agreement-text {
  margin: 0.25rem 0 1.375rem;
  color: rgb(255 255 255 / 0.45);
  font-size: 0.75rem;
  line-height: 1.7;
}
.agreement-text a {
  color: var(--primary);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.agreement-text a:hover {
  color: var(--foreground);
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
  color: var(--primary);
  text-decoration: none;
}
.auth-row-links a:hover {
  color: var(--foreground);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.forgot-reset-form {
  padding-top: 1.5rem;
}
.tip-message,
.error-message {
  font-size: 0.8rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.75rem;
  margin: 0.5rem 0;
}
.tip-message {
  color: var(--success);
  background: rgb(34 197 94 / 0.1);
  border: 1px solid rgb(34 197 94 / 0.25);
}
.error-message {
  color: var(--destructive);
  background: rgb(239 68 68 / 0.1);
  border: 1px solid rgb(239 68 68 / 0.28);
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
  background: var(--primary);
  color: var(--primary-foreground);
  font-size: 0.9375rem;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease, opacity 0.2s ease, filter 0.2s ease;
  box-shadow: 0 12px 28px rgb(176 92 255 / 0.32);
}
.login-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.08);
  box-shadow: 0 16px 36px rgb(176 92 255 / 0.4);
}
.login-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.auth-switch {
  text-align: center;
  padding: 1rem 1.5rem 1.5rem;
  font-size: 0.75rem;
  color: rgb(255 255 255 / 0.45);
}
.auth-switch a {
  color: rgb(255 255 255 / 0.55);
  text-decoration: none;
  border-bottom: 1px solid rgb(255 255 255 / 0.25);
  transition: color 0.2s ease;
}
.auth-switch a:hover {
  color: var(--primary);
  border-bottom-color: var(--primary);
}
.auth-switch.plain-link a {
  color: var(--primary);
  border-bottom: none;
  font-size: 0.875rem;
}
.auth-switch.plain-link a:hover {
  color: var(--foreground);
}
</style>
