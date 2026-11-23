<template>
  <div class="page-card">
    <div class="toolbar">
      <el-button @click="onReadAll">全部标为已读</el-button>
      <el-button @click="load">刷新</el-button>
      <span class="hint">
        只显示发给商家的消息（新订单、取消申请、订单取消）。全店共用一份收件箱，
        谁点了已读会记下来。保留 90 天。
      </span>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      size="small"
      class="table"
      style="width: 100%"
      :row-class-name="rowClass"
      @row-click="onOpen"
    >
      <el-table-column label="" width="44">
        <template #default="{ row }">
          <span class="dot" :class="{ on: row.unread }" />
        </template>
      </el-table-column>
      <el-table-column label="时间" width="150">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="typeOf(row.msgType).tag">
            {{ typeOf(row.msgType).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" width="200" />
      <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
      <el-table-column label="已读" width="132">
        <template #default="{ row }">
          <span v-if="row.unread" class="muted">未读</span>
          <template v-else>
            <div class="reader">{{ row.readByName || '—' }}</div>
            <div class="sub">{{ shortTime(row.readAt) }}</div>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="订单" width="96">
        <template #default="{ row }">
          <span v-if="row.orderId" class="link">查看订单</span>
          <span v-else class="muted">—</span>
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
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import http from '../../api';

const router = useRouter();

/**
 * 类型 → 中文名与标签配色，口径与小程序消息中心一致。
 *
 * 未知类型不吞掉：直接把 code 当标签文字显示，将来后端加了类型也不会变成空白。
 */
const TYPES = {
  newOrder: { label: '新订单', tag: 'warning' },
  cancelRequest: { label: '取消申请', tag: 'warning' },
  cancelled: { label: '订单取消', tag: 'info' },
};

const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);

function typeOf(code) {
  return TYPES[code] || { label: code || '通知', tag: 'info' };
}

function rowClass({ row }) {
  return row.unread ? 'is-unread' : '';
}

/** 后端给的是 LocalDateTime，形如 2026-11-23T13:05:00，去掉 T 截到秒 */
function formatTime(value) {
  return (value || '').replace('T', ' ').slice(0, 19);
}

/** 已读时间只要到分钟：这一列是「谁什么时候点的」，秒没有意义 */
function shortTime(value) {
  return (value || '').replace('T', ' ').slice(5, 16);
}

function load() {
  loading.value = true;
  http
    .get('/message/list', {
      params: { side: 'merchant', page: page.value, pageSize },
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

/**
 * 点一行：标已读 → 跳订单管理并带上 orderId。
 *
 * 不等标已读回来再跳：要看的是那笔单，多等一个往返没必要。
 * 没有关联订单时留在本页，把列表刷一遍让「谁读的」显示成真实值。
 */
function onOpen(row) {
  if (!row) {
    return;
  }
  const mark = row.unread
    ? http.post('/message/' + row.id + '/read').catch(() => {})
    : Promise.resolve();
  if (!row.orderId) {
    ElMessage.warning('这条消息没有关联订单');
    mark.then(load);
    return;
  }
  router.push({ path: '/order', query: { orderId: String(row.orderId) } });
}

/**
 * 全部标为已读。
 *
 * 清完重新拉一次，不本地置灰：已读人是谁只有服务端知道，
 * 本地改会让「已读」列显示成空名字加空时间。
 */
function onReadAll() {
  http
    .post('/message/read-all', null, { params: { side: 'merchant' } })
    .then((count) => {
      if (!count) {
        ElMessage.info('没有未读消息');
        return;
      }
      ElMessage.success('已全部标为已读');
      load();
    })
    .catch(() => {});
}

onMounted(load);
</script>

<style scoped>
.hint {
  font-size: 12px;
  color: #8792a2;
}

/* 整行可点，指针形状是唯一的暗示，不加按钮占宽度 */
.table :deep(.el-table__row) {
  cursor: pointer;
}

.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #e3e6eb;
  vertical-align: middle;
}

.dot.on {
  background: #e67e22;
}

.link {
  color: #409eff;
}

.muted {
  color: #c0c4cc;
}

/* 已读人比时间重一档：一眼先看「谁处理的」，时间只在需要追的时候看 */
.reader {
  color: #374151;
}

.sub {
  font-size: 12px;
  color: #9aa4b2;
}
</style>
