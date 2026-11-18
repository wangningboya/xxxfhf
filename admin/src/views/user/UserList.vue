<template>
  <div class="page-card">
    <div class="toolbar">
      <el-select v-model="auditStatus" placeholder="全部状态" clearable style="width: 140px" @change="reload">
        <el-option
          v-for="item in auditOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
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
        <el-button v-if="auth.has('user.audit')" @click="openInvites">邀请码管理</el-button>
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
      <el-table-column
        type="selection"
        width="44"
        :selectable="(row) => row.auditStatus === 'pending' && row.canAudit"
      />
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
      <el-table-column label="手机号" width="150">
        <template #default="{ row }">
          <span>{{ row.phone || '未填' }}</span>
          <!-- 代填：老人不会用微信授权、或者手填时打错一位，都靠这里改 -->
          <el-button
            v-if="auth.has('user.audit')"
            link
            type="primary"
            class="phone-edit"
            @click="openPhone(row)"
          >
            改
          </el-button>
        </template>
      </el-table-column>
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
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <template v-if="auth.has('user.audit')">
            <!-- 没填昵称的人列表照常出现，但不给通过/驳回：商家认不出是谁，点了也只能靠猜 -->
            <span v-if="needNickName(row)" class="muted">待对方填昵称</span>
            <template v-else>
              <el-button v-if="row.auditStatus === 'pending'" link type="primary" @click="onAudit(row, 'approve')">
                通过
              </el-button>
              <el-button v-if="row.auditStatus === 'pending'" link type="danger" @click="onAudit(row, 'reject')">
                驳回
              </el-button>
              <el-button v-if="row.auditStatus === 'rejected'" link type="primary" @click="onAudit(row, 'approve')">
                重新通过
              </el-button>
            </template>
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
          <!-- 删除是物理删除、不可恢复，单独挂 user.delete，不跟审核混一个权限 -->
          <el-button
            v-if="auth.has('user.delete')"
            link
            type="danger"
            @click="onDelete(row)"
          >
            删除
          </el-button>
          <span v-if="!auth.has('user.audit') && !auth.has('user.delete')" class="muted">只读</span>
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

    <!-- 邀请码管理：发错群时能立刻作废，不用等它过期 -->
    <el-dialog v-model="inviteVisible" title="邀请码管理" width="680px">
      <div class="invite-head">
        <el-select v-model="inviteStatus" placeholder="全部状态" clearable style="width: 140px" @change="loadInvites">
          <el-option
            v-for="item in inviteOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <span class="muted">作废只对「未使用」的码有效；已使用的码作废会误导对方以为准入被撤销</span>
      </div>
      <el-table v-loading="inviteLoading" :data="invites" border size="small" style="width: 100%">
        <el-table-column prop="code" label="邀请码" width="130">
          <template #default="{ row }"><code>{{ row.code }}</code></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="inviteTag(row.status)">{{ inviteLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效期至" width="150">
          <template #default="{ row }">{{ (row.expireAt || '').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="使用时间" width="150">
          <template #default="{ row }">{{ (row.usedAt || '—').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'unused'"
              link
              type="danger"
              @click="onRevoke(row)"
            >
              作废
            </el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 代填 / 修改手机号：接口早就有了，之前两端都只能看不能改 -->
    <el-dialog v-model="phoneVisible" title="修改手机号" width="420px">
      <el-form label-width="80px">
        <el-form-item label="用户">
          <span>{{ phoneRow ? phoneRow.nickName || phoneRow.userId : '' }}</span>
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="phoneValue" maxlength="20" placeholder="11 位大陆手机号" clearable />
        </el-form-item>
      </el-form>
      <div class="muted">仅用于配送联系。下单时会把手机号快照进订单，改这里不影响历史订单。</div>
      <template #footer>
        <el-button @click="phoneVisible = false">取消</el-button>
        <el-button type="primary" :loading="phoneSaving" @click="onSavePhone">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../../api';
import { useAuthStore } from '../../stores/auth';
import { useDictStore } from '../../stores/dict';

const auth = useAuthStore();
const dict = useDictStore();

const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);
/** 字典没加载出来时的兜底；加载完成后以字典为准 */
const FALLBACK_AUDIT = [
  { value: 'pending', label: '待审核' },
  { value: 'approved', label: '已通过' },
  { value: 'rejected', label: '已驳回' },
  { value: 'disabled', label: '已停用' },
];
const FALLBACK_INVITE = [
  { value: 'unused', label: '未使用' },
  { value: 'used', label: '已使用' },
  { value: 'expired', label: '已过期' },
  { value: 'revoked', label: '已作废' },
];

const auditOptions = computed(() => dict.options('audit_status', FALLBACK_AUDIT));
const inviteOptions = computed(() => dict.options('invite_status', FALLBACK_INVITE));

const auditStatus = ref('');
const keyword = ref('');
const selected = ref([]);

function statusLabel(value) {
  const fallback =
    { pending: '待审核', approved: '已通过', rejected: '已驳回', disabled: '已停用' }[value] || value;
  return dict.label('audit_status', value, fallback);
}

function statusType(value) {
  return dict.tagType('audit_status', value);
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

/* ==================== 代填手机号 ==================== */

const phoneVisible = ref(false);
const phoneRow = ref(null);
const phoneValue = ref('');
const phoneSaving = ref(false);

function openPhone(row) {
  phoneRow.value = row;
  phoneValue.value = row.phone || '';
  phoneVisible.value = true;
}

function onSavePhone() {
  const phone = (phoneValue.value || '').trim();
  // 与服务端同一套规则：11 位大陆手机号，不校验归属
  if (!/^1\d{10}$/.test(phone)) {
    ElMessage.error('手机号应为 11 位，以 1 开头');
    return;
  }
  phoneSaving.value = true;
  http
    .post('/admin/user/phone', { userId: phoneRow.value.userId, phone })
    .then(() => {
      ElMessage.success('已保存');
      phoneVisible.value = false;
      load();
    })
    .catch(() => {})
    .finally(() => {
      phoneSaving.value = false;
    });
}

/* ==================== 删除用户 ==================== */

/**
 * 物理删除，不可恢复。服务端还有两道闸：名下有订单会拒删、只接受 customer 类型。
 * 删完如果这页被删空且不在第一页，回退一页，免得停在一张空表上。
 */
function onDelete(row) {
  const name = row.nickName || row.userId;
  ElMessageBox.confirm('将永久删除「' + name + '」及其名下收货地址，不可恢复。', '删除用户', {
    type: 'warning',
    confirmButtonText: '删除',
    confirmButtonClass: 'el-button--danger',
  })
    .then(() => {
      http
        .post('/admin/user/delete', { userId: row.userId })
        .then(() => {
          ElMessage.success('已删除');
          if (rows.value.length === 1 && page.value > 1) {
            page.value -= 1;
          }
          load();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

/** 没填昵称的人不能通过/驳回：审核列表里认不出是谁，先让对方补昵称 */
function needNickName(row) {
  return (row.auditStatus === 'pending' || row.auditStatus === 'rejected') && !row.canAudit;
}

function onAudit(row, action) {
  // 兜底：后端也会拦，这里先给一句人话，省得用户看到红字报错
  if ((action === 'approve' || action === 'reject') && !row.canAudit) {
    ElMessage.warning('对方还没填昵称，暂时不能审核；请让对方先在「我的」页填写昵称');
    return;
  }
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
  const users = selected.value.filter((row) => row.auditStatus === 'pending' && row.canAudit);
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
    ElMessageBox.alert('以下用户未通过：' + failed.join('、'), '部分失败', { type: 'warning' })
      // 同生成邀请码的结果提示：点 × 关闭会 reject，需接住避免全局兜底误报
      .catch(() => {});
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
          )
            // 点 × 关闭结果提示框也会 reject，不接住会被全局兜底当成"未知错误"
            .catch(() => {});
        })
        .catch(() => {});
    })
    .catch(() => {});
}

const inviteVisible = ref(false);
const inviteLoading = ref(false);
const inviteStatus = ref('');
const invites = ref([]);

function openInvites() {
  inviteVisible.value = true;
  loadInvites();
}

function loadInvites() {
  inviteLoading.value = true;
  http
    .get('/admin/invite/list', {
      params: { status: inviteStatus.value || undefined, page: 1, pageSize: 50 },
    })
    .then((result) => {
      invites.value = (result && result.list) || [];
    })
    .catch(() => {})
    .finally(() => {
      inviteLoading.value = false;
    });
}

function inviteLabel(status) {
  const fallback =
    { unused: '未使用', used: '已使用', expired: '已过期', revoked: '已作废' }[status] || status;
  return dict.label('invite_status', status, fallback);
}

function inviteTag(status) {
  return dict.tagType('invite_status', status);
}

function onRevoke(row) {
  ElMessageBox.confirm(`作废后邀请码 ${row.code} 立刻失效，确定吗？`, '作废邀请码')
    .then(() => {
      http
        .post('/admin/invite/revoke', { code: row.code })
        .then(() => {
          ElMessage.success('已作废');
          loadInvites();
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

/* 手机号旁的「改」：贴得太近会看不清是按钮，留一点间距 */
.phone-edit {
  margin-left: 6px;
}
</style>
