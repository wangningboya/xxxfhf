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

    <!--
      未接单提示（v3.50 / F-G11）：备菜数字只算「已接单及之后」，而截单之后往往还有一批单
      挂在待接单。不提示的话，商家照着当前数字备料，第二天一定少做 ——
      这是这个业务最痛的一种错，必须在主界面上说出来。
    -->
    <el-alert
      v-if="pendingAcceptCount > 0"
      type="warning"
      :closable="false"
      show-icon
      class="pending-tip"
    >
      <template #title>
        另有 <b>{{ pendingAcceptCount }}</b> 笔待接单（约 <b>{{ pendingAcceptQty }}</b> 份）
        未计入下方数字，接单后刷新才会增加
      </template>
      <el-button type="warning" link @click="$router.push('/order')">去接单</el-button>
    </el-alert>

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
            <!-- 备菜只关心「这份给谁」：姓名够分拣，电话在这一页没有用途，不放 -->
            <div v-for="(contact, index) in row.contacts || []" :key="index">
              {{ contact.contactName }}
            </div>
            <div v-for="(slot, index) in row.slots || []" :key="'s' + index" class="muted">
              {{ slot.slot }}：{{ slot.qty }} 份
            </div>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="180">
          <template #default="{ row }">
            <div v-for="(contact, index) in row.contacts || []" :key="index" class="remark-text">
              <template v-if="contact.remark">{{ contact.remark }}</template>
            </div>
            <span v-if="!(row.contacts || []).some((c) => c.remark)" class="muted">—</span>
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

/** 同日仍待接单的笔数与份数：不计入备菜数字，只用于顶部提示 */
const pendingAcceptCount = computed(() => view.value.pendingAcceptCount || 0);
const pendingAcceptQty = computed(() => view.value.pendingAcceptQty || 0);

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

<style scoped>
/* 备注：分拣与装袋要照着做，给它一点存在感 */
.remark-text {
  color: #a86b00;
  word-break: break-all;
}
/* 未接单提示：与表格留出间距，别让人误以为它属于表头 */
.pending-tip {
  margin: 12px 0;
}
</style>
