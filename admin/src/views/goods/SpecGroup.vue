<template>
  <div class="page-card">
    <div class="toolbar">
      <span class="muted">规格组只在网页后台维护：小程序里只能选用，不能新建</span>
      <div class="toolbar-right">
        <el-button type="primary" @click="openEdit()">新增规格组</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" border size="small" style="width: 100%">
      <el-table-column prop="name" label="组名" width="160" />
      <el-table-column label="必选" width="80" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.required" type="warning" size="small">必选</el-tag>
          <span v-else class="muted">可选</span>
        </template>
      </el-table-column>
      <el-table-column label="计入备菜" width="100" align="center">
        <template #default="{ row }">{{ row.countInPrep ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="选项" min-width="280">
        <template #default="{ row }">
          <el-tag
            v-for="option in row.options || []"
            :key="option.optionId"
            size="small"
            effect="plain"
            class="opt-tag"
          >
            {{ option.name }}{{ option.priceDelta ? '（' + (option.priceDelta > 0 ? '+' : '') + yuan(option.priceDelta) + '）' : '' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="70" align="center" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'enabled' ? 'success' : 'info'" size="small">
            {{ row.status === 'enabled' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="toggleStatus(row)">
            {{ row.status === 'enabled' ? '停用' : '启用' }}
          </el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawer" :title="form.id ? '编辑规格组' : '新增规格组'" size="560px">
      <el-form :model="form" label-width="86px">
        <el-form-item label="组名" required>
          <el-input v-model="form.name" maxlength="20" placeholder="如：主食 / 辣度" />
        </el-form-item>
        <el-form-item label="必选">
          <el-switch v-model="form.required" />
          <span class="muted">必选时用户不选不能下单</span>
        </el-form-item>
        <el-form-item label="计入备菜">
          <el-switch v-model="form.countInPrep" />
          <span class="muted">开启后备菜统计里会按选项分列（如白米饭 / 杂粮饭）</span>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
        </el-form-item>

        <el-form-item label="选项">
          <div class="option-box">
            <div v-for="(option, index) in form.options" :key="index" class="option-line">
              <el-input v-model="option.name" placeholder="选项名" style="width: 130px" maxlength="20" />
              <el-input-number
                v-model="option.priceDeltaYuan"
                :precision="2"
                :step="1"
                :min="-9999"
                style="width: 130px"
              />
              <el-button link type="danger" @click="removeOption(index)">删除</el-button>
            </div>
            <el-button size="small" @click="addOption">＋ 添加选项</el-button>
            <div class="muted">差价为 0 表示不加钱；正数加价，负数减价（单位：元）</div>
          </div>
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
const drawer = ref(false);
const saving = ref(false);
const form = ref({ options: [] });

function yuan(cents) {
  return (Number(cents || 0) / 100).toFixed(2);
}

function load() {
  loading.value = true;
  http
    .get('/admin/spec/list')
    .then((list) => {
      rows.value = list || [];
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

function openEdit(row) {
  if (row) {
    form.value = {
      id: row.id,
      name: row.name,
      required: !!row.required,
      countInPrep: !!row.countInPrep,
      sort: row.sort || 0,
      options: (row.options || []).map((option, index) => ({
        optionId: option.optionId,
        name: option.name,
        priceDeltaYuan: Number(option.priceDelta || 0) / 100,
        sort: option.sort === undefined ? index + 1 : option.sort,
      })),
    };
  } else {
    form.value = {
      id: '',
      name: '',
      required: true,
      countInPrep: false,
      sort: 0,
      options: [{ optionId: '', name: '', priceDeltaYuan: 0, sort: 1 }],
    };
  }
  drawer.value = true;
}

function addOption() {
  form.value.options.push({
    optionId: '',
    name: '',
    priceDeltaYuan: 0,
    sort: form.value.options.length + 1,
  });
}

function removeOption(index) {
  if (form.value.options.length <= 1) {
    ElMessage.warning('至少保留一个选项');
    return;
  }
  form.value.options.splice(index, 1);
}

function onSave() {
  const data = form.value;
  if (!data.name || !data.name.trim()) {
    ElMessage.warning('请填写组名');
    return;
  }
  const options = (data.options || []).filter((option) => option.name && option.name.trim());
  if (options.length === 0) {
    ElMessage.warning('至少填一个选项');
    return;
  }
  saving.value = true;
  http
    .post('/admin/spec/save', {
      id: data.id || null,
      name: data.name.trim(),
      required: data.required,
      countInPrep: data.countInPrep,
      sort: data.sort,
      options: options.map((option, index) => ({
        optionId: option.optionId || null,
        name: option.name.trim(),
        priceDelta: Math.round(Number(option.priceDeltaYuan || 0) * 100),
        sort: index + 1,
      })),
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

async function toggleStatus(row) {
  const next = row.status === 'enabled' ? 'disabled' : 'enabled';
  const submit = (confirmUnbind) =>
    http.post('/admin/spec/status', {
      specGroupId: row.id,
      status: next,
      confirmUnbind: !!confirmUnbind,
    });

  try {
    await submit(false);
    ElMessage.success('已更新');
    load();
  } catch (e) {
    // 停用被盒饭引用时服务端返回 409，这里拉出受影响清单让商家确认
    if (e && e.code === 409) {
      const affected = await http.get('/admin/spec/affected', { params: { specGroupId: row.id } });
      const names = (affected || []).map((combo) => combo.name).join('、');
      ElMessageBox.confirm(
        '「' + row.name + '」正被以下盒饭使用：' + (names || '（无）') + '。停用会把它从这些盒饭上解除，确定吗？',
        '停用规格组',
        { type: 'warning' }
      )
        .then(() => submit(true).then(() => {
          ElMessage.success('已停用并解除引用');
          load();
        }))
        .catch(() => {});
    }
  }
}

function onDelete(row) {
  ElMessageBox.confirm('删除后无法恢复，确定删除「' + row.name + '」？', '删除规格组')
    .then(() => {
      http
        .post('/admin/spec/delete', { specGroupId: row.id })
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
.opt-tag {
  margin: 0 6px 6px 0;
}

.option-box {
  width: 100%;
  border: 1px solid #eef0f3;
  border-radius: 6px;
  padding: 10px 12px;
}

.option-line {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 0;
}
</style>
