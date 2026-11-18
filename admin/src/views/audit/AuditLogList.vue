<template>
  <div class="page-card">
    <div class="toolbar">
      <el-select v-model="tableName" clearable placeholder="全部对象" style="width: 120px" @change="onTableChange">
        <el-option label="地址" value="address" />
        <el-option label="用户" value="user" />
        <el-option label="订单" value="order" />
      </el-select>
      <el-select v-model="action" clearable placeholder="全部动作" style="width: 160px" @change="reload">
        <el-option v-for="item in actionOptions" :key="item.code" :label="item.label" :value="item.code" />
      </el-select>
      <el-input
        v-model="operator"
        placeholder="操作人"
        clearable
        style="width: 140px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-input
        v-model="keyword"
        placeholder="说明 / 对象ID"
        clearable
        style="width: 180px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        style="width: 240px"
        @change="reload"
      />
      <el-button @click="reload">查询</el-button>
      <el-button @click="onReset">重置</el-button>
    </div>

    <div class="hint">
      只记高危动作：地址审核与修改、用户审核与手机号、订单接单与收款退款。按时间倒序，默认每页 20 条。
    </div>

    <el-table v-loading="loading" :data="rows" border size="small" style="width: 100%">
      <el-table-column label="时间" width="150">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="对象" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.tableNameLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="动作" width="130">
        <template #default="{ row }">{{ row.actionLabel }}</template>
      </el-table-column>
      <el-table-column prop="summary" label="说明" min-width="280" />
      <el-table-column label="对象 ID" width="120">
        <template #default="{ row }"><code class="id">{{ row.recordId }}</code></template>
      </el-table-column>
      <el-table-column label="操作人" width="130">
        <template #default="{ row }">
          <span>{{ row.operatorName || row.operatorId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.source === 'task' ? 'info' : 'success'" effect="plain">
            {{ row.sourceLabel }}
          </el-tag>
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
import { computed, onMounted, ref } from 'vue';
import http from '../../api';

const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);

const tableName = ref('');
const action = ref('');
const operator = ref('');
const keyword = ref('');
const dateRange = ref([]);

/** 动作下拉由服务端下发（与埋点常量同源），不在这里硬编码一份映射 */
const allActions = ref([]);

const actionOptions = computed(() =>
  tableName.value ? allActions.value.filter((item) => item.tableName === tableName.value) : allActions.value
);

function load() {
  loading.value = true;
  const range = dateRange.value || [];
  http
    .get('/admin/audit-log/list', {
      params: {
        tableName: tableName.value || undefined,
        action: action.value || undefined,
        operator: operator.value || undefined,
        keyword: keyword.value || undefined,
        startDate: range[0] || undefined,
        endDate: range[1] || undefined,
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

/** 切对象类型时把动作清掉：留着上一个域的动作码会筛出空结果 */
function onTableChange() {
  action.value = '';
  reload();
}

function onReset() {
  tableName.value = '';
  action.value = '';
  operator.value = '';
  keyword.value = '';
  dateRange.value = [];
  reload();
}

function formatTime(value) {
  return (value || '').replace('T', ' ').slice(0, 19);
}

onMounted(() => {
  load();
  http
    .get('/admin/audit-log/actions')
    .then((list) => {
      allActions.value = list || [];
    })
    .catch(() => {});
});
</script>

<style scoped>
.hint {
  margin-bottom: 10px;
  font-size: 12px;
  color: #8792a2;
}

/* 对象 ID 是机器串，等宽字体才扫得快 */
.id {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: #6b7280;
}
</style>
