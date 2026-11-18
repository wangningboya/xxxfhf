<template>
  <div class="page-card">
    <div class="toolbar">
      <el-date-picker
        v-model="serveDate"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="供餐日"
        :clearable="false"
        style="width: 150px"
        @change="reload"
      />
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px" @change="reload">
        <el-option
          v-for="item in statusOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="昵称或手机号"
        clearable
        style="width: 180px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-checkbox v-model="cancelOnly" @change="reload">只看有取消申请</el-checkbox>
      <el-button @click="reload">查询</el-button>
      <div class="toolbar-right">
        <el-button v-if="auth.has('order.accept')" @click="proxyVisible = true">代客下单</el-button>
        <el-button
          v-if="auth.has('order.accept')"
          type="primary"
          :disabled="selected.length === 0"
          :loading="accepting"
          @click="onBatchAccept"
        >
          批量接单（{{ selected.length }}）
        </el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      size="small"
      style="width: 100%"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="44" :selectable="canSelect" />
      <el-table-column label="订单号" width="150">
        <template #default="{ row }">
          <div>{{ row.order.orderNo }}</div>
          <el-tag v-if="row.order.stockReleased" type="danger" size="small">份数已释放</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="顾客" width="130">
        <template #default="{ row }">
          <div>{{ row.userNickName || '未填昵称' }}</div>
          <div class="muted">{{ row.userPhone || '无手机号' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="盒饭" min-width="200">
        <template #default="{ row }">
          <div v-for="item in row.order.items" :key="item.comboId + item.name">
            {{ item.name }} × {{ item.qty }}
            <span v-if="item.specs && item.specs.length" class="muted">
              （{{ item.specs.map((s) => s.optionName).join('/') }}）
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="取餐方式" width="110">
        <template #default="{ row }">
          {{ deliveryTypeLabel(row.order.deliveryType) }}
          <div class="muted" v-if="row.order.deliveryType === 'delivery'">
            {{ (row.order.region && row.order.region.name) || '' }}
          </div>
          <div class="muted" v-else>{{ row.order.pickupSlot || '' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="100" align="right">
        <template #default="{ row }">
          <span class="price">¥{{ yuan(row.order.totalAmount) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="订单状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.order)" size="small">
            {{ row.order.statusLabel }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="收款" width="120">
        <template #default="{ row }">
          <el-tag :type="payType(row.order)" size="small" effect="plain">
            {{ row.order.payStatusLabel }}
          </el-tag>
          <div v-if="row.order.cancelRequested" class="muted">有取消申请</div>
        </template>
      </el-table-column>
      <el-table-column label="取餐码" width="90" align="center">
        <template #default="{ row }">
          <span v-if="row.order.pickupCode" class="code">{{ row.order.pickupCode }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.order.status === 'pending_accept' && auth.has('order.accept')"
            link
            type="primary"
            @click="onAccept(row)"
          >
            接单
          </el-button>
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer" title="订单详情" size="520px">
      <div v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="订单号">{{ detail.order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="供餐日">{{ detail.order.serveDate }}</el-descriptions-item>
          <el-descriptions-item label="顾客">{{ detail.userNickName || '未填昵称' }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ detail.userPhone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="订单状态">{{ detail.order.statusLabel }}</el-descriptions-item>
          <el-descriptions-item label="收款状态">{{ detail.order.payStatusLabel }}</el-descriptions-item>
          <el-descriptions-item label="取餐码">{{ detail.order.pickupCode || '—' }}</el-descriptions-item>
          <el-descriptions-item label="取餐时段">{{ detail.order.pickupSlot || '—' }}</el-descriptions-item>
          <el-descriptions-item label="取餐方式" :span="2">
            {{ deliveryTypeLabel(detail.order.deliveryType) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.order.address" label="收货信息" :span="2">
            {{ detail.order.address.contactName }} {{ detail.order.address.phone }}<br />
            {{ detail.order.address.regionName }} {{ detail.order.address.detail }}
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.order.remark || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.order.rejectReason" label="拒单原因" :span="2">
            {{ detail.order.rejectReason }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.order.cancelRequest" label="取消申请" :span="2">
            用户原因：{{ detail.order.cancelRequest.reason || '未填原因' }}
            <div class="muted">
              提交于 {{ detail.order.cancelRequest.appliedAt || '—' }}
              <template v-if="detail.order.cancelRequest.result">
                · 处理结果：{{ cancelResultLabel(detail.order.cancelRequest.result) }}
              </template>
              <template v-if="detail.order.cancelRequest.handledAt">
                · 处理于 {{ detail.order.cancelRequest.handledAt }}
              </template>
              <template v-if="detail.order.cancelRequest.remark">
                · 处理备注：{{ detail.order.cancelRequest.remark }}
              </template>
            </div>
          </el-descriptions-item>
        </el-descriptions>

        <el-table :data="detail.order.items" size="small" border style="margin-top: 14px">
          <el-table-column prop="name" label="盒饭" min-width="140" />
          <el-table-column label="规格" min-width="120">
            <template #default="{ row }">
              {{ (row.specs || []).map((s) => s.optionName).join(' / ') || '—' }}
            </template>
          </el-table-column>
          <el-table-column prop="qty" label="份数" width="70" align="center" />
          <el-table-column label="小计" width="90" align="right">
            <template #default="{ row }">¥{{ yuan(row.price * row.qty) }}</template>
          </el-table-column>
        </el-table>

        <div class="sums">
          <div>商品 ¥{{ yuan(detail.order.goodsAmount) }}</div>
          <div>规格 ¥{{ yuan(detail.order.specAmount) }}</div>
          <div>配送费 ¥{{ yuan(detail.order.deliveryFee) }}</div>
          <div class="total">合计 ¥{{ yuan(detail.order.totalAmount) }}</div>
        </div>

        <div class="actions">
          <el-button
            v-if="detail.order.status === 'pending_accept' && auth.has('order.accept')"
            type="primary"
            @click="onAccept(detail)"
          >
            接单
          </el-button>
          <el-button
            v-if="detail.order.status === 'pending_accept' && auth.has('order.accept')"
            type="danger"
            plain
            @click="onReject(detail)"
          >
            拒单
          </el-button>
          <el-button
            v-if="nextStatus(detail.order.status) && auth.has('order.accept')"
            type="primary"
            plain
            @click="onAdvance(detail)"
          >
            {{ nextLabel(detail.order.status, detail.order.deliveryType) }}
          </el-button>
          <el-button
            v-if="detail.order.payStatus !== 'paid' && detail.order.payStatus !== 'refunded' && auth.has('order.payment')"
            type="success"
            plain
            @click="onPayment(detail, 'confirm')"
          >
            确认收款
          </el-button>
          <el-button
            v-if="detail.order.payStatus === 'paid' && auth.has('order.payment')"
            plain
            @click="onPayment(detail, 'revert')"
          >
            回退收款
          </el-button>
          <el-button
            v-if="detail.order.payStatus === 'refunding' && auth.has('order.payment')"
            type="danger"
            plain
            @click="onPayment(detail, 'refund')"
          >
            标记已退款
          </el-button>
          <el-button v-if="detail.order.cancelRequested && auth.has('order.accept')" type="warning" plain @click="onCancelRequest(true)">
            同意取消
          </el-button>
          <el-button v-if="detail.order.cancelRequested && auth.has('order.accept')" plain @click="onCancelRequest(false)">
            驳回取消
          </el-button>
        </div>
      </div>
    </el-drawer>

    <ProxyOrderDialog v-model="proxyVisible" @created="reload" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../../api';
import { useAuthStore } from '../../stores/auth';
import { useDictStore } from '../../stores/dict';
import ProxyOrderDialog from './ProxyOrderDialog.vue';

const auth = useAuthStore();
const dict = useDictStore();
const route = useRoute();

/** 字典没加载出来时的兜底；加载完成后以字典为准（商家可改显示名与顺序） */
const STATUS = [
  { value: 'pending_accept', label: '待接单' },
  { value: 'accepted', label: '已接单' },
  { value: 'ready', label: '待取餐' },
  { value: 'completed', label: '已完成' },
  { value: 'cancelled', label: '已取消' },
  { value: 'rejected', label: '已拒单' },
];

/** 默认查明天的单：供餐日是次日，商家每天处理的就是明天那批 */
function tomorrow() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

const serveDate = ref(tomorrow());
const status = ref('');
const keyword = ref('');
/** 只看有取消申请的单：勾上时不限供餐日，专门用来捞漏处理的退订 */
const cancelOnly = ref(false);
const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);
const accepting = ref(false);
const selected = ref([]);

const drawer = ref(false);
const detail = ref(null);
const proxyVisible = ref(false);

function yuan(cents) {
  const value = Number(cents || 0);
  return (value / 100).toFixed(2);
}

/** 状态筛选选项：字典里停用的项不会出现，顺序按字典 sort */
const statusOptions = computed(() => dict.options('order_status', STATUS));

/**
 * 状态标签配色。优先用服务端随行下发的 statusTag（同一份字典算出来的），
 * 本地字典还没到也能正确上色；两者都没有时落到 info。
 */
function statusType(row) {
  return row.statusTag || dict.tagType('order_status', row.status);
}

function payType(row) {
  return row.payStatusTag || dict.tagType('pay_status', row.payStatus);
}

function deliveryTypeLabel(value) {
  return dict.label('delivery_type', value, value === 'delivery' ? '配送' : '自取');
}

function canSelect(row) {
  return row.order.status === 'pending_accept' && auth.has('order.accept');
}

function load() {
  loading.value = true;
  http
    .get('/admin/order/list', {
      params: {
        // 只看取消申请时不限供餐日：漏处理的那条往往就是往期的，
        // 带着「明天」这个默认日期去筛，等于把要找回的单先排除掉了
        serveDate: cancelOnly.value ? undefined : serveDate.value,
        status: status.value || undefined,
        keyword: keyword.value || undefined,
        cancelRequested: cancelOnly.value ? true : undefined,
        page: page.value,
        pageSize,
      },
    })
    .then((result) => {
      rows.value = (result && result.list) || [];
      total.value = (result && result.total) || 0;
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

function reload() {
  page.value = 1;
  load();
}

function onSelectionChange(selection) {
  selected.value = selection;
}

function openDetail(row) {
  http
    .get('/admin/order/detail', { params: { orderId: row.order.orderId } })
    .then((result) => {
      detail.value = result;
      drawer.value = true;
    })
    .catch(() => {});
}

function refreshDetail() {
  if (detail.value) {
    openDetail(detail.value);
  }
  load();
}

/** 没有「制作中」：一条直线 已接单 → 出餐(ready) → 已完成，配送方式不改变路径 */
function nextStatus(current) {
  if (current === 'accepted') {
    return 'ready';
  }
  if (current === 'ready') {
    return 'completed';
  }
  return '';
}

/** 同一个 ready，自取是「完成订单」、配送是「确认已送达」；出餐那一步同理 */
function nextLabel(current, deliveryType) {
  const delivery = deliveryType === 'delivery';
  if (current === 'accepted') {
    return delivery ? '开始配送' : '出餐待取';
  }
  if (current === 'ready') {
    return delivery ? '确认已送达' : '完成订单';
  }
  return '';
}

function onAdvance(row) {
  const target = nextStatus(row.order.status);
  if (!target) {
    return;
  }
  http
    .post('/admin/order/advance', { orderId: row.order.orderId, status: target })
    .then(() => {
      ElMessage.success('已推进');
      refreshDetail();
    })
    .catch(() => {});
}

function onAccept(row) {
  http
    .post('/admin/order/accept', { orderIds: [row.order.orderId] })
    .then((result) => {
      if (result.warnings && result.warnings.length) {
        ElMessageBox.alert(result.warnings.join('\n'), '已接单但需注意', { type: 'warning' });
      } else {
        ElMessage.success('已接单');
      }
      if (drawer.value) {
        refreshDetail();
      } else {
        load();
      }
    })
    .catch(() => {});
}

function onBatchAccept() {
  const orderIds = selected.value.map((row) => row.order.orderId);
  if (orderIds.length === 0) {
    return;
  }
  accepting.value = true;
  http
    .post('/admin/order/accept', { orderIds })
    .then((result) => {
      const accepted = (result.accepted || []).length;
      const skipped = (result.skipped || []).length;
      ElMessage.success('已接单 ' + accepted + ' 单' + (skipped ? '，跳过 ' + skipped + ' 单' : ''));
      if (result.warnings && result.warnings.length) {
        ElMessageBox.alert(result.warnings.join('\n'), '需要注意', { type: 'warning' });
      }
      load();
    })
    .catch(() => {})
    .finally(() => {
      accepting.value = false;
    });
}

function onReject(row) {
  ElMessageBox.prompt('拒单原因会展示给顾客，请写清楚（必填）', '拒单', {
    inputPlaceholder: '如：明天备料不足，无法供应',
    inputValidator: (value) => (value && value.trim() ? true : '必须填写拒单原因'),
  })
    .then(({ value }) => {
      http
        .post('/admin/order/reject', { orderId: row.order.orderId, reason: value.trim() })
        .then(() => {
          ElMessage.success('已拒单');
          refreshDetail();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

function onPayment(row, action) {
  const labelMap = { confirm: '确认收款', revert: '回退收款', refund: '标记已退款' };
  ElMessageBox.confirm('确定' + labelMap[action] + '？', labelMap[action])
    .then(() => {
      // 确认 / 回退走同一个接口靠 action 区分；退款是独立接口
      const request =
        action === 'refund'
          ? http.post('/admin/order/refund', { orderId: row.order.orderId })
          : http.post('/admin/order/payment', { orderId: row.order.orderId, action });
      request
        .then(() => {
          ElMessage.success('已' + labelMap[action]);
          refreshDetail();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

function onCancelRequest(approve) {
  ElMessageBox.prompt(
    approve ? '同意取消会释放份数并进入退款流程。备注可不填。' : '驳回原因会展示给顾客，建议填写。',
    approve ? '同意取消' : '驳回取消',
    { inputPlaceholder: '备注（可选）' }
  )
    .then(({ value }) => {
      http
        .post('/admin/order/cancel-request', {
          orderId: detail.value.order.orderId,
          approve,
          remark: value || null,
        })
        .then(() => {
          ElMessage.success('已处理');
          refreshDetail();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

function cancelResultLabel(result) {
  return {
    approved: '已同意',
    rejected: '已驳回',
    auto_approved: '超时未处理，系统自动同意',
  }[result] || result;
}

onMounted(() => {
  // 从工作台「待处理取消申请」点进来时带上这个参数，直接筛出要处理的那批
  if (route.query.cancelRequested === '1') {
    cancelOnly.value = true;
  }
  load();
});
</script>

<style scoped>
.code {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 16px;
  letter-spacing: 2px;
  color: #c2410c;
}

.sums {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #eef0f3;
  display: flex;
  gap: 18px;
  font-size: 13px;
  color: #6b7280;
}

.sums .total {
  margin-left: auto;
  font-size: 16px;
  font-weight: 600;
  color: #c2410c;
}

.actions {
  margin-top: 18px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
