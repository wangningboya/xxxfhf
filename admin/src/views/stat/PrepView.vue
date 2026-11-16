<template>
  <div class="page-card">
    <div class="toolbar">
      <el-date-picker
        v-model="serveDate"
        type="date"
        value-format="YYYY-MM-DD"
        :clearable="false"
        style="width: 150px"
        @change="load"
      />
      <el-radio-group v-model="tab" size="small">
        <el-radio-button value="dish">按菜品</el-radio-button>
        <el-radio-button value="region">按区域</el-radio-button>
      </el-radio-group>
      <span class="muted">口径：已接单及之后（待接单不计入），只读订单快照</span>
      <div class="toolbar-right">
        <el-button v-if="auth.has('prep.export')" type="primary" @click="onExport">导出 Excel</el-button>
        <span v-else class="muted">当前角色无导出权限</span>
      </div>
    </div>

    <template v-if="tab === 'dish'">
      <el-table v-loading="loading" :data="dishes" border size="small" style="width: 100%">
        <el-table-column prop="name" label="菜品 / 规格项" min-width="180">
          <template #default="{ row }">
            {{ row.name }}
            <el-tag v-if="row.kind === 'spec'" size="small" type="warning" effect="plain">规格</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="groupName" label="所属规格组" width="130">
          <template #default="{ row }">{{ row.groupName || '—' }}</template>
        </el-table-column>
        <el-table-column label="需要份数" width="110" align="right">
          <template #default="{ row }"><span class="price">{{ row.qty }}</span></template>
        </el-table-column>
        <el-table-column label="来自盒饭" min-width="240">
          <template #default="{ row }">{{ (row.comboNames || []).join('、') }}</template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && dishes.length === 0" description="这一天还没有已接单的订单" />
    </template>

    <template v-else>
      <el-table v-loading="loading" :data="regionsWithPickup" border size="small" style="width: 100%">
        <el-table-column label="区域" min-width="150">
          <template #default="{ row }">
            {{ row.regionName }}
            <el-tag v-if="row.selfPickup" size="small" type="success" effect="plain">自取</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="订单数" width="90" align="center">
          <template #default="{ row }">{{ row.orderCount }}</template>
        </el-table-column>
        <el-table-column label="盒饭合计" width="100" align="right">
          <template #default="{ row }"><span class="price">{{ row.qty }}</span></template>
        </el-table-column>
        <el-table-column label="明细" min-width="240">
          <template #default="{ row }">
            {{ (row.items || []).map((item) => item.name + '×' + item.qty).join('、') || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="收货人" min-width="240">
          <template #default="{ row }">
            <div v-for="(contact, index) in row.contacts || []" :key="index">
              {{ contact.contactName }} {{ contact.phone }}
            </div>
            <div v-for="(slot, index) in row.slots || []" :key="'s' + index" class="muted">
              {{ slot.slot }}：{{ slot.qty }} 份
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && regionsWithPickup.length === 0" description="这一天还没有已接单的订单" />
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import http, { download } from '../../api';
import { useAuthStore } from '../../stores/auth';

const auth = useAuthStore();

const serveDate = ref('');
const tab = ref('dish');
const loading = ref(false);
const view = ref({ dishes: [], regions: [], selfPickup: null });

const dishes = computed(() => view.value.dishes || []);

/** 自取栏固定排最后一行，与 Excel 保持一致 */
const regionsWithPickup = computed(() => {
  const list = (view.value.regions || []).slice();
  if (view.value.selfPickup) {
    list.push(Object.assign({ selfPickup: true }, view.value.selfPickup));
  }
  return list;
});

function tomorrow() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

function load() {
  loading.value = true;
  http
    .get('/admin/prep', { params: { serveDate: serveDate.value } })
    .then((result) => {
      view.value = result || { dishes: [], regions: [] };
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

function onExport() {
  download('/admin/prep/export', { serveDate: serveDate.value }, '备菜-' + serveDate.value + '.xlsx')
    .then(() => ElMessage.success('已开始下载'))
    .catch(() => {});
}

onMounted(() => {
  serveDate.value = tomorrow();
  load();
});
</script>
