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
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'enabled' ? 'success' : 'info'" size="small">
            {{ row.status === 'enabled' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openForm(row)">编辑</el-button>
          <el-button link type="primary" @click="toggleStatus(row)">
            {{ row.status === 'enabled' ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

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
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../../api';

const rows = ref([]);
const loading = ref(false);
const dialog = ref(false);
const saving = ref(false);
const form = ref({});

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

onMounted(load);
</script>
