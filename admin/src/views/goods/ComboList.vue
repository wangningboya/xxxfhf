<template>
  <div class="page-card">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="盒饭名称"
        clearable
        style="width: 200px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 130px" @change="load">
        <el-option label="已上架" value="on" />
        <el-option label="已下架" value="off" />
      </el-select>
      <el-button @click="load">查询</el-button>
      <div class="toolbar-right">
        <el-button type="primary" @click="openEdit()">新增盒饭</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" border size="small" style="width: 100%">
      <el-table-column label="封面" width="76">
        <template #default="{ row }">
          <el-image
            v-if="row.cover"
            :src="row.cover"
            :preview-src-list="[row.cover]"
            fit="cover"
            style="width: 44px; height: 44px; border-radius: 4px"
          />
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column label="价格" width="100" align="right">
        <template #default="{ row }"><span class="price">¥{{ yuan(row.price) }}</span></template>
      </el-table-column>
      <el-table-column label="组成" min-width="240">
        <template #default="{ row }">
          <span>{{ dishesText(row) || '未配置' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="规格组" width="90" align="center">
        <template #default="{ row }">{{ (row.specGroupIds || []).length }}</template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="70" align="center" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'on' ? 'success' : 'info'" size="small">
            {{ row.status === 'on' ? '在售' : '下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="toggleStatus(row)">
            {{ row.status === 'on' ? '下架' : '上架' }}
          </el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawer" :title="form.id ? '编辑盒饭' : '新增盒饭'" size="640px">
      <el-form :model="form" label-width="88px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="30" placeholder="如：红烧肉套餐" />
        </el-form-item>
        <el-form-item label="价格（元）" required>
          <el-input-number v-model="form.priceYuan" :min="0" :precision="2" :step="1" />
		      <div class="muted">提交时换算成分，界面按元显示</div>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="封面">
          <el-upload :show-file-list="false" :http-request="uploadCover" accept="image/*">
            <el-image v-if="form.cover" :src="form.cover" fit="cover" class="cover-preview" />
            <el-button v-else>上传封面</el-button>
          </el-upload>
          <el-button v-if="form.cover" link type="danger" @click="form.cover = ''">移除</el-button>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.desc" type="textarea" :rows="2" maxlength="80" />
        </el-form-item>

        <el-form-item label="组成菜品">
          <div class="dish-box">
            <div class="muted">勾选菜品并设置份数；备菜按「盒饭份数 × 菜品份数」累加</div>
            <div v-for="dish in dishOptions" :key="dish.dishId" class="dish-line">
              <el-checkbox v-model="dish.checked" @change="(v) => onCheckDish(dish, v)">
                {{ dish.name }}
              </el-checkbox>
              <el-input-number
                v-if="dish.checked"
                v-model="dish.qty"
                :min="1"
                :max="20"
                size="small"
                @change="(v) => onQtyChange(dish, v)"
              />
            </div>
            <el-empty v-if="dishOptions.length === 0" :image-size="60" description="菜品库为空" />
          </div>
        </el-form-item>

        <el-form-item label="启用规格组">
          <el-checkbox-group v-model="form.specGroupIds">
            <el-checkbox v-for="group in specGroups" :key="group.id" :value="group.id">
              {{ group.name }}{{ group.required ? '（必选）' : '' }}
            </el-checkbox>
          </el-checkbox-group>
          <div class="muted">规格组在「规格组」页维护，这里只选用</div>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="保存后，已发布的当天菜单不会自动更新"
            description="要到「每日菜单」撤下 → 保存 → 重新发布才生效；草稿态菜单下次保存时自动用上新配置。"
            style="margin-top: 8px"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-drawer>
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

const drawer = ref(false);
const saving = ref(false);
const form = ref({ dishes: [], specGroupIds: [] });
const dishOptions = ref([]);
const specGroups = ref([]);

function yuan(cents) {
  return (Number(cents || 0) / 100).toFixed(2);
}

function dishesText(row) {
  return (row.dishes || []).map((d) => (d.qty > 1 ? d.name + '×' + d.qty : d.name)).join('、');
}

function load() {
  loading.value = true;
  http
    .get('/admin/combo/list', {
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

function openEdit(row) {
  form.value = {
    id: row ? row.id : '',
    name: row ? row.name : '',
    priceYuan: row ? Number(row.price || 0) / 100 : 0,
    cover: row ? row.cover || '' : '',
    desc: row ? row.desc || '' : '',
    sort: row ? row.sort || 0 : 0,
    specGroupIds: row ? (row.specGroupIds || []).slice() : [],
    dishes: [],
  };
  const picked = {};
  (row && row.dishes ? row.dishes : []).forEach((d) => {
    picked[d.dishId] = d.qty || 1;
  });
  dishOptions.value = dishOptions.value.map((dish) =>
    Object.assign({}, dish, {
      checked: !!picked[dish.dishId],
      qty: picked[dish.dishId] || 1,
    })
  );
  drawer.value = true;
}

function onCheckDish(dish, checked) {
  dishOptions.value = dishOptions.value.map((item) =>
    item.dishId === dish.dishId ? Object.assign({}, item, { checked }) : item
  );
}

function onQtyChange(dish, qty) {
  dishOptions.value = dishOptions.value.map((item) =>
    item.dishId === dish.dishId ? Object.assign({}, item, { qty }) : item
  );
}

async function uploadCover(options) {
  const data = new FormData();
  data.append('file', options.file);
  const result = await http.post('/admin/file/upload', data, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  form.value.cover = result.url;
  ElMessage.success('已上传');
  if (options.onSuccess) {
    options.onSuccess(result);
  }
}

function onSave() {
  const data = form.value;
  if (!data.name || !data.name.trim()) {
    ElMessage.warning('请填写盒饭名称');
    return;
  }
  const picked = dishOptions.value.filter((dish) => dish.checked);
  if (picked.length === 0) {
    ElMessage.warning('至少选择一道菜');
    return;
  }
  const price = Math.round(Number(data.priceYuan || 0) * 100);
  if (price <= 0) {
    ElMessage.warning('请填写价格');
    return;
  }

  saving.value = true;
  http
    .post('/admin/combo/save', {
      id: data.id || null,
      name: data.name.trim(),
      cover: data.cover || null,
      desc: data.desc || null,
      price,
      dishes: picked.map((dish) => ({ dishId: dish.dishId, name: dish.name, qty: dish.qty || 1 })),
      specGroupIds: data.specGroupIds || [],
      sort: data.sort,
    })
    .then(() => {
      ElMessage.success('已保存');
      drawer.value = false;
      load();
    })
    .catch(() => {})
    .finally(() => {
      saving.value = false;
    });
}

function toggleStatus(row) {
  const next = row.status === 'on' ? 'off' : 'on';
  http
    .post('/admin/combo/status', { comboId: row.id, status: next })
    .then(() => load())
    .catch(() => {});
}

function onDelete(row) {
  ElMessageBox.confirm('删除「' + row.name + '」？历史订单不受影响。', '删除盒饭')
    .then(() => {
      http
        .post('/admin/combo/delete', { comboId: row.id })
        .then(() => {
          ElMessage.success('已删除');
          load();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

onMounted(() => {
  load();
  http
    .get('/admin/dish/list', { params: { status: 'enabled' } })
    .then((list) => {
      dishOptions.value = (list || []).map((dish) => ({
        dishId: dish.id,
        name: dish.name,
        checked: false,
        qty: 1,
      }));
    })
    .catch(() => {});
  http
    .get('/admin/spec/list-enabled')
    .then((list) => {
      specGroups.value = list || [];
    })
    .catch(() => {});
});
</script>

<style scoped>
.cover-preview {
  width: 84px;
  height: 84px;
  border-radius: 6px;
  display: block;
}

.dish-box {
  width: 100%;
  border: 1px solid #eef0f3;
  border-radius: 6px;
  padding: 10px 12px;
  max-height: 260px;
  overflow-y: auto;
}

.dish-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
}
</style>
