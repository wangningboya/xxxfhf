<template>
  <div class="page-card">
    <div class="toolbar">
      <span class="hint">
        餐已经给了、钱还没到的单：已出餐 / 已完成 + 未收款 / 声称已付款。**不限供餐日**，欠得最久的排最前
      </span>
      <div class="toolbar-right">
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="canExport" @click="onExport">导出 Excel</el-button>
      </div>
    </div>

    <!--
      这张表和「对账视图」故意分开（需求 4.21）：对账回答「这一天收了多少」，
      催收回答「还有哪些单的钱没到手」。混进去，六个数会被这张跨天的单子带得对不上。
    -->
    <el-table v-loading="loading" :data="rows" border stripe size="small">
      <el-table-column label="订单号" width="150">
        <template #default="{ row }">
          <el-link type="primary" @click="openDetail(row)">{{ row.order.orderNo }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="顾客" width="120">
        <template #default="{ row }">{{ row.userNickName || row.order.address?.contactName || '—' }}</template>
      </el-table-column>
      <el-table-column label="供餐日" width="110">
        <template #default="{ row }">{{ row.order.serveDate }}</template>
      </el-table-column>
      <el-table-column label="金额" width="90" align="right">
        <template #default="{ row }">¥{{ yuan(row.order.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="取送" width="80">
        <template #default="{ row }">
          {{ row.order.deliveryType === 'delivery' ? '配送' : '自取' }}
        </template>
      </el-table-column>
      <el-table-column label="收款状态" width="130">
        <template #default="{ row }">
          <el-tag :type="payTag(row.order.payStatus)" size="small">
            {{ row.order.payStatusLabel }}
          </el-tag>
        </template>
      </el-table-column>
      <!--
        逾期天数 = 供餐日到今天。当天出的餐算 0 天（还没到收摊的时候），
        不标红；超过 1 天开始标黄，超过 3 天标红 —— 现金单隔天就该清了。
      -->
      <el-table-column label="逾期" width="90" align="center">
        <template #default="{ row }">
          <span :class="overdueClass(row.order.serveDate)">
            {{ overdueText(row.order.serveDate) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.order.remark || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="canPay"
            type="primary"
            link
            @click="openPay(row)"
          >确认收款</el-button>
          <el-button link @click="openDetail(row)">查看订单</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty">没有待收款的单 —— 已出餐的单都收到钱了</div>
      </template>
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

    <!--
      确认收款必须再选一次方式（需求 4.10）：现金单顾客不会点「我已完成付款」，
      这里不补选，这单就永远没有方式值，事后对账无法判断钱走的哪条路。
    -->
    <el-dialog v-model="payDialog" title="确认收款" width="400px">
      <div class="pay-tip">
        <template v-if="claimedMethod">
          顾客申报的付款方式是「{{ dict.label('pay_method', claimedMethod, claimedMethod) }}」，实际收到的也是吗？
        </template>
        <template v-else>顾客没有申报付款方式，请选择实际收到的。</template>
      </div>
      <el-radio-group v-model="payMethod">
        <el-radio v-for="item in payMethodOptions" :key="item.value" :label="item.value">
          {{ item.label }}
        </el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="payDialog = false">取消</el-button>
        <el-button type="primary" :loading="paying" @click="submitPay">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情沿用订单管理页：带着订单 ID 跳过去，同一个抽屉、同一套时间轴 -->
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import http, { download } from '../../api';
import { useDictStore } from '../../stores/dict';
import { useAuthStore } from '../../stores/auth';

const dict = useDictStore();
const auth = useAuthStore();
const router = useRouter();

const loading = ref(false);
const rows = ref([]);
const page = ref(1);
const pageSize = 20;
const total = ref(0);

const payDialog = ref(false);
const paying = ref(false);
const payTargetId = ref(null);
const payMethod = ref('');
const claimedMethod = ref('');
/** 权限只决定按钮显不显示；真正的拦截在服务端 */
const canPay = computed(() => auth.has('order.payment'));
const canExport = computed(() => auth.has('prep.export'));

/** 字典没加载出来时的兜底：与服务端 PayMethod 常量一致 */
const PAY_METHOD_FALLBACK = [
  { value: 'wechat', label: '微信' },
  { value: 'alipay', label: '支付宝' },
  { value: 'cash', label: '现金' },
];
const payMethodOptions = computed(() => dict.options('pay_method', PAY_METHOD_FALLBACK));

function yuan(fen) {
  return ((Number(fen || 0)) / 100).toFixed(2);
}

function today() {
  const d = new Date();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

/** 逾期天数：日期串直接比，避免时区把「今天」算成昨天 */
function overdueDays(serveDate) {
  if (!serveDate) {
    return 0;
  }
  const one = 24 * 60 * 60 * 1000;
  const a = new Date(serveDate + 'T00:00:00');
  const b = new Date(today() + 'T00:00:00');
  return Math.max(0, Math.round((b - a) / one));
}

function overdueText(serveDate) {
  const days = overdueDays(serveDate);
  return days === 0 ? '当天' : days + ' 天';
}

function overdueClass(serveDate) {
  const days = overdueDays(serveDate);
  if (days >= 3) {
    return 'overdue-red';
  }
  return days >= 1 ? 'overdue-warn' : 'overdue-ok';
}

function payTag(status) {
  return status === 'claimed' ? 'warning' : 'info';
}

function load() {
  loading.value = true;
  http
    .get('/admin/order/collect', { params: { page: page.value, pageSize } })
    .then((result) => {
      rows.value = (result && result.list) || [];
      total.value = (result && result.total) || 0;
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

function openPay(row) {
  payTargetId.value = row.order.orderId;
  // 只有「用户声称已付款」才有申报值可带出；未收款的单上没有申报值
  claimedMethod.value =
    row.order.payStatus === 'claimed' && row.order.payMethod ? row.order.payMethod : '';
  payMethod.value = payMethodOptions.value.some((item) => item.value === claimedMethod.value)
    ? claimedMethod.value
    : '';
  payDialog.value = true;
}

function submitPay() {
  if (!payMethod.value) {
    ElMessage.warning('请选择收款方式');
    return;
  }
  paying.value = true;
  http
    .post('/admin/order/payment', {
      orderId: payTargetId.value,
      action: 'confirm',
      payMethod: payMethod.value,
    })
    .then(() => {
      ElMessage.success('已确认收款');
      payDialog.value = false;
      load();
    })
    .catch(() => {})
    .finally(() => {
      paying.value = false;
    });
}

function openDetail(row) {
  // 带 orderId 跳订单页：那边会直接打开详情抽屉，不要求这单在当页筛选结果里
  router.push({ path: '/order', query: { orderId: row.order.orderId } });
}

function onExport() {
  download('/admin/order/collect/export', {}, '待收款-' + today() + '.xlsx');
}

onMounted(load);
</script>

<style scoped>
.hint {
  font-size: 12px;
  color: #909399;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.toolbar-right {
  display: flex;
  gap: 8px;
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.pay-tip {
  margin-bottom: 12px;
  color: #606266;
  font-size: 13px;
}
.empty {
  padding: 24px 0;
  color: #909399;
}
.overdue-ok {
  color: #909399;
}
.overdue-warn {
  color: #e6a23c;
  font-weight: 600;
}
.overdue-red {
  color: #f56c6c;
  font-weight: 600;
}
</style>
