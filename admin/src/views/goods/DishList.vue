<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="菜品名称"
        clearable
        style="width: 200px"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px" @change="reload">
        <el-option label="启用" value="enabled" />
        <el-option label="停用" value="disabled" />
      </el-select>
      <el-button @click="reload">查询</el-button>
      <div class="toolbar-right">
        <el-button type="primary" @click="openForm()">新增菜品</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" border size="small" style="width: 100%">
      <el-table-column label="图片" width="80">
        <template #default="{ row }">
          <el-image
            v-if="row.image"
            :src="row.image"
            :preview-src-list="[row.image]"
            fit="cover"
            style="width: 44px; height: 44px; border-radius: 4px"
          />
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="菜品名称" min-width="180" />
      <el-table-column prop="sort" label="排序" width="80" align="center" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'enabled' ? 'success' : 'info'" size="small">
            {{ row.status === 'enabled' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openForm(row)">编辑</el-button>
          <el-button link type="primary" @click="toggleStatus(row)">
            {{ row.status === 'enabled' ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id ? '编辑菜品' : '新增菜品'" width="420px">
      <el-form :model="form" label-width="72px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="30" placeholder="如：红烧肉" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
          <div class="muted">数字小的排前面</div>
        </el-form-item>
        <el-form-item label="图片">
          <el-upload
            :show-file-list="false"
            :http-request="uploadImage"
            accept="image/*"
          >
            <el-image v-if="form.image" :src="form.image" fit="cover" class="upload-preview" />
            <el-button v-else>上传图片</el-button>
          </el-upload>
          <el-button v-if="form.image" link type="danger" @click="form.image = ''">移除</el-button>
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
const keyword = ref('');
const status = ref('');

const dialog = ref(false);
const saving = ref(false);
const form = ref({});

function load() {
  loading.value = true;
  http
    .get('/admin/dish/list', {
      params: { keyword: keyword.value || undefined, status: status.value || undefined },
    })
    .then((list) => {
      rows.value = list || [];
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

function reload() {
  load();
}

function openForm(row) {
  form.value = row
    ? { id: row.id, name: row.name, image: row.image || '', sort: row.sort || 0 }
    : { id: '', name: '', image: '', sort: 0 };
  dialog.value = true;
}

/** 走 axios 上传是为了带上 Authorization；Element 的默认上传不带令牌 */
async function uploadImage(options) {
  const data = new FormData();
  data.append('file', options.file);
  const result = await http.post('/admin/file/upload', data, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  form.value.image = result.url;
  ElMessage.success('已上传');
  if (options.onSuccess) {
    options.onSuccess(result);
  }
}

function onSave() {
  if (!form.value.name || !form.value.name.trim()) {
    ElMessage.warning('请填写菜品名称');
    return;
  }
  saving.value = true;
  http
    .post('/admin/dish/save', {
      id: form.value.id || null,
      name: form.value.name.trim(),
      image: form.value.image || null,
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
    .post('/admin/dish/status', { dishId: row.id, status: next })
    .then(() => load())
    .catch(() => {});
}

function onDelete(row) {
  ElMessageBox.confirm('被盒饭引用的菜品不能删除，只能停用。确定删除「' + row.name + '」？', '删除菜品')
    .then(() => {
      http
        .post('/admin/dish/delete', { dishId: row.id })
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

<style scoped>
.upload-preview {
  width: 72px;
  height: 72px;
  border-radius: 6px;
  display: block;
}
</style>
