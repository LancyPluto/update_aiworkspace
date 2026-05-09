<script setup lang="ts">
import { RouterLink } from "vue-router"
import { Sparkles, ShieldCheck, Zap, Boxes } from "lucide-vue-next"
</script>

<template>
  <div class="login-layout">
    <aside class="hero-side">
      <div class="blur-bg" aria-hidden>
        <div class="blur-circle top-left" />
        <div class="blur-circle bottom-right" />
      </div>
      <div class="brand">
        <div class="brand-icon">
          <Sparkles class="icon" />
        </div>
        <span class="brand-name">智效 AI 工作台</span>
      </div>

      <div class="hero-content">
        <h1 class="hero-title">
          企业级 AI 工具中台
          <br />
          让每位员工都拥有专属 AI 助手
        </h1>
        <p class="hero-desc">
          统一管理 Prompt、工具、算力与素材，按部门精细化分配权限与配额，沉淀企业知识资产。
        </p>
        <div class="stats-grid">
          <div class="stat-item">
            <Boxes class="stat-icon" />120+ 工具
          </div>
          <div class="stat-item">
            <ShieldCheck class="stat-icon" />企业合规
          </div>
          <div class="stat-item">
            <Zap class="stat-icon" />多模型路由
          </div>
        </div>
      </div>

      <div class="footer-copyright">© 2026 智效科技 · 已服务 500+ 企业客户</div>
    </aside>

    <main class="login-main">
      <div class="login-container">
        <div class="mobile-brand">
          <div class="brand-icon">
            <Sparkles class="icon" />
          </div>
          <span class="brand-name">智效 AI 工作台</span>
        </div>

        <div class="login-header">
          <h2 class="login-title">登录账号</h2>
          <p class="login-subtitle">使用企业邮箱或扫码登录，开始你的 AI 工作流</p>
        </div>

        <div class="login-card">
          <label class="input-group">
            <span class="input-label">企业邮箱</span>
            <input
              type="email"
              class="input-field"
              placeholder="name@company.com"
              value="zhangwei@acme.com"
            />
          </label>
          <label class="input-group">
            <span class="input-label">登录密码</span>
            <input
              type="password"
              class="input-field"
              placeholder="请输入密码"
            />
          </label>
          <RouterLink
            :to="{ name: 'ToolList' }"
            class="login-btn"
          >
            登录工作台
          </RouterLink>
        </div>

        <p class="footer-link">
          <RouterLink :to="{ name: 'ToolList' }" class="toolstore-link">
            进入 AI 工具超市
          </RouterLink>
        </p>
      </div>
    </main>
  </div>
</template>

<style scoped>
  /* 布局 */
  .login-layout {
    min-height: 100vh;
    display: grid;
    background-color: var(--background);
  }
  @media (min-width: 1024px) {
    .login-layout {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  /* 左侧品牌区 */
  .hero-side {
    position: relative;
    display: none;
    flex-direction: column;
    justify-content: space-between;
    padding: 3rem;
    background: linear-gradient(to bottom right, var(--card), var(--accent) / 0.3);
    border-right: 1px solid var(--border);
    overflow: hidden;
  }
  @media (min-width: 1024px) {
    .hero-side {
      display: flex;
    }
  }

  .blur-bg {
    position: absolute;
    inset: 0;
    opacity: 0.2;
    pointer-events: none;
  }
  .blur-circle {
    position: absolute;
    width: 18rem;
    height: 18rem;
    border-radius: 9999px;
    filter: blur(3rem);
  }
  .blur-circle.top-left {
    top: 5rem;
    left: 5rem;
    background-color: var(--primary);
  }
  .blur-circle.bottom-right {
    bottom: 2.5rem;
    right: 2.5rem;
    background-color: var(--accent);
  }

  .brand {
    position: relative;
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  .brand-icon {
    display: flex;
    height: 2.25rem;
    width: 2.25rem;
    align-items: center;
    justify-content: center;
    border-radius: 0.5rem;
    background-color: var(--primary);
    color: var(--primary-foreground);
  }
  .icon {
    height: 1.25rem;
    width: 1.25rem;
  }
  .brand-name {
    font-size: 1.125rem;
    font-weight: 600;
    letter-spacing: -0.025em;
  }

  .hero-content {
    position: relative;
    margin: 2rem 0;
    display: flex;
    flex-direction: column;
    gap: 1.5rem;
  }
  .hero-title {
    font-size: 2.25rem;
    font-weight: 600;
    line-height: 1.2;
    text-wrap: balance;
  }
  .hero-desc {
    font-size: 0.875rem;
    color: var(--muted-foreground);
    line-height: 1.625;
    max-width: 28rem;
  }
  .stats-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 1rem;
    padding-top: 1rem;
    max-width: 28rem;
  }
  .stat-item {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    border-radius: 0.5rem;
    border: 1px solid var(--border);
    background-color: var(--card) / 0.6;
    backdrop-filter: blur(4px);
    padding: 0.75rem;
    font-size: 0.75rem;
  }
  .stat-icon {
    height: 1rem;
    width: 1rem;
    color: var(--primary);
  }

  .footer-copyright {
    position: relative;
    font-size: 0.75rem;
    color: var(--muted-foreground);
  }

  /* 右侧登录区 */
  .login-main {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 1.5rem;
  }
  @media (min-width: 640px) {
    .login-main {
      padding: 3rem;
    }
  }
  .login-container {
    width: 100%;
    max-width: 28rem;
    display: flex;
    flex-direction: column;
    gap: 2rem;
  }
  .mobile-brand {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  @media (min-width: 1024px) {
    .mobile-brand {
      display: none;
    }
  }
  .login-header {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }
  .login-title {
    font-size: 1.5rem;
    font-weight: 600;
    letter-spacing: -0.025em;
  }
  .login-subtitle {
    font-size: 0.875rem;
    color: var(--muted-foreground);
  }
  .login-card {
    border-radius: 0.75rem;
    border: 1px solid var(--border);
    background-color: var(--card);
    padding: 1.5rem;
    display: flex;
    flex-direction: column;
    gap: 1rem;
    box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05);
  }
  .input-group {
    display: block;
  }
  .input-label {
    display: block;
    font-size: 0.875rem;
    font-weight: 500;
    margin-bottom: 0.5rem;
  }
  .input-field {
    display: flex;
    height: 2.5rem;
    width: 100%;
    border-radius: 0.375rem;
    border: 1px solid var(--border);
    background-color: var(--background);
    padding: 0.5rem 0.75rem;
    font-size: 0.875rem;
  }
  .input-field:focus {
    outline: none;
    box-shadow: 2px solid var(--ring);
  }
  .login-btn {
    display: inline-flex;
    height: 2.75rem;
    width: 100%;
    align-items: center;
    justify-content: center;
    border-radius: 0.375rem;
    background-color: var(--primary);
    padding: 0 1rem;
    font-size: 0.875rem;
    font-weight: 500;
    color: var(--primary-foreground);
    text-decoration: none;
  }
  .login-btn:hover {
    opacity: 0.9;
  }
  .footer-link {
    text-align: center;
    font-size: 0.75rem;
    color: var(--muted-foreground);
  }
  .toolstore-link {
    color: var(--primary);
    text-decoration: none;
  }
  .toolstore-link:hover {
    text-decoration: underline;
  }
</style>