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
        style="width: 180px"
        @change="reload"
      >
        <el-option
          v-for="region in regions"
          :key="region.id"
          :label="regionLabel(region)"
          :value="region.id"
        />
      </el-select>
      <el-select v-model="filterEnabled" style="width: 130px" @change="reload">
        <el-option label="全部" value="" />
        <el-option label="仅启用" :value="1" />
        <el-option label="仅停用" :value="0" />
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
      <br />
      区域停用后，该区域下的地址一律不可用于下单（地址本身的状态不用改，区域恢复启用即自动恢复）；地址也能单独停用，但两者互不干扰。
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
      <!-- 审核状态 × 地址启用 × 区域状态，三者相乘才是「能不能下单」的口径 -->
      <el-table-column label="可下单" width="150">
        <template #default="{ row }">
          <el-tag :type="row.usable ? 'success' : 'info'" size="small">
            {{ row.usable ? '可用' : '不可用' }}
          </el-tag>
          <div v-if="!row.usable" class="unusable-reason">{{ unusableText(row) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="启用状态" width="130">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'danger'" size="small" effect="plain">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
          <div v-if="!row.enabled && row.disabledReason" class="unusable-reason">
            {{ row.disabledReason }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
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
          <el-button v-if="row.enabled" link type="warning" @click="onDisable(row)">停用</el-button>
          <el-button v-else link type="success" @click="onEnable(row)">启用</el-button>
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
            <el-option
              v-for="region in formRegionOptions"
              :key="region.id"
              :label="regionLabel(region)"
              :value="region.id"
            />
          </el-select>
          <div class="muted">
            区域由「区域管理」维护，停用的区域不能新增地址（改门牌仍可保存）；筛选项里能看到已停用的区域，是为了把受影响的地址筛出来。
          </div>
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
          <el-select v-model="form.tag" clearable placeholder="可不填" style="width: 100%">
            <el-option v-for="item in tagOptions" :key="item" :label="item" :value="item" />
          </el-select>
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
import { useDictStore } from '../../stores/dict';
import http from '../../api';

const dict = useDictStore();

/* ==================== 列表与筛选 ==================== */

const status = ref('pending');
const filterUserId = ref('');
const filterRegionId = ref('');
const filterEnabled = ref('');
const keyword = ref('');

const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);
const pendingCount = ref(0);

const regions = ref([]);

/** 区域筛选用全量（含停用），新增/编辑的选项另算 —— 停用区域不能再挂新地址 */
const formRegionOptions = computed(() =>
  regions.value.filter((region) => region.status !== 'disabled' || region.id === form.value.regionId)
);

function regionLabel(region) {
  const suffix = region.status === 'disabled' ? '（已停用）' : '';
  const count = typeof region.addressCount === 'number' ? '（' + region.addressCount + ' 个地址）' : '';
  return region.name + suffix + count;
}

function loadRegions() {
  // 用后台接口取全量区域：用户端的 /region/list 只返回启用中的，停用区域筛不出来
  http
    .get('/admin/region/list')
    .then((list) => {
      regions.value = list || [];
    })
    .catch(() => {});
}

function load() {
  loading.value = true;
  http
    .get('/admin/address/list', {
      params: {
        status: status.value || undefined,
        enabled: filterEnabled.value === '' ? undefined : filterEnabled.value,
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
  filterEnabled.value = '';
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

/**
 * 新增时带出归属用户的昵称与手机号 —— 这两项都是「跟人走」的，换人就整体换掉，
 * 不沿用上一个用户的残留。
 *
 * 手机号不能直接用下拉里的值：`/admin/user/list` 下发的账号手机号是掩码
 * （138****1234），填进 maxlength=11 的输入框会被截断，保存时也过不了 11 位校验。
 * 所以有号时再走一次「查看完整手机号」接口（user.phone.view，一次一个用户、
 * 每次写一条操作记录）拿明文；没号就不发请求，保持留空。
 */
function fillFromUser(user) {
  if (!user) {
    return;
  }
  form.value.contactName = user.nickName || '';
  form.value.phone = '';
  // 掩码非空 = 该用户绑了手机号；没绑就不用去要明文，省一次请求和一条操作记录
  if (!user.phone) {
    return;
  }
  const userId = user.userId;
  http
    .get(`/admin/user/${userId}/phone`)
    .then((phone) => {
      // 请求返回前可能已经换了人：只认当前仍选中这个人，且商家没自己填过才回填
      if (form.value.userId === userId && !form.value.phone) {
        form.value.phone = phone || '';
      }
    })
    .catch(() => {
      // 没 user.phone.view 权限或接口失败：留空让商家自己输，不打断填表
    });
}

function onFormUserChange(userId) {
  if (form.value.id) {
    return;
  }
  fillFromUser(formUsers.value.find((u) => u.userId === userId));
}

/* ==================== 代客新增 / 编辑 ==================== */

const dialog = ref(false);
const saving = ref(false);
const form = ref({});

/**
 * 标签只能选，不能手输（需求 4.x 定死「家 / 公司」，与小程序地址编辑页同一口径）。
 *
 * 编辑老数据时，库里可能存着早年手输的其它值（如「学校」）：
 * 把它作为额外一项显示出来，不改就原样带回去，不强迫商家重选。
 */
const TAG_PRESETS = ['家', '公司'];

const tagOptions = computed(() => {
  const current = form.value.tag;
  return current && !TAG_PRESETS.includes(current) ? TAG_PRESETS.concat(current) : TAG_PRESETS;
});

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
      regionId: regions.value.length ? formRegionOptions.value[0]?.id || '' : '',
      contactName: '',
      phone: '',
      detail: '',
      tag: '',
    };
    // 从「按用户筛选」进来时，用户下拉里得先有这一项，否则只显示一串 ID；
    // 顺手把该用户的昵称和手机号带进表单，省得再手填一遍
    if (form.value.userId) {
      formUsers.value = filterUsers.value.slice();
      fillFromUser(formUsers.value.find((u) => u.userId === form.value.userId));
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

/** 与后端 AddressService.usabilityOf 同序：审核状态 → 地址启用 → 区域停用 */
function unusableText(row) {
  if (row.status === 'pending') return '待商家确认';
  if (row.status === 'rejected') return row.rejectReason ? '已驳回：' + row.rejectReason : '已驳回';
  if (!row.enabled) return row.disabledReason ? '已停用：' + row.disabledReason : '已停用';
  if (row.regionStatus === 'disabled') return '所属区域已停用';
  return '不可用';
}

/* ==================== 地址启用 / 停用 ==================== */

function setEnabled(row, enabled, reason) {
  http
    .post('/admin/address/enabled', { addressId: row.id, enabled, reason: reason || null })
    .then(() => {
      ElMessage.success(enabled ? '已启用' : '已停用');
      load();
      loadPendingCount();
    })
    .catch(() => {});
}

/**
 * 停用原因可以不填 —— 但填了用户端会原样看到，比只显示「已停用」更能解释清楚。
 */
function onDisable(row) {
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
    .then(({ value }) => setEnabled(row, false, (value || '').trim()))
    .catch(() => {});
}

function onEnable(row) {
  ElMessageBox.confirm(
    '启用后该地址恢复可用。如果它所属的区域仍是停用状态，则依然不能下单。确定启用？',
    '启用地址'
  )
    .then(() => setEnabled(row, true))
    .catch(() => {});
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
  loadRegions();
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

/* 不可用的原因跟在标签下面，跟驳回原因同一套观感 */
.unusable-reason {
  margin-top: 2px;
  font-size: 12px;
  color: #8792a2;
  line-height: 1.4;
}
</style>
