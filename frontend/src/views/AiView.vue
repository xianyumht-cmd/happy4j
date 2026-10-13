<script setup>
import { computed } from 'vue'
import AiControlPanel from '@/components/AiControlPanel.vue'

const props = defineProps({
  config: Object,
  modes: { type: Array, default: () => [] },
  session: Object,
  runtime: Object,
})
const emit = defineEmits(['start', 'stop', 'save', 'logs', 'provider-test'])
const openAiEnabled = computed(() => props.config?.inferenceEngine === 'OPENAI')

const games = computed(() => Number(props.runtime?.wins || 0) + Number(props.runtime?.losses || 0))
const winRate = computed(() =>
  games.value ? `${Math.round((Number(props.runtime?.wins || 0) * 100) / games.value)}%` : '—',
)
const recentDecisions = computed(() =>
  [...(props.runtime?.logs || [])]
    .filter((entry) => entry.phase === 'INFERENCE' || entry.phase === 'SETTLEMENT')
    .slice(-5)
    .reverse(),
)

function time(value) {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}
</script>

<template>
  <section class="lobby-content ai-page">
    <header class="ai-page-heading">
      <div>
        <span>DOUZERO AUTOPILOT</span>
        <h1>AI 托管中心</h1>
        <p>配置自动对局策略、资金保护与场次循环，运行后在牌桌内查看紧凑日志。</p>
      </div>
      <div class="ai-page-status" :class="{ active: runtime?.active }">
        <i></i><span>{{ runtime?.active ? '托管运行中' : '当前已停止' }}</span
        ><b>{{ runtime?.modelState || 'IDLE' }}</b>
      </div>
    </header>

    <AiControlPanel
      standalone
      :config="config"
      :modes="modes"
      :session="session"
      :runtime="runtime"
      @start="emit('start', $event)"
      @stop="emit('stop')"
      @save="emit('save', $event)"
      @logs="emit('logs')"
      @provider-test="emit('provider-test', $event)"
    />

    <div class="ai-page-dashboard">
      <section class="ai-page-metrics">
        <article>
          <span>有效对局胜率</span><b>{{ winRate }}</b
          ><small
            >{{ runtime?.wins || 0 }} 胜 / {{ runtime?.losses || 0 }} 负 ·
            {{ runtime?.unknown || 0 }} 待确认</small
          >
        </article>
        <article>
          <span>完成对局</span><b>{{ runtime?.rounds || 0 }}</b
          ><small>仅统计可识别结算事件</small>
        </article>
        <article>
          <span>主动出牌</span><b>{{ runtime?.plays || 0 }}</b
          ><small>{{ openAiEnabled ? '大模型或本地降级策略' : 'FullAuto ResNet与控牌路径策略' }}</small>
        </article>
        <article>
          <span>选择不出</span><b>{{ runtime?.passes || 0 }}</b
          ><small>规则强制或模型决策</small>
        </article>
      </section>

      <section class="ai-engine-card">
        <div class="ai-section-heading">
          <div>
            <span>DECISION PIPELINE</span>
            <h2>胜率优先决策链</h2>
          </div>
          <b>{{ openAiEnabled ? 'LLM' : 'RESNET ADP' }}</b>
        </div>
        <ol>
          <li>
            <i>1</i>
            <div><strong>状态校验</strong><span>核对三家剩余牌数与公开出牌历史</span></div>
          </li>
          <li>
            <i>2</i>
            <div><strong>合法动作</strong><span>枚举经典斗地主完整牌型与不出</span></div>
          </li>
          <li>
            <i>3</i>
            <div>
              <strong>{{ openAiEnabled ? '大模型推理' : '端侧推理' }}</strong>
              <span>{{
                openAiEnabled ? '通过服务端调用OpenAI兼容Chat Completions' : '按地主、上家、下家加载对应ONNX'
              }}</span>
            </div>
          </li>
          <li>
            <i>4</i>
            <div><strong>结果执行</strong><span>提交最高Q值动作并等待服务器确认</span></div>
          </li>
        </ol>
      </section>

      <section class="ai-recent-card">
        <div class="ai-section-heading">
          <div>
            <span>RECENT ACTIVITY</span>
            <h2>最近决策</h2>
          </div>
          <button type="button" @click="emit('logs')">完整日志</button>
        </div>
        <div class="ai-recent-list">
          <article v-for="entry in recentDecisions" :key="entry.id">
            <time>{{ time(entry.at) }}</time>
            <div>
              <strong>{{ entry.message }}</strong
              ><small>{{ entry.phase }}</small>
            </div>
            <b v-if="entry.detail?.selectedQ !== undefined && entry.detail?.selectedQ !== null">
              Q {{ Number(entry.detail.selectedQ).toFixed(3) }}
            </b>
          </article>
          <p v-if="!recentDecisions.length">启动托管后将在这里显示最近决策</p>
        </div>
      </section>
    </div>
  </section>
</template>
