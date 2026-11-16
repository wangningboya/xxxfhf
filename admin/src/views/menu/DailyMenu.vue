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
      <el-button @click="setDate(1)">明天</el-button>
      <el-button @click="setDate(2)">后天</el-button>
      <el-tag v-if="view.published" type="success" size="small">已发布</el-tag>
      <el-tag v-else type="info" size="small">草稿（用户看不到）</el-tag>
      <el-tag v-if="view.closed" type="danger" size="small">已停业</el-tag>
      <el-tag v-if="view.cutoff" type="warning" size="small">已截单</el-tag>
      <span v-if="view.cutoffAt" class="muted">截单时刻 {{ (view.cutoffAt || '').replace('T', ' ') }}</span>

      <div class="toolbar-right">
        <el-button @click="onCopy">复制上一份</el-button>
        <el-button @click="onClose"> {{ view.closed ? '恢复供应' : '暂停供应' }} </el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
        <el-button v-if="!view.published" type="success" @click="onPublish(true)">发布</el-button>
        <el-button v-else @click="onPublish(false)">撤下</el-button>
      </div>
    </div>

    <el-alert
      v-if="view.cutoff"
      type="warning"
      :closable="false"
      show-icon
      title="已过截单时间"
      description="截单后菜单不能再改（已下的单不受影响）。代客下单不受截单限制，仍可补单。"
      style="margin-bottom: 12px"
    />

    <el-table v-loading="loading" :data="rows" border size="small" style="width: 100%">
      <el-table-column width="60" align="center">
        <template #default="{ row }">
          <el-checkbox v-model="row.checked" @change="(v) => onCheck(row, v)" />
        </template>
      </el-table-column>
      <el-table-column prop="name" label="盒饭" min-width="170" />
      <el-table-column label="价格" width="100" align="right">
        <template #default="{ row }"><span class="price">¥{{ yuan(row.price) }}</span></template>
      </el-table-column>
      <el-table-column label="组成" min-width="220">
        <template #default="{ row }">
          <span class="muted">
            {{ (row.dishes || []).map((d) => d.name + (d.qty > 1 ? '×' + d.qty : '')).join('、') }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="限量（份）" width="150">
        <template #default="{ row }">
          <el-input-number
            v-model="row.limitQty"
            :min="0"
            :max="9999"
            size="small"
            :disabled="!row.checked"
            placeholder="不限量"
          />
        </template>
      </el-table-column>
      <el-table-column label="已售" width="80" align="center">
        <template #default="{ row }">{{ row.soldQty || 0 }}</template>
      </el-table-column>
      <el-table-column label="剩余" width="90" align="center">
        <template #default="{ row }">
          <span v-if="row.limitQty === null || row.limitQty === undefined" class="muted">不限量</span>
          <span v-else>{{ Math.max(row.limitQty - (row.soldQty || 0), 0) }}</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="muted" style="margin-top: 10px">
      勾选哪些盒饭这天供应；限量留空表示不限量。发布后用户端才能看到并下单。
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../../api';

const serveDate = ref('');
const view = ref({ items: [], published: false, closed: false, cutoff: false });
const rows = ref([]);
const loading = ref(false);
const saving = ref(false);

function yuan(cents) {
  return (Number(cents || 0) / 100).toFixed(2);
}

function dateStr(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day;
}

function setDate(offset) {
  serveDate.value = dateStr(offset);
  load();
}

/**
 * 菜单编辑页把「上架盒饭」和「已选盒饭」合成一张表：
 * 勾选的来自当天菜单，未勾选的来自盒饭库 —— 否则商家得在两个列表之间对照着勾。
 */
function load() {
  loading.value = true;
  Promise.all([
    http.get('/admin/menu/view', { params: { serveDate: serveDate.value } }),
    http.get('/admin/combo/list', { params: { status: 'on' } }),
  ])
    .then(([menu, combos]) => {
      view.value = menu || { items: [] };
      const chosen = {};
      (menu.items || []).forEach((item) => {
        chosen[item.comboId] = item.limitQty;
      });

      const merged = [];
      const seen = {};
      // 先放已选的（可能已下架，仍要能看到并取消勾选）
      (menu.items || []).forEach((item) => {
        seen[item.comboId] = true;
        merged.push({
          comboId: item.comboId,
          name: item.name,
          price: item.price,
          dishes: item.dishes || [],
          soldQty: item.soldQty || 0,
          checked: true,
          limitQty: item.limitQty === null || item.limitQty === undefined ? null : item.limitQty,
          offline: item.status !== 'on',
        });
      });
      (combos || []).forEach((combo) => {
        if (seen[combo.comboId || combo.id]) {
          return;
        }
        merged.push({
          comboId: combo.comboId || combo.id,
          name: combo.name,
          price: combo.price,
          dishes: combo.dishes || [],
          soldQty: 0,
          checked: false,
          limitQty: null,
        });
      });
      rows.value = merged;
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

function onCheck(row, checked) {
  if (checked) {
    row.limitQty = null;
  }
}

function payload() {
  return {
    serveDate: serveDate.value,
    items: rows.value
      .filter((row) => row.checked)
      .map((row) => ({
        comboId: row.comboId,
        limitQty: row.limitQty === '' ? null : row.limitQty,
      })),
  };
}

function onSave() {
  saving.value = true;
  http
    .post('/admin/menu/save', payload())
    .then(() => {
      ElMessage.success('已保存');
      load();
    })
    .catch(() => {})
    .finally(() => {
      saving.value = false;
    });
}

function onCopy() {
  http
    .get('/admin/menu/copy', { params: { serveDate: serveDate.value } })
    .then((items) => {
      if (!items || items.length === 0) {
        ElMessage.warning('之前没有可复制的菜单');
        return;
      }
      const chosen = {};
      items.forEach((item) => {
        chosen[item.comboId] = item.limitQty;
      });
      rows.value = rows.value.map((row) =>
        Object.assign({}, row, {
          checked: chosen[row.comboId] !== undefined,
          limitQty: chosen[row.comboId] === undefined ? null : chosen[row.comboId],
        })
      );
      ElMessage.success('已按上一份预填，确认后点保存');
    })
    .catch(() => {});
}

function onPublish(published) {
  const action = published ? '发布' : '撤下';
  ElMessageBox.confirm(
    published
      ? '发布后用户端就能看到并下单，确定发布 ' + serveDate.value + ' 的菜单？'
      : '撤下后用户端看不到这份菜单，已下的单不受影响。确定？',
    action + '菜单'
  )
    .then(() => {
      http
        .post('/admin/menu/publish', { serveDate: serveDate.value, published })
        .then(() => {
          ElMessage.success('已' + action);
          load();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

function onClose() {
  const next = !view.value.closed;
  ElMessageBox.confirm(
    next ? '暂停后该供餐日不能下单，已下的单不受影响。确定？' : '恢复后该供餐日可正常下单。',
    next ? '暂停供应' : '恢复供应'
  )
    .then(() => {
      http
        .post('/admin/menu/close', { serveDate: serveDate.value, closed: next })
        .then(() => {
          ElMessage.success(next ? '已暂停' : '已恢复');
          load();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

onMounted(() => {
  setDate(1);
});
</script>
