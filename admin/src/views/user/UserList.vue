<template>
  <div class="page-card">
    <div class="toolbar">
      <el-select v-model="auditStatus" placeholder="全部状态" clearable style="width: 140px" @change="reload">
        <el-option label="待审核" value="pending" />
        <el-option label="已通过" value="approved" />
        <el-option label="已驳回" value="rejected" />
        <el-option label="已停用" value="disabled" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="昵称或手机号"
        clearable
        style="width: 200px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-button @click="reload">查询</el-button>
      <div class="toolbar-right">
        <el-button
          v-if="auth.has('user.audit')"
          type="primary"
          :disabled="selected.length === 0"
          @click="onBatchApprove"
        >
          批量通过（{{ selected.length }}）
        </el-button>
        <el-button v-if="auth.has('user.audit')" @click="onCreateInvite">生成邀请码</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      size="small"
      style="width: 100%"
      @selection-change="(v) => (selected = v)"
    >
      <el-table-column type="selection" width="44" :selectable="(row) => row.auditStatus === 'pending'" />
      <el-table-column label="用户" min-width="160">
        <template #default="{ row }">
          <div class="user">
            <el-avatar :size="28" :src="row.avatarUrl">{{ (row.nickName || '?').slice(0, 1) }}</el-avatar>
            <div>
              <div>{{ row.nickName || '未填昵称' }}</div>
              <div class="muted">{{ row.userId }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="phone" label="手机号" width="130" />
      <el-table-column label="准入方式" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">
            {{ row.accessVia === 'invite' ? '邀请码' : row.accessVia === 'group' ? '群入口' : '—' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.auditStatus)" size="small">{{ statusLabel(row.auditStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="驳回原因" min-width="140">
        <template #default="{ row }">{{ row.rejectReason || '—' }}</template>
      </el-table-column>
      <el-table-column label="申请时间" width="160">
        <template #default="{ row }">{{ (row.createdAt || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <template v-if="auth.has('user.audit')">
            <el-button v-if="row.auditStatus === 'pending'" link type="primary" @click="onAudit(row, 'approve')">
              通过
            </el-button>
            <el-button v-if="row.auditStatus === 'pending'" link type="danger" @click="onAudit(row, 'reject')">
              驳回
            </el-button>
            <el-button v-if="row.auditStatus === 'rejected'" link type="primary" @click="onAudit(row, 'approve')">
              重新通过
            </el-button>
            <el-button
              v-if="row.auditStatus === 'approved'"
              link
              type="danger"
              @click="onAudit(row, 'disable')"
            >
              停用
            </el-button>
            <el-button v-if="row.auditStatus === 'disabled'" link type="primary" @click="onAudit(row, 'enable')">
              启用
            </el-button>
          </template>
          <span v-else class="muted">只读</span>
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
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../../api';
import { useAuthStore } from '../../stores/auth';

const auth = useAuthStore();

const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);
const auditStatus = ref('');
const keyword = ref('');
const selected = ref([]);

function statusLabel(value) {
  return (
    { pending: '待审核', approved: '已通过', rejected: '已驳回', disabled: '已停用' }[value] || value
  );
}

function statusType(value) {
  return { pending: 'warning', approved: 'success', rejected: 'danger', disabled: 'info' }[value] || 'info';
}

function load() {
  loading.value = true;
  http
    .get('/admin/user/list', {
      params: {
        auditStatus: auditStatus.value || undefined,
        keyword: keyword.value || undefined,
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

function onAudit(row, action) {
  if (action === 'reject') {
    ElMessageBox.prompt('驳回原因会展示给用户，请写清楚', '驳回申请', {
      inputPlaceholder: '如：不是本群同事，请说明身份',
      inputValidator: (value) => (value && value.trim() ? true : '必须填写驳回原因'),
    })
      .then(({ value }) => {
        http
          .post('/admin/user/audit', { userId: row.userId, action, reason: value.trim() })
          .then(() => {
            ElMessage.success('已驳回');
            load();
          })
          .catch(() => {});
      })
      .catch(() => {});
    return;
  }

  const label = { approve: '通过', disable: '停用', enable: '启用' }[action];
  ElMessageBox.confirm('确定' + label + '「' + (row.nickName || row.userId) + '」？', label)
    .then(() => {
      http
        .post('/admin/user/audit', { userId: row.userId, action })
        .then(() => {
          ElMessage.success('已' + label);
          load();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

/** 批量通过：逐条发请求，单条失败不阻塞其余，最后汇总给一个结果 */
async function onBatchApprove() {
  const users = selected.value.filter((row) => row.auditStatus === 'pending');
  if (users.length === 0) {
    return;
  }
  try {
    await ElMessageBox.confirm('确定通过选中的 ' + users.length + ' 位用户？', '批量通过');
  } catch (e) {
    // 用户取消，什么都不做
    return;
  }

  let ok = 0;
  const failed = [];
  for (const user of users) {
    try {
      await http.post('/admin/user/audit', { userId: user.userId, action: 'approve' });
      ok += 1;
    } catch (e) {
      failed.push(user.nickName || user.userId);
    }
  }
  ElMessage.success('已通过 ' + ok + ' 位');
  if (failed.length) {
    ElMessageBox.alert('以下用户未通过：' + failed.join('、'), '部分失败', { type: 'warning' });
  }
  load();
}

function onCreateInvite() {
  ElMessageBox.prompt('邀请码有效期（小时，1-720）', '生成一次性邀请码', {
    inputValue: '168',
    inputPattern: /^\d+$/,
    inputErrorMessage: '请填数字',
  })
    .then(({ value }) => {
      const hours = Number(value);
      if (hours < 1 || hours > 720) {
        ElMessage.warning('有效期需在 1 到 720 小时之间');
        return;
      }
      http
        .post('/admin/invite/create', { expireHours: hours })
        .then((result) => {
          ElMessageBox.alert(
            '邀请码：' + result.code + '\n有效期至 ' + (result.expireAt || '').replace('T', ' ').slice(0, 16),
            '生成成功',
            { type: 'success' }
          );
        })
        .catch(() => {});
    })
    .catch(() => {});
}

onMounted(load);
</script>

<style scoped>
.user {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
