<script setup>
import { computed } from 'vue'
import defaultAvatar from '@/assets/original/default-avatar.png'
import { hallReady, sessionStatus } from '@/utils/game-session-state.js'

const props = defineProps({ session: Object, connected: Boolean, tab: String })
const hallConnected = computed(() => props.connected && hallReady(props.session))
const status = computed(() => sessionStatus(props.session, props.connected))
const emit = defineEmits(['tab', 'qr', 'logout'])
const number = (value) => new Intl.NumberFormat('zh-CN').format(Number(value || 0))
function avatarFallback(event) {
  if (event.currentTarget.src !== defaultAvatar) event.currentTarget.src = defaultAvatar
}
</script>

<template>
  <header class="lobby-header">
    <div class="lobby-profile">
      <div class="avatar-ring">
        <img :src="session?.avatarUrl || defaultAvatar" alt="玩家头像" @error="avatarFallback" />
        <span :class="{ online: hallConnected }" :title="status"></span>
      </div>
      <div class="profile-copy">
        <strong>{{ session?.nickname || '欢乐玩家' }}</strong>
        <small>{{ hallConnected ? `ID ${session.uin} · 大厅已连接` : status }}</small>
      </div>
    </div>

    <nav class="lobby-nav" aria-label="大厅导航">
      <button :class="{ active: tab === 'modes' }" type="button" @click="emit('tab', 'modes')">
        <span>♠</span> 对战
      </button>
      <button :class="{ active: tab === 'rewards' }" type="button" @click="emit('tab', 'rewards')">
        <span>✦</span> 活动奖励
      </button>
      <button :class="{ active: tab === 'ai' }" type="button" @click="emit('tab', 'ai')">
        <span>◎</span> AI 托管
      </button>
    </nav>

    <div class="wallet-bar">
      <div class="currency">
        <i class="bean-icon">豆</i><strong>{{ number(session?.beans) }}</strong>
      </div>
      <div class="currency">
        <i class="diamond-icon">◆</i><strong>{{ number(session?.diamonds) }}</strong>
      </div>
      <button
        class="round-action"
        type="button"
        title="扫码登录游戏账号"
        aria-label="扫码登录游戏账号"
        @click="emit('qr')"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M4 9V5a1 1 0 0 1 1-1h4M15 4h4a1 1 0 0 1 1 1v4M20 15v4a1 1 0 0 1-1 1h-4M9 20H5a1 1 0 0 1-1-1v-4"
          />
          <rect x="8" y="8" width="8" height="8" rx="2" />
          <path d="M10.5 11h3M10.5 13.5h3" />
        </svg>
      </button>
      <button
        class="round-action"
        type="button"
        title="退出站点账号"
        aria-label="退出站点账号"
        @click="emit('logout')"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M10 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4M14 8l4 4-4 4M18 12H9" />
        </svg>
      </button>
    </div>
  </header>
</template>
