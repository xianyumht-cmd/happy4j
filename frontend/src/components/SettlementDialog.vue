<script setup>
import { computed } from 'vue'
import { settlementOutcome } from '@/ai/settlement.js'
import defaultAvatar from '@/assets/original/default-avatar.png'

const props = defineProps({ table: { type: Object, required: true }, beans: { type: Number, default: 0 } })
const emit = defineEmits(['action', 'close'])
const detailed = computed(() => props.table.settlement?.players?.length === 3)
const results = computed(() => {
  if (detailed.value) {
    return props.table.settlement.players.map((result) => ({
      ...result,
      player: props.table.players?.find((player) => player.seatId === result.seatId),
    }))
  }
  return (props.table.players || []).map((player) => ({
    seatId: player.seatId,
    beanChange: 0,
    beans: player.beans,
    factor: 0,
    remainingCards: [],
    player,
  }))
})
const localResult = computed(() => results.value.find((result) => result.seatId === props.table.seatId))
const localBalance = computed(() => {
  if (Number(localResult.value?.beans) >= 0) return Number(localResult.value.beans)
  return Number(props.beans) >= 0 ? Number(props.beans) : -1
})
const localBefore = computed(() =>
  localBalance.value >= 0 ? localBalance.value - Number(localResult.value?.beanChange || 0) : -1,
)
const localLandlord = computed(() => props.table.seatId === props.table.landlordSeatId)
const localWon = computed(() => settlementOutcome(props.table))
const verifiedWinnerSeat = computed(() => {
  if (localWon.value === null) return null
  return props.table.settlement.players.find((player) => player.remainingCards.length === 0)?.seatId ?? null
})
const number = (value) => new Intl.NumberFormat('zh-CN').format(Number(value || 0))
const signed = (value) => `${Number(value) > 0 ? '+' : ''}${number(value)}`
const headline = (value) => (Number(value) < 0 ? number(Math.abs(Number(value))) : signed(value))
const balance = (result) => (Number(result.beans) >= 0 ? `${number(result.beans)} 豆` : '同步中')
function avatarFallback(event) {
  if (event.currentTarget.src !== defaultAvatar) event.currentTarget.src = defaultAvatar
}
</script>

<template>
  <div class="modal-backdrop settlement-backdrop">
    <section class="settlement-modal game-modal" :class="{ victory: localWon, defeat: localWon === false }">
      <span class="settlement-rays"></span>
      <div class="settlement-crown">{{ localWon ? 'WIN' : 'ROUND' }}</div>
      <span class="modal-kicker">ROUND COMPLETE</span>
      <h2>{{ localWon === null ? '本局结束' : localWon ? '恭喜获胜' : '再接再厉' }}</h2>
      <p>{{ localLandlord ? '地主阵营' : '农民阵营' }} · 倍率 ×{{ number(table.multiplier) }}</p>
      <div class="settlement-score">
        <small>{{
          detailed ? (localResult?.beanChange >= 0 ? '本局收入' : '本局支出') : '当前欢乐豆'
        }}</small>
        <strong :class="{ negative: localResult?.beanChange < 0 }">
          {{ detailed ? headline(localResult?.beanChange || 0) : number(beans) }} </strong
        ><span>豆</span>
        <em v-if="detailed">本局结果已计入欢乐豆余额</em>
      </div>
      <div v-if="detailed" class="settlement-audit">
        <div>
          <small>结算前</small><b>{{ localBefore >= 0 ? number(localBefore) : '同步中' }}</b>
        </div>
        <i>＋</i>
        <div>
          <small>本局变动</small
          ><b :class="{ negative: localResult?.beanChange < 0 }">{{
            signed(localResult?.beanChange || 0)
          }}</b>
        </div>
        <i>＝</i>
        <div>
          <small>结算后</small><b>{{ localBalance >= 0 ? number(localBalance) : '同步中' }}</b>
        </div>
      </div>
      <div class="settlement-players">
        <div
          v-for="result in results"
          :key="result.seatId"
          :class="{ winner: verifiedWinnerSeat !== null && result.seatId === verifiedWinnerSeat }"
        >
          <div class="settlement-player-head">
            <img :src="result.player?.avatarUrl || defaultAvatar" alt="玩家头像" @error="avatarFallback" />
            <span>{{ result.seatId === table.landlordSeatId ? '地主' : '农民' }}</span>
          </div>
          <strong>{{ result.player?.nickname || `玩家 ${result.seatId + 1}` }}</strong>
          <b :class="{ negative: result.beanChange < 0 }">{{ signed(result.beanChange) }} 豆</b>
          <small>
            余额 {{ balance(result) }} · 剩余
            {{ result.remainingCards?.length ?? result.player?.cardCount ?? 0 }} 张
          </small>
          <em v-if="result.factor">结算系数 ×{{ result.factor }}</em>
        </div>
      </div>
      <div class="settlement-actions">
        <button
          v-if="table.availableActions.includes('CHANGE_TABLE')"
          class="game-button soft"
          type="button"
          @click="emit('action', 'CHANGE_TABLE')"
        >
          换一桌
        </button>
        <button
          v-if="table.availableActions.includes('READY')"
          class="game-button gold"
          type="button"
          @click="emit('action', 'READY')"
        >
          再来一局
        </button>
        <button class="text-button" type="button" @click="emit('action', 'LEAVE_ROOM')">返回大厅</button>
      </div>
    </section>
  </div>
</template>
