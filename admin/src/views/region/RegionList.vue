<template>
  <div class="page-card">
    <div class="toolbar">
      <span class="muted">区域决定地址归属与备菜汇总；有地址或历史订单引用的区域不能删除，只能停用</span>
      <div class="toolbar-right">
        <el-button type="primary" @click="openForm()">新增区域</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" border size="small" style="width: 100%">
      <el-table-column prop="name" label="区域名称" min-width="160" />
      <el-table-column prop="remark" label="备注" min-width="200">
        <template #default="{ row }">{{ row.remark || '—' }}</template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="80" align="center" />
      <el-table-column label="地址数" width="100" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="openAddresses(row)">
            {{ row.addressCount || 0 }} 个
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'enabled' ? 'success' : 'info'" size="small">
            {{ row.status === 'enabled' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openForm(row)">编辑</el-button>
          <el-button link type="primary" @click="toggleStatus(row)">
            {{ row.status === 'enabled' ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 区域下的地址清单：区域停用是「判定相乘」，地址本身不用逐条改；这里是给商家做个别处置用的 -->
    <el-drawer v-model="drawer" :title="drawerTitle" size="62%">
      <div class="drawer-hint">
        区域停用后，下面这些地址一律不能用于新配送单（已下的订单不受影响）。地址也能单独停用，两者互不干扰：区域恢复启用时，单独停用过的地址仍然是停用。
        <template v-if="!canToggleAddress">你没有「用户地址维护」权限，这里只能看。</template>
      </div>
      <el-table v-loading="drawerLoading" :data="drawerRows" border size="small" style="width: 100%">
        <el-table-column label="联系人 / 手机号" min-width="150">
          <template #default="{ row }">
            <div>{{ row.contactName }}</div>
            <div class="muted">{{ row.phone || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详细地址" min-width="180" />
        <el-table-column label="审核状态" width="100">
          <template #default="{ row }">
            <el-tag :type="auditTag(row.status)" size="small" effect="plain">
              {{ auditLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'danger'" size="small" effect="plain">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
            <div v-if="!row.enabled && row.disabledReason" class="unusable-reason">
              {{ row.disabledReason }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="可下单" width="140">
          <template #default="{ row }">
            <el-tag :type="row.usable ? 'success' : 'info'" size="small">
              {{ row.usable ? '可用' : '不可用' }}
            </el-tag>
            <div v-if="!row.usable" class="unusable-reason">{{ unusableText(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column v-if="canToggleAddress" label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.enabled" link type="warning" @click="toggleAddressEnabled(row)">
              停用
            </el-button>
            <el-button v-else link type="success" @click="toggleAddressEnabled(row)">启用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!drawerLoading && drawerRows.length === 0" description="这个区域下还没有地址" />
    </el-drawer>

    <el-dialog v-model="dialog" :title="form.id ? '编辑区域' : '新增区域'" width="400px">
      <el-form :model="form" label-width="72px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="30" placeholder="如：A 栋 / 研发中心" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="60" placeholder="范围说明，可不填" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
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
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '../../stores/auth';
import { useDictStore } from '../../stores/dict';
import http from '../../api';

const auth = useAuthStore();
const dict = useDictStore();

const rows = ref([]);
const loading = ref(false);
const dialog = ref(false);
const saving = ref(false);
const form = ref({});

/** 抽屉里的地址也归「用户地址维护」管，只给 region.manage 时抽屉只读 */
const canToggleAddress = computed(() => auth.has('user.address'));

function load() {
  loading.value = true;
  http
    .get('/admin/region/list')
    .then((list) => {
      rows.value = list || [];
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

function openForm(row) {
  form.value = row
    ? { id: row.id, name: row.name, remark: row.remark || '', sort: row.sort || 0 }
    : { id: '', name: '', remark: '', sort: rows.value.length + 1 };
  dialog.value = true;
}

function onSave() {
  if (!form.value.name || !form.value.name.trim()) {
    ElMessage.warning('请填写区域名称');
    return;
  }
  saving.value = true;
  http
    .post('/admin/region/save', {
      id: form.value.id || null,
      name: form.value.name.trim(),
      remark: form.value.remark || null,
      sort: form.value.sort,
    })
    .then(() => {
      ElMessage.success('已保存');
      dialog.value = false;
      load();
    })
    .catch(() => {})
    .finally(() => {
      saving.value = false;
    });
}

function toggleStatus(row) {
  const next = row.status === 'enabled' ? 'disabled' : 'enabled';
  if (next === 'disabled') {
    // 停用区域不动地址数据，但下面所有地址都不能再下单了，先把数量说清楚
    const count = row.addressCount || 0;
    const tail = count > 0 ? '该区域下有 ' + count + ' 个地址，停用后它们都不能用于新配送单（已下的订单不受影响）。' : '';
    ElMessageBox.confirm(tail + '地址数据不会被修改，区域恢复启用后自动恢复。确定停用「' + row.name + '」？', '停用区域')
      .then(() => postStatus(row, next))
      .catch(() => {});
    return;
  }
  postStatus(row, next);
}

function postStatus(row, next) {
  http
    .post('/admin/region/status', { regionId: row.id, status: next })
    .then(() => load())
    .catch(() => {});
}

function onDelete(row) {
  ElMessageBox.confirm('有地址或历史订单引用的区域不能删除，只能停用。确定删除「' + row.name + '」？', '删除区域')
    .then(() => {
      http
        .post('/admin/region/delete', { regionId: row.id })
        .then(() => {
          ElMessage.success('已删除');
          load();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

/* ==================== 区域下的地址清单 ==================== */

const drawer = ref(false);
const drawerRows = ref([]);
const drawerTotal = ref(0);
const drawerLoading = ref(false);
const drawerRegion = ref({});

const drawerTitle = computed(() => {
  const name = drawerRegion.value.name || '';
  const count = drawerTotal.value || 0;
  return name ? '「' + name + '」下的地址（' + count + ' 个）' : '区域下的地址';
});

function openAddresses(row) {
  drawerRegion.value = row;
  drawer.value = true;
  loadAddresses(row.id);
}

function loadAddresses(regionId) {
  drawerLoading.value = true;
  // 复用跨用户地址队列，只加 regionId 条件 —— 不另开一个「按区域查地址」的接口
  http
    .get('/admin/address/list', { params: { regionId, page: 1, pageSize: 100 } })
    .then((result) => {
      drawerRows.value = (result && result.list) || [];
      drawerTotal.value = (result && result.total) || 0;
    })
    .catch(() => {})
    .finally(() => {
      drawerLoading.value = false;
    });
}

function auditLabel(value) {
  const fallback = { active: '可用', pending: '待确认', rejected: '已驳回' }[value] || value;
  return dict.label('address_status', value, fallback);
}

function auditTag(value) {
  return { active: 'success', pending: 'warning', rejected: 'danger' }[value] || 'info';
}

/** 与后端 AddressService.usabilityOf 同序：审核状态 → 地址启用 → 区域停用 */
function unusableText(row) {
  if (row.status === 'pending') return '待商家确认';
  if (row.status === 'rejected') return row.rejectReason ? '已驳回：' + row.rejectReason : '已驳回';
  if (!row.enabled) return row.disabledReason ? '已停用：' + row.disabledReason : '已停用';
  if (row.regionStatus === 'disabled') return '所属区域已停用';
  return '不可用';
}

function toggleAddressEnabled(row) {
  if (!row.enabled) {
    ElMessageBox.confirm(
      '启用后该地址恢复可用。如果它所属的区域仍是停用状态，则依然不能下单。确定启用？',
      '启用地址'
    )
      .then(() => postAddressEnabled(row, true))
      .catch(() => {});
    return;
  }
  ElMessageBox.prompt(
    '停用后该地址不能再用于新配送单（已下的订单不受影响），用户端会显示这句原因。可以不填。',
    '停用地址',
    {
      confirmButtonText: '停用',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '如：地址已拆迁 / 用户要求暂停配送',
      inputValidator: (value) => ((value || '').trim().length > 200 ? '最多 200 字' : true),
    }
  )
    .then(({ value }) => postAddressEnabled(row, false, (value || '').trim()))
    .catch(() => {});
}

function postAddressEnabled(row, enabled, reason) {
  http
    .post('/admin/address/enabled', { addressId: row.id, enabled, reason: reason || null })
    .then(() => {
      ElMessage.success(enabled ? '已启用' : '已停用');
      loadAddresses(drawerRegion.value.id);
      load();
    })
    .catch(() => {});
}

onMounted(load);
</script>

<style scoped>
.drawer-hint {
  margin-bottom: 10px;
  font-size: 12px;
  color: #8792a2;
  line-height: 1.6;
}

/* 不可用的原因跟在标签下面 */
.unusable-reason {
  margin-top: 2px;
  font-size: 12px;
  color: #8792a2;
  line-height: 1.4;
}
</style>
