<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from './components/AppIcon.vue'

interface CaptchaResponse {
  captchaId: string
  imageData: string
  expiresInSeconds: number
}

interface LoginResponse {
  token: string
  username: string
  role: string
  expiresAt: string
}

interface ApiError {
  code?: string
  message?: string
}

interface Overview {
  health: string
  taskTotal: number
  activeTasks: number
  mode: string
  agentTotal: number
}

interface AgentTask {
  id: string
  actionCode: string
  status: string
  externalRef?: string
  createdAt: string
}

type RuntimeConfig = Record<string, string | number | boolean>
interface RuntimeAgent {
  code: string
  displayName: string
  enabled: boolean
  visibleToVisitor: boolean
  iconKey: string
  actionCount: number
  actionModes: Record<string, number>
  routerStatus: string
  routeTotal: number
  ambiguousTotal: number
  failureTotal: number
}
type ConsoleSection = 'overview' | 'agents' | 'tasks' | 'config'

const api = import.meta.env.VITE_API_BASE || '/api/console/v1'
const sessionToken = ref(sessionStorage.getItem('console-token') || '')
const username = ref(sessionStorage.getItem('console-username') || 'admin')
const password = ref('')
const passwordVisible = ref(false)
const captchaId = ref('')
const captchaCode = ref('')
const captchaImage = ref('')
const captchaLoading = ref(false)
const active = ref<ConsoleSection>('overview')
const error = ref('')
const loading = ref(false)
const overview = ref<Overview | null>(null)
const tasks = ref<AgentTask[]>([])
const agents = ref<RuntimeAgent[]>([])
const config = ref<RuntimeConfig | null>(null)

const title = computed(() => ({
  overview: '运行总览',
  agents: '领域 Agent',
  tasks: '任务执行记录',
  config: '运行配置',
})[active.value])

async function responseError(response: Response, fallback: string): Promise<string> {
  try {
    const body = await response.json() as ApiError
    return body.message || fallback
  } catch {
    return fallback
  }
}

async function refreshCaptcha(): Promise<void> {
  captchaLoading.value = true
  try {
    const response = await fetch(`${api}/auth/captcha`, { cache: 'no-store' })
    if (!response.ok) {
      throw new Error(await responseError(response, '图片验证码加载失败，请稍后重试。'))
    }
    const captcha = await response.json() as CaptchaResponse
    captchaId.value = captcha.captchaId
    captchaImage.value = captcha.imageData
    captchaCode.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '图片验证码加载失败。'
  } finally {
    captchaLoading.value = false
  }
}

async function login(): Promise<void> {
  if (!username.value.trim() || !password.value || !captchaCode.value.trim()) {
    error.value = '请完整填写用户名、密码和图片验证码。'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const response = await fetch(`${api}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: username.value.trim(),
        password: password.value,
        captchaId: captchaId.value,
        captchaCode: captchaCode.value.trim(),
      }),
    })
    if (!response.ok) {
      throw new Error(await responseError(response, '登录失败，请检查登录信息。'))
    }
    const session = await response.json() as LoginResponse
    sessionToken.value = session.token
    username.value = session.username
    sessionStorage.setItem('console-token', session.token)
    sessionStorage.setItem('console-username', session.username)
    password.value = ''
    captchaCode.value = ''
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败。'
    await refreshCaptcha()
  } finally {
    loading.value = false
  }
}

async function load(): Promise<void> {
  if (!sessionToken.value) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    const path = active.value === 'overview'
      ? 'overview'
      : active.value === 'agents' ? 'agents'
        : active.value === 'tasks' ? 'tasks' : 'runtime-config'
    const response = await fetch(`${api}/${path}`, {
      headers: { Authorization: `Bearer ${sessionToken.value}` },
    })
    if (response.status === 401) {
      clearSession()
      await refreshCaptcha()
      throw new Error('登录会话已失效，请重新登录。')
    }
    if (!response.ok) {
      throw new Error(await responseError(response, '控制台数据加载失败。'))
    }
    const data: unknown = await response.json()
    if (active.value === 'overview') {
      overview.value = data as Overview
    } else if (active.value === 'agents') {
      agents.value = data as RuntimeAgent[]
    } else if (active.value === 'tasks') {
      tasks.value = data as AgentTask[]
    } else {
      config.value = data as RuntimeConfig
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败。'
  } finally {
    loading.value = false
  }
}

function navigate(value: ConsoleSection): void {
  active.value = value
  void load()
}

function clearSession(): void {
  sessionStorage.removeItem('console-token')
  sessionStorage.removeItem('console-username')
  sessionToken.value = ''
  overview.value = null
  tasks.value = []
  agents.value = []
  config.value = null
}

async function logout(): Promise<void> {
  const token = sessionToken.value
  try {
    await fetch(`${api}/auth/logout`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    })
  } finally {
    clearSession()
    error.value = ''
    await refreshCaptcha()
  }
}

onMounted(() => {
  if (sessionToken.value) {
    void load()
  } else {
    void refreshCaptcha()
  }
})
</script>

<template>
  <main v-if="!sessionToken" class="login-page">
    <section class="login-intro">
      <header class="login-brand">
        <span class="brand-mark"><AppIcon :size="36" /></span>
        <div class="brand-copy">
          <strong>Agent Console</strong>
          <small>AGENT TEMPLATE PRO</small>
        </div>
        <span class="environment-badge"><i />LOCAL WORKSPACE</span>
      </header>

      <div class="login-intro-content">
        <div class="context-copy">
          <p class="eyebrow">CONTROL PLANE</p>
          <h1>把自然语言交互，置于可控运行之中。</h1>
          <p>集中查看会话任务、动作确认与运行状态，让模型理解和确定性执行保持清晰边界。</p>
        </div>

        <section class="workspace-preview" aria-label="控制台工作区预览">
          <header class="preview-head">
            <strong>运行边界</strong>
            <span><i />READY</span>
          </header>
          <div class="preview-metrics">
            <div><AppIcon :size="18" /><small>Runtime</small><strong>CODE-FIRST</strong></div>
            <div><AppIcon :size="18" /><small>高风险动作</small><strong>二次确认</strong></div>
            <div><AppIcon :size="18" /><small>当前存储</small><strong>IN-MEMORY</strong></div>
          </div>
          <div class="preview-list">
            <div><span>访客身份</span><b>签名 Cookie 隔离</b></div>
            <div><span>动作执行</span><b>确定性代码校验</b></div>
            <div><span>状态追踪</span><b>任务与事件贯穿</b></div>
          </div>
        </section>
      </div>

      <footer class="login-intro-footer">
        <span><AppIcon :size="14" />管理端与匿名访客会话严格隔离</span>
        <span>LOCAL DEMO</span>
      </footer>
    </section>

    <section class="auth-pane">
      <form class="login-form" @submit.prevent="login">
        <div class="mobile-brand">
          <span class="brand-mark"><AppIcon :size="36" /></span>
          <div class="brand-copy"><strong>Agent Console</strong><small>AGENT TEMPLATE PRO</small></div>
        </div>
        <header class="form-header">
          <p class="eyebrow">身份验证 · AUTHENTICATION</p>
          <h2>登录管理后台</h2>
          <p>使用管理员账号登录，图片验证码不区分大小写。</p>
        </header>
        <el-alert v-if="error" type="error" :closable="false">
          <template #title><span class="alert-title"><AppIcon :size="16" />{{ error }}</span></template>
        </el-alert>
        <div class="field-group">
          <label for="username">用户名</label>
          <el-input id="username" v-model="username" autocomplete="username" placeholder="请输入管理员用户名" :disabled="loading">
            <template #prefix><AppIcon :size="17" /></template>
          </el-input>
        </div>
        <div class="field-group">
          <label for="password">密码</label>
          <el-input id="password" v-model="password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入管理员密码" :disabled="loading">
            <template #prefix><AppIcon :size="17" /></template>
            <template #suffix>
              <button class="password-toggle" type="button" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" @click="passwordVisible = !passwordVisible"><AppIcon :size="17" /></button>
            </template>
          </el-input>
        </div>
        <div class="field-group">
          <label for="captcha-code">图片验证码</label>
          <div class="captcha-field">
            <el-input id="captcha-code" v-model="captchaCode" maxlength="4" autocomplete="off" placeholder="请输入验证码" :disabled="loading">
              <template #prefix><AppIcon :size="17" /></template>
            </el-input>
            <button class="captcha-image" type="button" aria-label="刷新图片验证码" :disabled="captchaLoading" @click="refreshCaptcha">
              <img v-if="captchaImage" :src="captchaImage" alt="图片验证码" width="132" height="44">
              <AppIcon v-else class="captcha-placeholder" :size="18" />
            </button>
          </div>
        </div>
        <el-button native-type="submit" type="primary" :disabled="loading || captchaLoading">
          进入控制台 <AppIcon :class="{ spin: loading }" :size="16" />
        </el-button>
        <p class="security-note"><AppIcon :size="15" /><span>本地演示账号仅用于开发验证；部署环境必须替换默认密码并接入正式管理员权限体系。</span></p>
      </form>
      <footer class="auth-footer"><span>Agent Template Pro</span><span>简体中文</span></footer>
    </section>
  </main>

  <div v-else class="console-shell">
    <aside class="sidebar">
      <div class="sidebar-brand"><span><AppIcon :size="36" /></span><b>Agent<br>Template</b></div>
      <nav>
        <button :class="{ active: active === 'overview' }" @click="navigate('overview')"><AppIcon :size="17" /><span>运行总览</span></button>
        <button :class="{ active: active === 'agents' }" @click="navigate('agents')"><AppIcon :size="17" /><span>领域 Agent</span></button>
        <button :class="{ active: active === 'tasks' }" @click="navigate('tasks')"><AppIcon :size="17" /><span>任务记录</span></button>
        <button :class="{ active: active === 'config' }" @click="navigate('config')"><AppIcon :size="17" /><span>运行配置</span></button>
      </nav>
      <div class="sidebar-bottom"><span><i />本地演示环境</span></div>
    </aside>

    <header class="top-header">
      <div class="top-header-context"><span>Agent Console</span><i>/</i><strong>{{ title }}</strong></div>
      <div class="top-header-actions">
        <span class="runtime-status"><i />服务运行中</span>
        <div class="admin-profile">
          <span class="admin-avatar"><AppIcon :size="26" /></span>
          <span class="admin-copy"><strong>{{ username }}</strong><small>系统管理员</small></span>
        </div>
        <el-tooltip content="退出登录" placement="bottom">
          <el-button text circle aria-label="退出登录" @click="logout"><AppIcon :size="17" /></el-button>
        </el-tooltip>
      </div>
    </header>

    <main class="workspace" v-loading="loading && !overview">
      <header class="workspace-header">
        <div><p class="eyebrow">AGENT RUNTIME / {{ active.toUpperCase() }}</p><h1>{{ title }}</h1></div>
        <div class="header-actions">
          <el-button circle aria-label="刷新" :disabled="loading" @click="load"><AppIcon :class="{ spin: loading }" :size="17" /></el-button>
          <el-button type="primary" @click="navigate('tasks')">查看任务</el-button>
        </div>
      </header>
      <p v-if="error" class="page-error">{{ error }}</p>

      <section v-if="active === 'overview'" class="overview">
        <div class="metric-grid">
          <article><span>运行状态</span><strong class="healthy">{{ overview?.health || '-' }}</strong><small>服务可用性摘要</small></article>
          <article><span>任务总数</span><strong>{{ overview?.taskTotal ?? '-' }}</strong><small>当前运行内存中的任务</small></article>
          <article><span>等待中</span><strong>{{ overview?.activeTasks ?? '-' }}</strong><small>等待外部结果或用户确认</small></article>
          <article><span>领域 Agent</span><strong>{{ overview?.agentTotal ?? '-' }}</strong><small>代码注册且通过启动校验</small></article>
        </div>
        <section class="operation-panel">
          <div class="panel-header"><div><h2>运行边界</h2><p>模型提出意图，确定性动作负责校验、确认和执行。</p></div><el-tag type="success" effect="plain">{{ overview?.mode || 'loading' }}</el-tag></div>
          <div class="boundary-grid"><div><span>身份</span><b>签名访客 Cookie</b></div><div><span>会话</span><b>服务端归属校验</b></div><div><span>高风险动作</span><b>强制二次确认</b></div></div>
        </section>
      </section>

      <section v-if="active === 'agents'" class="agent-section">
        <div class="table-toolbar"><div><h2>领域 Agent 注册表</h2><p>只读展示运行时真实注册信息，不提供在线注入动作能力。</p></div><el-tag type="success" effect="plain">REGISTRY READY</el-tag></div>
        <div class="agent-grid">
          <article v-for="agent in agents" :key="agent.code">
            <header><div><small>{{ agent.code }}</small><h3>{{ agent.displayName }}</h3></div><el-tag :type="agent.enabled ? 'success' : 'info'" effect="plain">{{ agent.enabled ? '启用' : '停用' }}</el-tag></header>
            <dl><div><dt>C 端可见</dt><dd>{{ agent.visibleToVisitor ? '是' : '否' }}</dd></div><div><dt>注册动作</dt><dd>{{ agent.actionCount }}</dd></div><div><dt>路由器</dt><dd>{{ agent.routerStatus }}</dd></div></dl>
            <footer><el-tag v-for="(count, mode) in agent.actionModes" :key="mode" size="small" effect="plain">{{ mode }} {{ count }}</el-tag></footer>
            <p class="agent-metrics">路由 {{ agent.routeTotal }} · 澄清 {{ agent.ambiguousTotal }} · 失败 {{ agent.failureTotal }}</p>
          </article>
        </div>
      </section>

      <section v-if="active === 'tasks'" class="table-section">
        <div class="table-toolbar"><div><h2>任务执行记录</h2><p>确认、调用和异步状态均以任务为追踪入口。</p></div><el-button @click="load">刷新列表</el-button></div>
        <el-table :data="tasks" v-loading="loading" empty-text="暂无任务记录">
          <el-table-column prop="id" label="任务 ID" min-width="180" show-overflow-tooltip />
          <el-table-column prop="actionCode" label="动作" min-width="190" />
          <el-table-column label="状态" width="190"><template #default="scope"><el-tag :type="scope.row.status === 'SUCCEEDED' ? 'success' : scope.row.status.includes('WAITING') ? 'warning' : 'info'">{{ scope.row.status }}</el-tag></template></el-table-column>
          <el-table-column prop="externalRef" label="外部引用" min-width="160" />
          <el-table-column prop="createdAt" label="创建时间" min-width="180" />
        </el-table>
      </section>

      <section v-if="active === 'config'" class="config-section">
        <div class="panel-header"><div><h2>非敏感运行配置</h2><p>密钥和值不会通过控制台 API 返回。</p></div><AppIcon class="monitor" :size="24" /></div>
        <dl v-if="config"><template v-for="(value, key) in config" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl>
      </section>
    </main>
  </div>
</template>
