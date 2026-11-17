<template>
  <el-dialog
    v-model="visible"
    title="代客下单"
    width="880px"
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <el-form label-width="86px">
      <el-form-item label="下单用户">
        <el-select
          v-model="userId"
          filterable
          remote
          clearable
          reserve-keyword
          :remote-method="searchUsers"
          :loading="userLoading"
          placeholder="输入昵称或手机号搜索已通过的用户"
          style="width: 380px"
          @change="onUserChange"
        >
          <el-option
            v-for="u in users"
            :key="u.userId"
            :label="(u.nickName || '未填昵称') + ' · ' + (u.phone || '无手机号')"
            :value="u.userId"
          />
        </el-select>
        <span class="hint">只能选审核通过的用户</span>
      </el-form-item>

      <el-form-item label="供餐日">
        <el-date-picker
          v-model="serveDate"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          @change="loadMenu"
        />
        <span class="hint">已过截单时间也可以代下单，份数与停业仍受限</span>
      </el-form-item>

      <el-form-item label="可选盒饭">
        <el-table :data="menuItems" size="small" border style="width: 100%" empty-text="该日还没有发布菜单">
          <el-table-column label="盒饭" min-width="180">
            <template #default="{ row }">
              <div>{{ row.name }}</div>
              <div class="muted">{{ row.dishesText }}</div>
            </template>
          </el-table-column>
          <el-table-column label="价格" width="90">
            <template #default="{ row }">¥{{ yuan(row.price) }}</template>
          </el-table-column>
          <el-table-column label="剩余" width="80">
            <template #default="{ row }">
              <span :class="{ danger: row.soldOut }">{{ row.remainQty === null ? '不限' : row.remainQty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="row.soldOut" @click="openSpec(row)">
                {{ row.soldOut ? '售罄' : '选择' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-form-item>

      <el-form-item label="已选">
        <el-table :data="lines" size="small" border style="width: 100%" empty-text="还没有添加盒饭">
          <el-table-column prop="name" label="盒饭" min-width="140" />
          <el-table-column prop="specText" label="规格" min-width="140" />
          <el-table-column label="单价" width="90">
            <template #default="{ row }">¥{{ yuan(row.unit) }}</template>
          </el-table-column>
          <el-table-column prop="qty" label="份数" width="70" />
          <el-table-column label="小计" width="90">
            <template #default="{ row }">¥{{ yuan(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="" width="70">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeLine($index)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-form-item>

      <el-form-item label="取餐方式">
        <el-radio-group v-model="deliveryType">
          <el-radio label="pickup">自取</el-radio>
          <el-radio label="delivery">配送</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="deliveryType === 'pickup'" label="自取时段">
        <el-select v-model="pickupSlot" placeholder="选择时段" style="width: 220px">
          <el-option v-for="slot in slots" :key="slot" :label="slot" :value="slot" />
        </el-select>
      </el-form-item>

      <el-form-item v-else label="配送地址">
        <el-select v-model="addressId" placeholder="选择地址" style="width: 380px" no-data-text="该用户没有可用地址">
          <el-option v-for="a in addresses" :key="a.id" :label="a.regionName + ' ' + a.detail" :value="a.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="remark" type="textarea" :rows="2" maxlength="100" show-word-limit />
      </el-form-item>

      <el-form-item label="合计">
        <strong>¥{{ yuan(totalAmount) }}</strong>
      </el-form-item>
    </el-form>

    <el-dialog v-model="specVisible" title="选规格与份数" width="420px" append-to-body>
      <div v-if="editing">
        <div class="spec-title">{{ editing.name }}</div>
        <div v-for="group in editing.groups" :key="group.name" class="spec-group">
          <div class="spec-group-name">
            {{ group.name }}<span v-if="group.required" class="required">*</span>
          </div>
          <el-radio-group v-model="picked[group.name]">
            <el-radio v-for="opt in group.options" :key="opt.optionId" :label="opt.optionId">
              {{ opt.name }}<span v-if="opt.priceDelta" class="delta">{{ deltaText(opt.priceDelta) }}</span>
            </el-radio>
          </el-radio-group>
        </div>
        <div class="qty-row">
          <span>份数</span>
          <el-input-number v-model="editQty" :min="1" :max="editing.remainQty === null ? 99 : editing.remainQty" size="small" />
          <span class="amount">小计 ¥{{ yuan(editAmount) }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="specVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmSpec">加入清单</el-button>
      </template>
    </el-dialog>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">提交代下单</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import http from '../../api';

const props = defineProps({
  modelValue: { type: Boolean, default: false },
});
const emit = defineEmits(['update:modelValue', 'created']);

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
});

function yuan(cents) {
  return (Number(cents || 0) / 100).toFixed(2);
}

function deltaText(delta) {
  const value = Number(delta || 0) / 100;
  return (value > 0 ? ' +' : ' -') + Math.abs(value).toFixed(2);
}

/** 默认明天：与用户自助下单的供餐日一致，菜单也只发布明天与后天 */
function tomorrow() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

const userId = ref('');
const users = ref([]);
const userLoading = ref(false);

const serveDate = ref(tomorrow());
const menuItems = ref([]);

const lines = ref([]);
const deliveryType = ref('pickup');
const slots = ref([]);
const pickupSlot = ref('');
const addresses = ref([]);
const addressId = ref('');
const remark = ref('');
const submitting = ref(false);

const specVisible = ref(false);
const editing = ref(null);
const picked = reactive({});
const editQty = ref(1);

const totalAmount = computed(() => lines.value.reduce((sum, line) => sum + line.amount, 0));

const editAmount = computed(() => {
  if (!editing.value) {
    return 0;
  }
  let unit = editing.value.price || 0;
  (editing.value.groups || []).forEach((group) => {
    const opt = (group.options || []).find((o) => o.optionId === picked[group.name]);
    if (opt) {
      unit += opt.priceDelta || 0;
    }
  });
  return unit * editQty.value;
});

function onOpen() {
  if (menuItems.value.length === 0) {
    loadMenu();
  }
  if (users.value.length === 0) {
    searchUsers('');
  }
  if (slots.value.length === 0) {
    loadSlots();
  }
}

function searchUsers(keyword) {
  userLoading.value = true;
  http
    .get('/admin/user/list', {
      params: { auditStatus: 'approved', page: 1, pageSize: 20, keyword: keyword || undefined },
    })
    .then((page) => {
      users.value = (page && page.list) || [];
    })
    .catch(() => {})
    .finally(() => {
      userLoading.value = false;
    });
}

function onUserChange(id) {
  addressId.value = '';
  addresses.value = [];
  if (!id) {
    return;
  }
  http
    .get('/admin/address/list', { params: { userId: id } })
    .then((list) => {
      const active = (list || []).filter((a) => a.status === 'active');
      addresses.value = active;
      addressId.value = active.length > 0 ? active[0].id : '';
    })
    .catch(() => {});
}

function loadMenu() {
  http
    .get('/menu', { params: { serveDate: serveDate.value } })
    .then((menu) => {
      menuItems.value = ((menu && menu.items) || []).map((item) => ({
        comboId: item.comboId,
        name: item.name,
        price: item.price,
        dishesText: (item.dishes || [])
          .map((d) => (d.qty > 1 ? d.name + '×' + d.qty : d.name))
          .join('、'),
        specGroups: item.specGroups || [],
        remainQty: item.remainQty,
        soldOut: item.remainQty !== null && item.remainQty <= 0,
      }));
    })
    .catch(() => {
      menuItems.value = [];
    });
}

function loadSlots() {
  http
    .get('/config')
    .then((cfg) => {
      let list = [];
      try {
        const parsed = JSON.parse((cfg && cfg.pickupSlots) || '[]');
        list = Array.isArray(parsed) ? parsed : [];
      } catch (e) {
        list = [];
      }
      slots.value = list;
      pickupSlot.value = list.length > 0 ? list[0] : '';
    })
    .catch(() => {
      slots.value = [];
    });
}

function openSpec(item) {
  // 必选组默认选中第一项，免得商家漏选后提交被服务端驳回
  Object.keys(picked).forEach((key) => delete picked[key]);
  (item.specGroups || []).forEach((group) => {
    if (group.required && group.options && group.options.length > 0) {
      picked[group.name] = group.options[0].optionId;
    }
  });
  editing.value = {
    comboId: item.comboId,
    name: item.name,
    price: item.price,
    remainQty: item.remainQty,
    groups: (item.specGroups || []).map((group) => ({
      name: group.name,
      required: !!group.required,
      options: (group.options || []).map((opt) => ({
        optionId: opt.optionId,
        name: opt.name,
        priceDelta: opt.priceDelta || 0,
      })),
    })),
  };
  editQty.value = 1;
  specVisible.value = true;
}

function confirmSpec() {
  const current = editing.value;
  if (!current) {
    return;
  }
  const missing = (current.groups || []).filter((g) => g.required && !picked[g.name]);
  if (missing.length > 0) {
    ElMessage.warning('请选择' + missing[0].name);
    return;
  }

  const optionIds = [];
  const specText = [];
  let unit = current.price || 0;
  (current.groups || []).forEach((group) => {
    const opt = (group.options || []).find((o) => o.optionId === picked[group.name]);
    if (opt) {
      optionIds.push(opt.optionId);
      specText.push(opt.name);
      unit += opt.priceDelta || 0;
    }
  });

  lines.value.push({
    comboId: current.comboId,
    name: current.name,
    optionIds,
    specText: specText.join(' / ') || '无',
    unit,
    qty: editQty.value,
    amount: unit * editQty.value,
  });
  specVisible.value = false;
}

function removeLine(index) {
  lines.value.splice(index, 1);
}

function submit() {
  if (!userId.value) {
    ElMessage.warning('请先选择下单用户');
    return;
  }
  if (lines.value.length === 0) {
    ElMessage.warning('请先添加盒饭');
    return;
  }
  if (deliveryType.value === 'delivery' && !addressId.value) {
    ElMessage.warning('该用户没有可用地址，请先维护');
    return;
  }

  submitting.value = true;
  http
    .post('/admin/order/proxy', {
      userId: userId.value,
      serveDate: serveDate.value,
      deliveryType: deliveryType.value,
      addressId: deliveryType.value === 'delivery' ? addressId.value : null,
      pickupSlot: deliveryType.value === 'pickup' ? pickupSlot.value : null,
      remark: remark.value,
      items: lines.value.map((line) => ({
        comboId: line.comboId,
        qty: line.qty,
        optionIds: line.optionIds,
      })),
    })
    .then((order) => {
      ElMessage.success('已代下单，订单号 ' + order.orderNo + '，取餐码 ' + (order.pickupCode || '—'));
      lines.value = [];
      remark.value = '';
      visible.value = false;
      loadMenu();
      emit('created');
    })
    .catch(() => {})
    .finally(() => {
      submitting.value = false;
    });
}
</script>

<style scoped>
.hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.danger {
  color: var(--el-color-danger);
}

.spec-title {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 12px;
}

.spec-group {
  margin-bottom: 12px;
}

.spec-group-name {
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 6px;
}

.required {
  color: var(--el-color-danger);
  margin-left: 2px;
}

.delta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.qty-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  font-size: 13px;
}

.amount {
  margin-left: auto;
  font-weight: 500;
}
</style>
