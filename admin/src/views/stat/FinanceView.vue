<template>
  <div class="page-card">
    <div class="toolbar">
      <el-date-picker
        v-model="serveDate"
        type="date"
        value-format="YYYY-MM-DD"
        :clearable="false"
        style="width: 150px"
        @change="load"
      />
      <span class="muted">共 {{ summary.orderCount || 0 }} 单。数字由服务端按订单状态与收款状态汇总</span>
      <div class="toolbar-right">
        <el-button v-if="auth.has('prep.export')" type="primary" @click="onExport">导出 Excel</el-button>
        <span v-else class="muted">当前角色无导出权限</span>
      </div>
    </div>

    <div class="buckets">
      <div
        v-for="bucket in BUCKETS"
        :key="bucket.key"
        class="bucket"
        :class="{ active: activeBucket === bucket.key, money: bucket.key === 'receivable' }"
        @click="onBucket(bucket)"
      >
        <div class="label">{{ bucket.label }}</div>
        <div class="amount">¥{{ yuan((summary[bucket.key] || {}).amount) }}</div>
        <div class="count">{{ (summary[bucket.key] || {}).count || 0 }} 单</div>
        <div class="hint">{{ bucket.hint }}</div>
      </div>
    </div>

    <el-table v-loading="detailLoading" :data="detail" border size="small" style="width: 100%">
      <el-table-column prop="orderNo" label="订单号" width="150" />
      <el-table-column label="顾客" width="120">
        <template #default="{ row }">{{ row.contactName || '自取顾客' }}</template>
      </el-table-column>
      <el-table-column label="取餐方式" width="90">
        <template #default="{ row }">{{ row.deliveryType === 'delivery' ? '配送' : '自取' }}</template>
      </el-table-column>
      <el-table-column prop="statusLabel" label="订单状态" width="100" />
      <el-table-column prop="payStatusLabel" label="收款状态" width="110" />
      <el-table-column prop="items" label="内容" min-width="220" />
      <el-table-column label="金额" width="100" align="right">
        <template #default="{ row }"><span class="price">¥{{ yuan(row.totalAmount) }}</span></template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!detailLoading && detail.length === 0" description="点上方任意一栏查看对应订单" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import http, { download } from '../../api';
import { useAuthStore } from '../../stores/auth';

const auth = useAuthStore();

const BUCKETS = [
  { key: 'receivable', label: '应收', hint: '全部有效订单' },
  { key: 'paid', label: '已收', hint: '已确认收款' },
  { key: 'unpaid', label: '未收', hint: '顾客未点已转账' },
  { key: 'claimed', label: '待确认', hint: '顾客称已转账' },
  { key: 'refunding', label: '待退款', hint: '取消或拒单' },
  { key: 'refunded', label: '已退款', hint: '退款已完成' },
];

const serveDate = ref('');
const summary = ref({});
const detail = ref([]);
const detailLoading = ref(false);
const activeBucket = ref('receivable');

function yuan(cents) {
  return (Number(cents || 0) / 100).toFixed(2);
}

function tomorrow() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

function load() {
  http
    .get('/admin/finance', { params: { serveDate: serveDate.value } })
    .then((result) => {
      summary.value = result || {};
      onBucket(BUCKETS.filter((b) => b.key === activeBucket.value)[0]);
    })
    .catch(() => {});
}

function onBucket(bucket) {
  if (!bucket) {
    return;
  }
  activeBucket.value = bucket.key;
  detailLoading.value = true;
  http
    .get('/admin/finance/detail', {
      params: { serveDate: serveDate.value, bucket: bucket.key },
    })
    .then((rows) => {
      detail.value = rows || [];
    })
    .catch(() => {})
    .finally(() => {
      detailLoading.value = false;
    });
}

function onExport() {
  download('/admin/finance/export', { serveDate: serveDate.value }, '对账-' + serveDate.value + '.xlsx')
    .then(() => ElMessage.success('已开始下载'))
    .catch(() => {});
}

onMounted(() => {
  serveDate.value = tomorrow();
  load();
});
</script>

<style scoped>
.buckets {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.bucket {
  flex: 1 1 150px;
  border: 1px solid #eef0f3;
  border-radius: 8px;
  padding: 12px 14px;
  cursor: pointer;
  background: #fff;
  transition: all 0.15s;
}

.bucket:hover {
  border-color: #c9d2dd;
}

.bucket.active {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.12);
}

.bucket.money {
  background: #fff8f0;
}

.label {
  font-size: 13px;
  color: #6b7280;
  margin-bottom: 6px;
}

.amount {
  font-size: 20px;
  font-weight: 600;
  color: #1f2a37;
}

.count {
  font-size: 12px;
  color: #c2410c;
  margin-top: 4px;
}

.hint {
  font-size: 11px;
  color: #b0b6bf;
  margin-top: 4px;
}
</style>
