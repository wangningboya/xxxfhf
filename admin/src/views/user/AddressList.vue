<template>
  <div class="page-card">
    <div class="toolbar">
      <el-select
        v-model="userId"
        filterable
        remote
        reserve-keyword
        placeholder="搜索并选择用户（昵称或手机号）"
        :remote-method="searchUsers"
        :loading="userLoading"
        style="width: 300px"
        @change="loadAddresses"
      >
        <el-option
          v-for="user in users"
          :key="user.userId"
          :label="(user.nickName || '未填昵称') + ' / ' + (user.phone || '无手机号')"
          :value="user.userId"
        />
      </el-select>
      <div class="toolbar-right">
        <el-button type="primary" :disabled="!userId" @click="openForm()">新增地址</el-button>
      </div>
    </div>

    <el-empty v-if="!userId" description="先选一位用户，再维护他的收货地址" />

    <template v-else>
      <el-table v-loading="loading" :data="addresses" border size="small" style="width: 100%">
        <el-table-column prop="regionName" label="区域" width="130" />
        <el-table-column prop="contactName" label="联系人" width="110" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="detail" label="详细地址" min-width="200" />
        <el-table-column prop="tag" label="标签" width="90">
          <template #default="{ row }">{{ row.tag || '—' }}</template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.source === 'merchant' ? '商家' : '用户' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
            <div v-if="row.status === 'rejected' && row.rejectReason" class="reject-reason">
              {{ row.rejectReason }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)">编辑</el-button>
            <el-button
              v-if="row.status !== 'active'"
              link
              type="success"
              @click="setStatus(row, 'active')"
            >
              通过
            </el-button>
            <el-button
              v-if="row.status !== 'rejected'"
              link
              type="danger"
              @click="onReject(row)"
            >
              驳回
            </el-button>
            <el-button
              v-if="row.status === 'active'"
              link
              type="warning"
              @click="setStatus(row, 'pending')"
            >
              收回
            </el-button>
            <el-button link type="danger" plain @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <el-dialog v-model="dialog" :title="form.id ? '编辑地址' : '新增地址'" width="460px">
      <el-form :model="form" label-width="80px">
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
          <el-input v-model="form.phone" maxlength="11" />
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

const users = ref([]);
const userLoading = ref(false);
const userId = ref('');

const addresses = ref([]);
const regions = ref([]);
const loading = ref(false);

const dialog = ref(false);
const saving = ref(false);
const form = ref({});

function searchUsers(keyword) {
  userLoading.value = true;
  http
    .get('/admin/user/list', { params: { keyword: keyword || undefined, page: 1, pageSize: 20 } })
    .then((result) => {
      users.value = (result && result.list) || [];
    })
    .catch(() => {})
    .finally(() => {
      userLoading.value = false;
    });
}

function loadAddresses() {
  if (!userId.value) {
    addresses.value = [];
    return;
  }
  loading.value = true;
  http
    .get('/admin/address/list', { params: { userId: userId.value } })
    .then((list) => {
      addresses.value = list || [];
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

function openForm(row) {
  if (row) {
    form.value = {
      id: row.id,
      userId: row.userId,
      regionId: row.regionId,
      contactName: row.contactName,
      phone: row.phone,
      detail: row.detail,
      tag: row.tag || '',
    };
  } else {
    form.value = {
      id: '',
      userId: userId.value,
      regionId: regions.value.length ? regions.value[0].id : '',
      contactName: '',
      phone: '',
      detail: '',
      tag: '',
    };
  }
  dialog.value = true;
}

function onSave() {
  const data = form.value;
  if (!data.regionId) {
    ElMessage.warning('请选择区域');
    return;
  }
  if (!data.contactName || !data.contactName.trim()) {
    ElMessage.warning('请填写联系人');
    return;
  }
  if (!/^1\d{10}$/.test(data.phone || '')) {
    ElMessage.warning('请填写 11 位手机号');
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
      loadAddresses();
    })
    .catch(() => {})
    .finally(() => {
      saving.value = false;
    });
}

/** 地址三态的显示名走字典，商家可以把「待确认」改成「待核对」 */
function statusLabel(status) {
  const fallback = { active: '可用', pending: '待确认', rejected: '已驳回' }[status] || status;
  return dict.label('address_status', status, fallback);
}

function statusTag(status) {
  return { active: 'success', pending: 'warning', rejected: 'danger' }[status] || 'info';
}

function setStatus(row, status, reason) {
  http
    .post('/admin/address/status', { addressId: row.id, status, reason })
    .then(() => {
      ElMessage.success(status === 'active' ? '已通过' : '已更新');
      loadAddresses();
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
  ElMessageBox.confirm('删除后顾客需要重新填写地址，确定吗？', '删除地址')
    .then(() => {
      http
        .post('/admin/address/delete', { addressId: row.id })
        .then(() => {
          ElMessage.success('已删除');
          loadAddresses();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

onMounted(() => {
  searchUsers('');
  http
    .get('/region/list')
    .then((list) => {
      regions.value = list || [];
    })
    .catch(() => {});
});
</script>
