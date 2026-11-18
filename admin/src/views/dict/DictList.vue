<template>
  <div class="page-card">
    <div class="split">
      <!-- 左：类型 -->
      <div class="pane pane-types">
        <div class="pane-head">
          <span class="pane-title">字典类型</span>
          <el-button size="small" type="primary" plain @click="openType()">新增</el-button>
        </div>
        <el-scrollbar height="560px">
          <div
            v-for="type in types"
            :key="type.typeCode"
            class="type-item"
            :class="{ 'type-active': type.typeCode === currentType }"
            @click="selectType(type.typeCode)"
          >
            <div class="type-line">
              <span class="type-name">{{ type.typeName }}</span>
              <el-tag v-if="type.builtin" size="small" type="info" effect="plain">内置</el-tag>
            </div>
            <div class="type-code">{{ type.typeCode }} · {{ type.itemCount }} 项</div>
          </div>
        </el-scrollbar>
      </div>

      <!-- 右：项 -->
      <div class="pane pane-items">
        <div class="pane-head">
          <span class="pane-title">
            {{ currentTypeName || '字典项' }}
            <el-tag v-if="currentTypeBuiltin" size="small" type="warning" effect="plain">
              <template v-if="currentTypeExtensible">
                内置：类型不可删，项可自行增删
              </template>
              <template v-else>内置：只能改显示名 / 颜色 / 排序 / 启停，不能增删</template>
            </el-tag>
          </span>
          <div class="pane-actions" v-if="currentType">
            <el-button size="small" :disabled="currentTypeBuiltin" @click="openType(currentTypeType)">
              编辑类型
            </el-button>
            <el-button
              size="small"
              type="danger"
              plain
              :disabled="currentTypeBuiltin"
              @click="onDeleteType"
            >
              删除类型
            </el-button>
            <el-button
              size="small"
              type="primary"
              :disabled="currentTypeBuiltin && !currentTypeExtensible"
              @click="openItem()"
            >
              新增项
            </el-button>
          </div>
        </div>

        <el-empty v-if="!currentType" description="左侧选一个类型" />

        <el-table v-else v-loading="itemLoading" :data="items" border size="small" style="width: 100%">
          <el-table-column prop="itemValue" label="存值" width="150">
            <template #default="{ row }">
              <code>{{ row.itemValue }}</code>
            </template>
          </el-table-column>
          <el-table-column prop="itemLabel" label="显示名" width="150" />
          <el-table-column label="颜色" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.tagType" size="small" :type="tagTypeOf(row.tagType)" effect="plain">
                {{ row.tagType }}
              </el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="sort" label="排序" width="80" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">
                {{ row.status === 'active' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openItem(row)">编辑</el-button>
              <el-button link type="primary" @click="toggleItem(row)">
                {{ row.status === 'active' ? '停用' : '启用' }}
              </el-button>
              <el-button
                link
                type="danger"
                :disabled="row.builtin"
                @click="onDeleteItem(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 类型表单 -->
    <el-dialog v-model="typeDialog" :title="typeForm.isNew ? '新增字典类型' : '编辑字典类型'" width="440px">
      <el-form :model="typeForm" label-width="90px">
        <el-form-item label="类型编码">
          <el-input
            v-model="typeForm.typeCode"
            :disabled="!typeForm.isNew"
            placeholder="如 dish_category，代码里按它取值"
          />
        </el-form-item>
        <el-form-item label="类型名称">
          <el-input v-model="typeForm.typeName" placeholder="如 菜品分类" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="typeForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveType">保存</el-button>
      </template>
    </el-dialog>

    <!-- 项表单 -->
    <el-dialog v-model="itemDialog" :title="itemForm.isNew ? '新增字典项' : '编辑字典项'" width="480px">
      <el-form :model="itemForm" label-width="90px">
        <el-form-item label="存值">
          <el-input
            v-model="itemForm.itemValue"
            :disabled="!itemForm.isNew && itemForm.builtin"
            placeholder="写进业务表的值，如 pending_accept"
          />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="itemForm.itemLabel" placeholder="如 待接单" />
        </el-form-item>
        <el-form-item label="颜色">
          <el-select v-model="itemForm.tagType" clearable placeholder="不设置" style="width: 100%">
            <el-option label="默认 primary" value="primary" />
            <el-option label="成功 success" value="success" />
            <el-option label="警告 warning" value="warning" />
            <el-option label="危险 danger" value="danger" />
            <el-option label="信息 info" value="info" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="itemForm.sort" :min="0" :step="10" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="itemForm.status"
            active-value="active"
            inactive-value="disabled"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../../api';
import { useDictStore } from '../../stores/dict';

const dict = useDictStore();

const types = ref([]);
const items = ref([]);
const currentType = ref('');
const itemLoading = ref(false);
const saving = ref(false);

const typeDialog = ref(false);
const itemDialog = ref(false);
const typeForm = ref({});
const itemForm = ref({});

const currentTypeRow = computed(() => types.value.find((t) => t.typeCode === currentType.value) || null);
const currentTypeName = computed(() => (currentTypeRow.value ? currentTypeRow.value.typeName : ''));
const currentTypeBuiltin = computed(() => !!(currentTypeRow.value && currentTypeRow.value.builtin));
/** 内置类型里允许增删项的那几个（目前只有 pay_method），服务端 TypeView 里算好下发 */
const currentTypeExtensible = computed(
  () => !!(currentTypeRow.value && currentTypeRow.value.extensible)
);
const currentTypeType = computed(() => currentTypeRow.value);

/**
 * 改完字典要让别处立刻用上新文案：本页的增删改都调这里。
 * 不清的话订单列表还是旧状态名，商家会以为没保存成功。
 */
function refreshClientDict() {
  dict.refresh();
}

/** el-tag 的 type 只认 success/warning/danger/info，primary 用默认色 */
function tagTypeOf(value) {
  return ['success', 'warning', 'danger', 'info'].includes(value) ? value : '';
}

function loadTypes(keepSelection) {
  return http
    .get('/admin/dict/types')
    .then((res) => {
      types.value = res || [];
      if (!keepSelection || !currentType.value) {
        const first = types.value[0];
        if (first) {
          selectType(first.typeCode);
        }
      }
    })
    .catch(() => {});
}

function selectType(code) {
  currentType.value = code;
  loadItems();
}

function loadItems() {
  if (!currentType.value) {
    items.value = [];
    return;
  }
  itemLoading.value = true;
  http
    .get('/admin/dict/items', { params: { typeCode: currentType.value } })
    .then((res) => {
      items.value = res || [];
    })
    .catch(() => {})
    .finally(() => {
      itemLoading.value = false;
    });
}

function openType(row) {
  typeForm.value = row
    ? { isNew: false, typeCode: row.typeCode, typeName: row.typeName, remark: row.remark || '' }
    : { isNew: true, typeCode: '', typeName: '', remark: '' };
  typeDialog.value = true;
}

function saveType() {
  if (!typeForm.value.typeCode || !typeForm.value.typeName) {
    ElMessage.warning('类型编码与名称都要填');
    return;
  }
  saving.value = true;
  http
    .post('/admin/dict/type/save', {
      typeCode: typeForm.value.typeCode.trim(),
      typeName: typeForm.value.typeName.trim(),
      remark: typeForm.value.remark,
    })
    .then((saved) => {
      ElMessage.success('已保存');
      typeDialog.value = false;
      if (typeForm.value.isNew && saved) {
        currentType.value = saved.typeCode;
      }
      refreshClientDict();
      loadTypes(true).then(() => loadItems());
    })
    .catch(() => {})
    .finally(() => {
      saving.value = false;
    });
}

function onDeleteType() {
  const row = currentTypeRow.value;
  if (!row) return;
  ElMessageBox.confirm(
    `删除类型「${row.typeName}」会连同它下面的 ${row.itemCount} 个字典项一起删掉，确定吗？`,
    '删除字典类型'
  )
    .then(() => {
      http
        .post('/admin/dict/type/delete', { typeCode: row.typeCode })
        .then(() => {
          ElMessage.success('已删除');
          currentType.value = '';
          refreshClientDict();
          loadTypes(false);
        })
        .catch(() => {});
    })
    .catch(() => {});
}

function openItem(row) {
  itemForm.value = row
    ? {
        isNew: false,
        id: row.id,
        itemValue: row.itemValue,
        itemLabel: row.itemLabel,
        tagType: row.tagType || '',
        sort: row.sort ?? 0,
        status: row.status || 'active',
        builtin: !!row.builtin,
      }
    : {
        isNew: true,
        id: '',
        itemValue: '',
        itemLabel: '',
        tagType: '',
        sort: 0,
        status: 'active',
        builtin: false,
      };
  itemDialog.value = true;
}

function saveItem() {
  if (!itemForm.value.itemValue || !itemForm.value.itemLabel) {
    ElMessage.warning('存值与显示名都要填');
    return;
  }
  saving.value = true;
  http
    .post('/admin/dict/item/save', {
      id: itemForm.value.id || null,
      typeCode: currentType.value,
      itemValue: itemForm.value.itemValue.trim(),
      itemLabel: itemForm.value.itemLabel.trim(),
      tagType: itemForm.value.tagType || null,
      sort: itemForm.value.sort,
      status: itemForm.value.status,
    })
    .then(() => {
      ElMessage.success('已保存');
      itemDialog.value = false;
      refreshClientDict();
      loadItems();
      loadTypes(true);
    })
    .catch(() => {})
    .finally(() => {
      saving.value = false;
    });
}

function toggleItem(row) {
  const next = row.status === 'active' ? 'disabled' : 'active';
  http
    .post('/admin/dict/item/status', { id: row.id, status: next })
    .then(() => {
      refreshClientDict();
      loadItems();
    })
    .catch(() => {});
}

function onDeleteItem(row) {
  ElMessageBox.confirm(`删除字典项「${row.itemLabel}」？业务数据里已有的旧值不会受影响，只是不再有显示名。`, '删除字典项')
    .then(() => {
      http
        .post('/admin/dict/item/delete', { id: row.id })
        .then(() => {
          ElMessage.success('已删除');
          refreshClientDict();
          loadItems();
          loadTypes(true);
        })
        .catch(() => {});
    })
    .catch(() => {});
}

onMounted(() => loadTypes(false));
</script>

<style scoped>
.split {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 16px;
}

.pane {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 14px;
  min-height: 600px;
}

.pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
  min-height: 32px;
}

.pane-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 8px;
}

.pane-actions {
  display: flex;
  gap: 6px;
}

.type-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
}

.type-item:hover {
  background: #f5f7fa;
}

.type-active {
  background: #fff3e0;
}

.type-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.type-name {
  font-size: 14px;
  color: #303133;
}

.type-code {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.muted {
  color: #c0c4cc;
}

code {
  font-size: 12px;
  color: #606266;
}
</style>
