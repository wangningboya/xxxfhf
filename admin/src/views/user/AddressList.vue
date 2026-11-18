<template>
  <div class="page-card">
    <!-- 页签就是「待办优先」：进页面默认落在待确认，跟工作台那个数字是同一个口径 -->
    <div class="tabs">
      <el-radio-group v-model="status" @change="reload">
        <el-radio-button value="pending">待确认（{{ pendingCount }}）</el-radio-button>
        <el-radio-button value="active">已通过</el-radio-button>
        <el-radio-button value="rejected">已驳回</el-radio-button>
        <el-radio-button value="">全部</el-radio-button>
      </el-radio-group>
    </div>

    <div class="toolbar">
      <el-select
        v-model="filterUserId"
        filterable
        remote
        reserve-keyword
        clearable
        placeholder="按用户筛选（昵称或手机号）"
        :remote-method="searchFilterUsers"
        :loading="filterUserLoading"
        style="width: 240px"
        @change="reload"
      >
        <el-option
          v-for="user in filterUsers"
          :key="user.userId"
          :label="(user.nickName || '未填昵称') + ' / ' + (user.phone || '无手机号')"
          :value="user.userId"
        />
      </el-select>
      <el-select
        v-model="filterRegionId"
        clearable
        placeholder="按区域筛选"
        style="width: 160px"
        @change="reload"
      >
        <el-option v-for="region in regions" :key="region.id" :label="region.name" :value="region.id" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="联系人 / 手机号 / 详细地址"
        clearable
        style="width: 220px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-button @click="reload">查询</el-button>
      <el-button @click="onReset">重置</el-button>
      <div class="toolbar-right">
        <el-button type="primary" @click="openForm()">代客新增地址</el-button>
      </div>
    </div>

    <div class="hint">
      待确认按提交时间正序排（先来的先审）。通过后地址立刻可用于下单；驳回必须写原因，用户端会原样显示。
    </div>

    <el-table v-loading="loading" :data="rows" border size="small" style="width: 100%">
      <el-table-column label="用户" min-width="150">
        <template #default="{ row }">
          <div>{{ row.userName || '未填昵称' }}</div>
          <div class="muted">
            {{ row.userPhone || row.phone || '无手机号' }}
            <el-tag
              v-if="row.userAuditStatus && row.userAuditStatus !== 'approved'"
              size="small"
              type="warning"
              effect="plain"
              class="audit-tag"
              title="该用户还没通过用户审核。地址可以照常确认，但他要等账号审核通过后才能下单。"
            >
              未审核
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="regionName" label="区域" width="120" />
      <el-table-column prop="contactName" label="联系人" width="90" />
      <el-table-column prop="phone" label="手机号" width="120">
        <template #default="{ row }">{{ row.phone || '—' }}</template>
      </el-table-column>
      <el-table-column prop="detail" label="详细地址" min-width="180" />
      <el-table-column label="标签" width="80">
        <template #default="{ row }">{{ row.tag || '—' }}</template>
      </el-table-column>
      <el-table-column label="来源" width="80">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.source === 'merchant' ? '商家' : '用户' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="提交时间" width="140">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          <div v-if="row.status === 'rejected' && row.rejectReason" class="reject-reason">
            {{ row.rejectReason }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openForm(row)">编辑</el-button>
          <el-button v-if="row.status !== 'active'" link type="success" @click="setStatus(row, 'active')">
            通过
          </el-button>
          <el-button v-if="row.status !== 'rejected'" link type="danger" @click="onReject(row)">
            驳回
          </el-button>
          <el-button v-if="row.status === 'active'" link type="warning" @click="setStatus(row, 'pending')">
            收回
          </el-button>
          <el-button link type="danger" plain @click="onDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialog" :title="form.id ? '编辑地址' : '代客新增地址'" width="480px">
      <el-form :model="form" label-width="80px">
        <el-form-item v-if="!form.id" label="归属用户" required>
          <el-select
            v-model="form.userId"
            filterable
            remote
            reserve-keyword
            placeholder="搜索并选择用户（昵称或手机号）"
            :remote-method="searchFormUsers"
            :loading="formUserLoading"
            style="width: 100%"
            @change="onFormUserChange"
          >
            <el-option
              v-for="user in formUsers"
              :key="user.userId"
              :label="(user.nickName || '未填昵称') + ' / ' + (user.phone || '无手机号')"
              :value="user.userId"
            />
          </el-select>
          <div class="muted">地址必须挂在某位用户下。改归属走「删除后重建」，这里不提供改挂。</div>
        </el-form-item>
        <el-form-item v-else label="归属用户">
          <span>{{ form.userName || form.userId }}</span>
        </el-form-item>
        <el-form-item label="区域" required>
          <el-select v-model="form.regionId" placeholder="选择区域" style="width: 100%">
            <el-option v-for="region in regions" :key="region.id" :label="region.name" :value="region.id" />
          </el-select>
          <div class="muted">区域由「区域管理」维护，停用的区域不在此列出</div>
        </el-form-item>
        <el-form-item label="联系人" required>
          <el-input v-model="form.contactName" maxlength="20" />
        </el-form-item>
        <el-form-item label="手机号" required>
          <el-input v-model="form.phone" maxlength="11" placeholder="11 位手机号" />
          <div class="muted">
            仅用于配送或取餐时联系收货人，不用于营销。送到后按这个号联系；新增时自动带入归属用户的手机号，可改。
          </div>
        </el-form-item>
        <el-form-item label="详细地址" required>
          <el-input v-model="form.detail" type="textarea" :rows="2" maxlength="120" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tag" maxlength="10" placeholder="公司 / 家，可不填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useDictStore } from '../../stores/dict';
import http from '../../api';

const dict = useDictStore();

/* ==================== 列表与筛选 ==================== */

const status = ref('pending');
const filterUserId = ref('');
const filterRegionId = ref('');
const keyword = ref('');

const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);
const pendingCount = ref(0);

const regions = ref([]);

function load() {
  loading.value = true;
  http
    .get('/admin/address/list', {
      params: {
        status: status.value || undefined,
        userId: filterUserId.value || undefined,
        regionId: filterRegionId.value || undefined,
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

function onReset() {
  filterUserId.value = '';
  filterRegionId.value = '';
  keyword.value = '';
  reload();
}

/**
 * 待确认条数：页签上的数字要用全库口径，不能取当前页的行数。
 *
 * 单独发一次 pageSize=1 的请求拿 total —— 为一个数字新开一个计数接口不值得。
 */
function loadPendingCount() {
  http
    .get('/admin/address/list', { params: { status: 'pending', page: 1, pageSize: 1 } })
    .then((result) => {
      pendingCount.value = (result && result.total) || 0;
    })
    .catch(() => {});
}

/* ==================== 用户搜索 ==================== */

const filterUsers = ref([]);
const filterUserLoading = ref(false);
const formUsers = ref([]);
const formUserLoading = ref(false);

function fetchUsers(kw, target, loadingRef) {
  loadingRef.value = true;
  http
    .get('/admin/user/list', { params: { keyword: kw || undefined, page: 1, pageSize: 20 } })
    .then((result) => {
      target.value = (result && result.list) || [];
    })
    .catch(() => {})
    .finally(() => {
      loadingRef.value = false;
    });
}

function searchFilterUsers(kw) {
  fetchUsers(kw, filterUsers, filterUserLoading);
}

function searchFormUsers(kw) {
  fetchUsers(kw, formUsers, formUserLoading);
}

/** 新增时切换归属用户，手机号跟着换成该用户的 —— 号码跟人走，不沿用上一个用户的 */
function onFormUserChange(userId) {
  if (form.value.id) {
    return;
  }
  const user = formUsers.value.find((u) => u.userId === userId);
  form.value.phone = user && user.phone ? user.phone : '';
}

/* ==================== 代客新增 / 编辑 ==================== */

const dialog = ref(false);
const saving = ref(false);
const form = ref({});

function openForm(row) {
  if (row) {
    form.value = {
      id: row.id,
      userId: row.userId,
      userName: row.userName,
      regionId: row.regionId,
      contactName: row.contactName,
      phone: row.phone,
      detail: row.detail,
      tag: row.tag || '',
    };
  } else {
    form.value = {
      id: '',
      userId: filterUserId.value || '',
      userName: '',
      regionId: regions.value.length ? regions.value[0].id : '',
      contactName: '',
      phone: '',
      detail: '',
      tag: '',
    };
    // 从「按用户筛选」进来时，用户下拉里得先有这一项，否则只显示一串 ID
    if (form.value.userId) {
      formUsers.value = filterUsers.value.slice();
    }
  }
  dialog.value = true;
}

function onSave() {
  const data = form.value;
  if (!data.userId) {
    ElMessage.warning('请选择归属用户');
    return;
  }
  if (!data.regionId) {
    ElMessage.warning('请选择区域');
    return;
  }
  if (!data.contactName || !data.contactName.trim()) {
    ElMessage.warning('请填写联系人');
    return;
  }
  if (!data.phone || !/^1[3-9]\d{9}$/.test(data.phone.trim())) {
    ElMessage.warning('手机号必填，应为 11 位（1[3-9] 开头）');
    return;
  }
  if (!data.detail || !data.detail.trim()) {
    ElMessage.warning('请填写详细地址');
    return;
  }
  saving.value = true;
  http
    .post('/admin/address/save', data)
    .then(() => {
      ElMessage.success('已保存');
      dialog.value = false;
      reload();
      loadPendingCount();
    })
    .catch(() => {})
    .finally(() => {
      saving.value = false;
    });
}

/* ==================== 审核与删除 ==================== */

/** 地址三态的显示名走字典，商家可以把「待确认」改成「待核对」 */
function statusLabel(value) {
  const fallback = { active: '可用', pending: '待确认', rejected: '已驳回' }[value] || value;
  return dict.label('address_status', value, fallback);
}

function statusTag(value) {
  return { active: 'success', pending: 'warning', rejected: 'danger' }[value] || 'info';
}

function formatTime(value) {
  return (value || '').replace('T', ' ').slice(0, 16);
}

function setStatus(row, target, reason) {
  http
    .post('/admin/address/status', { addressId: row.id, status: target, reason })
    .then(() => {
      ElMessage.success(target === 'active' ? '已通过' : '已更新');
      reload();
      loadPendingCount();
    })
    .catch(() => {});
}

/**
 * 驳回必须写原因。
 *
 * 用输入框而不是「确认驳回」：用户端会把这句话原样显示，
 * 只说「不行」等于让他猜哪里不对，下一条提交还是错的。
 */
function onReject(row) {
  ElMessageBox.prompt('用户会看到这句话，写清楚哪里不对（如「3 栋没有 2 单元」）', '驳回地址', {
    confirmButtonText: '驳回',
    cancelButtonText: '取消',
    inputType: 'textarea',
    inputValidator: (value) => {
      const text = (value || '').trim();
      if (!text) return '必须填写驳回原因';
      if (text.length > 200) return '最多 200 字';
      return true;
    },
  })
    .then(({ value }) => setStatus(row, 'rejected', value.trim()))
    .catch(() => {});
}

function onDelete(row) {
  ElMessageBox.confirm(
    '删除后顾客需要重新填写地址（历史订单不受影响，它存的是下单时的地址快照），确定吗？',
    '删除地址'
  )
    .then(() => {
      http
        .post('/admin/address/delete', { addressId: row.id })
        .then(() => {
          ElMessage.success('已删除');
          // 删掉的如果是本页最后一条且不在第一页，回退一页，免得停在空表上
          if (rows.value.length === 1 && page.value > 1) {
            page.value -= 1;
          }
          load();
          loadPendingCount();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

onMounted(() => {
  load();
  loadPendingCount();
  searchFilterUsers('');
  http
    .get('/region/list')
    .then((list) => {
      regions.value = list || [];
    })
    .catch(() => {});
});
</script>

<style scoped>
.tabs {
  margin-bottom: 12px;
}

/* .pager / .reject-reason / .muted / .toolbar 都在 styles.css 里，这里不再重复定义 */
.hint {
  margin-bottom: 10px;
  font-size: 12px;
  color: #8792a2;
}

/* 未审核标记跟手机号同一行，不占额外高度 */
.audit-tag {
  margin-left: 4px;
}
</style>
