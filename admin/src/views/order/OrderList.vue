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
          <el-descriptions-item label="收款方式">{{ detail.order.payMethodLabel || '未申报' }}</el-descriptions-item>
          <el-descriptions-item label="取餐码">{{ detail.order.pickupCode || '—' }}</el-descriptions-item>
          <el-descriptions-item label="取餐时段">{{ detail.order.pickupSlot || '—' }}</el-descriptions-item>
          <el-descriptions-item label="取餐方式" :span="2">
            {{ deliveryTypeLabel(detail.order.deliveryType) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.order.address" label="收货信息" :span="2">
            {{ detail.order.address.contactName }} {{ detail.order.address.phone || '未留电话' }}<br />
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

        <!-- 状态流转：订单状态线 / 收款状态线 / 事件合成一条时间轴，最早在上，最新一条高亮 -->
        <div class="timeline">
          <div class="timeline-title">状态流转</div>
          <div v-if="logError" class="timeline-empty">记录加载失败，刷新后重试</div>
          <div v-else-if="logs.length === 0" class="timeline-empty">暂无记录</div>
          <el-timeline v-else>
            <el-timeline-item
              v-for="(item, index) in logs"
              :key="item.id"
              :type="index === logs.length - 1 ? 'primary' : ''"
              :hollow="index !== logs.length - 1"
              :timestamp="formatLogTime(item.createdAt)"
              placement="top"
            >
              <div class="log-item" :class="{ fail: item.action === 'event.notify_fail' }">
                <div class="log-head">
                  <span class="log-action">{{ item.actionLabel }}</span>
                  <template v-if="item.fromStatusLabel || item.toStatusLabel">
                    <el-tag v-if="item.fromStatusLabel" size="small" effect="plain" :type="item.fromStatusTag || 'info'">
                      {{ item.fromStatusLabel }}
                    </el-tag>
                    <span class="log-arrow">→</span>
                    <el-tag v-if="item.toStatusLabel" size="small" :type="item.toStatusTag || 'info'">
                      {{ item.toStatusLabel }}
                    </el-tag>
                  </template>
                  <!-- 用户申报与商家确认各一条，两个标签并排就是「说的是微信、收的是现金」 -->
                  <el-tag v-if="item.payMethodLabel" size="small" effect="plain" type="warning">
                    {{ item.payMethodLabel }}
                  </el-tag>
                </div>
                <div v-if="item.remark" class="log-remark">{{ item.remark }}</div>
                <div class="log-meta">{{ item.operatorName || item.operatorId }} · {{ item.sourceLabel }}</div>
              </div>
            </el-timeline-item>
          </el-timeline>
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

    <!--
      确认收款前选一次收款方式（需求 4.10 / N-33）。
      默认带出用户申报的值、可改；用户没申报（现金单没点按钮）则必须自己选。
      这里与小程序商家端共用一个接口，服务端已把 confirm 的 payMethod 设为必填。

      「用户申报过」的判据是 payStatus = claimed，不能只看 payMethod 有没有值 ——
      老单 / 脏值（如早期版本预置的 offline_transfer）也会带着一个值，
      那不是用户选的，问「也是吗」没有意义。
    -->
    <el-dialog v-model="payDialog" title="确认收款" width="380px">
      <div class="pay-tip">
        <template v-if="userClaimedMethod">
          用户申报的付款方式是「{{ dict.label('pay_method', userClaimedMethod, userClaimedMethod) }}」，实际收到的也是吗？
        </template>
        <template v-else>用户没有申报付款方式，请选择实际收到的。</template>
      </div>
      <el-radio-group v-model="payMethodValue">
        <el-radio v-for="item in payMethodOptions" :key="item.value" :label="item.value">
          {{ item.label }}
        </el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="payDialog = false">取消</el-button>
        <el-button type="primary" :loading="paySubmitting" @click="submitPay">确定</el-button>
      </template>
    </el-dialog>

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

/** 确认收款的收款方式弹窗：默认带 user 申报值，用户没申报就留空、必须自己选 */
const payDialog = ref(false);
const payMethodValue = ref('');
const paySubmitting = ref(false);
/** 用户申报过的方式；空串表示没申报（现金单用户不点按钮就是这种） */
const userClaimedMethod = ref('');

/** 字典没加载出来时的兜底：初始三项，与服务端 PayMethod 常量一致 */
const PAY_METHOD_FALLBACK = [
  { value: 'wechat', label: '微信' },
  { value: 'alipay', label: '支付宝' },
  { value: 'cash', label: '现金' },
];

/** 收款方式选项：商家可在「数据字典」里增删改，前端不写死，停用的项不会再出现 */
const payMethodOptions = computed(() => dict.options('pay_method', PAY_METHOD_FALLBACK));
/** 状态流转记录：与详情分开加载，记录挂了不影响详情与操作按钮 */
const logs = ref([]);
const logError = ref(false);

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
  const orderId = row.order.orderId;
  http
    .get('/admin/order/detail', { params: { orderId } })
    .then((result) => {
      detail.value = result;
      drawer.value = true;
      loadLogs(orderId);
    })
    .catch(() => {});
}

function loadLogs(orderId) {
  logError.value = false;
  http
    .get('/admin/order/status-log', { params: { orderId } })
    .then((rows) => {
      logs.value = Array.isArray(rows) ? rows : [];
    })
    .catch(() => {
      logs.value = [];
      logError.value = true;
    });
}

/**
 * 时间轴时间到秒。后端按毫秒排序，显示只到秒：接单后立刻出餐的两条，
 * 秒能看出先后就够了，多三位毫秒纯属噪音
 */
function formatLogTime(value) {
  if (!value) {
    return '';
  }
  const text = String(value).replace('T', ' ');
  return text.length >= 19 ? text.slice(5, 19) : text;
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
        ElMessageBox.alert(result.warnings.join('\n'), '已接单但需注意', { type: 'warning' })
          // 点 × 关闭提示框会 reject，需接住避免全局兜底误报"未知错误"
          .catch(() => {});
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
        ElMessageBox.alert(result.warnings.join('\n'), '需要注意', { type: 'warning' })
          // 同上：点 × 关闭会 reject，需接住避免全局兜底误报
          .catch(() => {});
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
  // 确认收款必须先选一次方式：现金单用户不点按钮，不补选就永远没有方式值
  if (action === 'confirm') {
    const order = row.order;
    // 只有「用户声称已付款」才有申报值可带出；未收款单上残留的旧值不算
    userClaimedMethod.value =
      order.payStatus === 'claimed' && order.payMethod ? order.payMethod : '';
    // 申报的方式若已被停用 / 删除，不预选（提交会被服务端拒掉），让商家自己重选
    payMethodValue.value = payMethodOptions.value.some(
      (item) => item.value === userClaimedMethod.value
    )
      ? userClaimedMethod.value
      : '';
    payDialog.value = true;
    return;
  }
  const labelMap = { revert: '回退收款', refund: '标记已退款' };
  ElMessageBox.confirm('确定' + labelMap[action] + '？', labelMap[action])
    .then(() => {
      // 回退走 payment 接口靠 action 区分；退款是独立接口
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

/** 确认收款：带方式提交，缺值前端先拦一道（服务端同样校验，绕不过去） */
function submitPay() {
  if (!payMethodValue.value) {
    ElMessage.warning('请选择收款方式');
    return;
  }
  paySubmitting.value = true;
  http
    .post('/admin/order/payment', {
      orderId: detail.value.order.orderId,
      action: 'confirm',
      payMethod: payMethodValue.value,
    })
    .then(() => {
      ElMessage.success('已确认收款');
      payDialog.value = false;
      refreshDetail();
    })
    .catch(() => {})
    .finally(() => {
      paySubmitting.value = false;
    });
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

.pay-tip {
  margin-bottom: 12px;
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
}

.timeline {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #eef0f3;
}

.timeline-title {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  margin-bottom: 10px;
}

.timeline-empty {
  font-size: 13px;
  color: #9ca3af;
  padding: 2px 0 6px;
}

.log-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 13px;
}

.log-action {
  font-weight: 600;
  color: #111827;
}

/* 推送失败整条标红：时间轴一屏里要一眼看出「这条没发出去」，不是流水账 */
.log-item.fail .log-action,
.log-item.fail .log-remark,
.log-item.fail .log-meta {
  color: #f56c6c;
}

.log-arrow {
  color: #9ca3af;
}

.log-remark {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7280;
  word-break: break-all;
}

.log-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #9ca3af;
}
</style>
