<template>
  <div class="page-card">
    <div class="toolbar">
      <el-date-picker
        v-model="serveDate"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="供餐日"
        :clearable="false"
        @change="load"
      />
      <span class="hint">待办数字不限供餐日（含往期漏单，资金两项同理），当日数据按所选供餐日统计</span>
      <div class="toolbar-right">
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="block-title">需要你处理</div>
    <div class="cards" v-loading="loading">
      <div
        v-for="card in todoCards"
        :key="card.key"
        class="card"
        :class="{ 'card-zero': card.value === 0, 'card-clickable': card.to }"
        @click="card.to && $router.push(card.to)"
      >
        <div class="card-num">{{ card.value }}</div>
        <div class="card-label">{{ card.label }}</div>
        <div class="card-desc">{{ card.desc }}</div>
      </div>
    </div>

    <div class="block-title">当日概览 · {{ data.serveDate || '—' }}</div>
    <div class="cards">
      <div class="card card-plain">
        <div class="card-num">{{ data.dayOrderCount ?? 0 }}</div>
        <div class="card-label">订单总数</div>
        <div class="card-desc">该供餐日全部状态</div>
      </div>
      <div class="card card-plain">
        <div class="card-num">¥{{ yuan(data.dayAmount) }}</div>
        <div class="card-label">已确认收款</div>
        <div class="card-desc">只算已确认收款，不含「用户声称已付款」</div>
      </div>
      <div class="card card-plain">
        <div class="card-num">{{ data.pendingPayment ?? 0 }}</div>
        <div class="card-label">待核对 / 待退款</div>
        <div class="card-desc">需人工确认收款或标记退款</div>
      </div>
      <div class="card card-plain">
        <div class="card-num">{{ data.publishedCombos ?? 0 }}</div>
        <div class="card-label">已发布盒饭</div>
        <div class="card-desc">为 0 说明这天还没发菜单</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import http from '../api';

const loading = ref(false);
const data = ref({});
const serveDate = ref(tomorrow());

function tomorrow() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

function yuan(fen) {
  return ((Number(fen || 0)) / 100).toFixed(2);
}

const todoCards = computed(() => [
  {
    key: 'accept',
    label: '待接单',
    value: data.value.pendingAccept ?? 0,
    desc: '点进去可批量接单',
    to: '/order',
  },
  {
    key: 'cancel',
    label: '待处理取消申请',
    value: data.value.pendingCancel ?? 0,
    desc: '用户提交了退订，等你批',
    to: '/order?cancelRequested=1',
  },
  {
    key: 'audit',
    label: '待审核用户',
    value: data.value.pendingAudit ?? 0,
    desc: '未通过审核进不了下单页',
    to: '/user',
  },
  {
    key: 'address',
    label: '待确认地址',
    value: data.value.pendingAddress ?? 0,
    desc: '未确认的地址用户选不到',
    to: '/address',
  },
  // 资金类两项（v1.53 / F-J09）：与上面四项同口径不限供餐日 ——
  // 钱的事不能只看今天，上个月少核对一笔，账就永远差一笔
  {
    key: 'check',
    label: '待核对收款',
    value: data.value.pendingCheck ?? 0,
    desc: '用户说付了，等你核对到账',
    to: '/order?payStatus=claimed',
  },
  {
    key: 'refund',
    label: '待退款',
    value: data.value.pendingRefund ?? 0,
    desc: '钱已收到但要退，还没退完',
    to: '/order?payStatus=refunding',
  },
  // 第三件资金事（v3.50 / F-F07）：餐已经给了、钱还没到。
  // 单独一份清单而不是按供餐日筛：要捞的正是往期漏收的单
  {
    key: 'collect',
    label: '待收款',
    value: data.value.pendingCollect ?? 0,
    desc: '已出餐/已完成但钱没到，按欠得最久排',
    to: '/collect',
  },
]);

function load() {
  loading.value = true;
  http
    .get('/admin/dashboard', { params: { serveDate: serveDate.value } })
    .then((res) => {
      data.value = res || {};
      // 后端可能纠正为默认日期，回显保持一致
      if (res && res.serveDate) {
        serveDate.value = res.serveDate;
      }
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

onMounted(load);
</script>

<style scoped>
.hint {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}

.block-title {
  margin: 20px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 14px;
}

.card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px 18px;
  background: #fff;
  transition: box-shadow 0.2s, border-color 0.2s;
}

.card-clickable {
  cursor: pointer;
}

.card-clickable:hover {
  border-color: #ffb454;
  box-shadow: 0 2px 10px rgba(255, 159, 28, 0.15);
}

.card-zero .card-num {
  color: #c0c4cc;
}

.card-num {
  font-size: 28px;
  font-weight: 700;
  color: #ff9f1c;
  line-height: 1.2;
}

.card-plain .card-num {
  color: #303133;
}

.card-label {
  margin-top: 6px;
  font-size: 14px;
  color: #303133;
}

.card-desc {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
