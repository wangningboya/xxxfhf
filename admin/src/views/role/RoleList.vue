<template>
  <div class="page-card">
    <el-tabs v-model="tab">
      <el-tab-pane label="角色与权限" name="role">
        <div class="toolbar">
          <span class="muted">
            内置「商家」是超管角色，权限项不可改、不可删；新建角色按需勾选权限
          </span>
          <div class="toolbar-right">
            <el-button type="primary" @click="openRole()">新增角色</el-button>
          </div>
        </div>

        <el-table v-loading="loading" :data="roles" border size="small" style="width: 100%">
          <el-table-column prop="name" label="角色" width="180">
            <template #default="{ row }">
              {{ row.name }}
              <el-tag v-if="row.builtin" size="small" type="warning" effect="plain">内置</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="权限" min-width="360">
            <template #default="{ row }">
              <el-tag
                v-for="code in row.permissions || []"
                :key="code"
                size="small"
                effect="plain"
                class="tag"
              >
                {{ nameOf(code) }}
              </el-tag>
              <span v-if="!row.permissions || row.permissions.length === 0" class="muted">未分配任何权限</span>
            </template>
          </el-table-column>
          <el-table-column label="在用账号" width="90" align="center">
            <template #default="{ row }">{{ row.memberCount }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openRole(row)">编辑</el-button>
              <el-button v-if="!row.builtin" link type="danger" @click="onDeleteRole(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="账号授权" name="account">
        <div class="toolbar">
          <span class="muted">只列出商家账号；群成员是微信登录，不参与后台角色</span>
        </div>

        <el-table :data="accounts" border size="small" style="width: 100%">
          <el-table-column prop="username" label="账号" width="150" />
          <el-table-column prop="displayName" label="显示名" width="150" />
          <el-table-column label="角色" min-width="240">
            <template #default="{ row }">
              <el-tag v-for="name in row.roleNames || []" :key="name" size="small" class="tag">
                {{ name }}
              </el-tag>
              <span v-if="!row.roleNames || row.roleNames.length === 0" class="muted">未分配</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.auditStatus === 'approved' ? 'success' : 'info'" size="small">
                {{ row.auditStatus === 'approved' ? '正常' : row.auditStatus }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="登录状态" width="160">
            <template #default="{ row }">
              <el-tag v-if="row.lockedUntil" type="danger" size="small">锁定中</el-tag>
              <span v-else-if="row.loginFailCount > 0" class="muted">已失败 {{ row.loginFailCount }} 次</span>
              <span v-else class="muted">正常</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openAssign(row)">分配角色</el-button>
              <el-button v-if="row.lockedUntil" link type="danger" @click="onUnlock(row)">解锁</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 角色编辑 -->
    <el-dialog v-model="dialog" :title="form.id ? '编辑角色' : '新增角色'" width="560px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="角色名" required>
          <el-input v-model="form.name" maxlength="64" placeholder="如：只负责接单的店员" />
        </el-form-item>
        <el-form-item label="权限项">
          <el-alert
            v-if="form.builtin"
            type="info"
            :closable="false"
            show-icon
            title="内置超管角色拥有全部权限，不可修改"
            style="margin-bottom: 10px"
          />
          <div class="perm-box">
            <div v-for="group in groupedPermissions" :key="group.title" class="perm-group">
              <div class="perm-title">{{ group.title }}</div>
              <el-checkbox
                v-for="item in group.items"
                :key="item.code"
                v-model="form.permissions"
                :value="item.code"
                :disabled="form.builtin"
              >
                {{ item.name }}
                <span class="muted">{{ item.desc }}</span>
              </el-checkbox>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveRole">保存</el-button>
      </template>
    </el-dialog>

    <!-- 账号授权 -->
    <el-dialog v-model="assignDialog" title="分配角色" width="420px">
      <el-form label-width="72px">
        <el-form-item label="账号">{{ assignTarget.displayName || assignTarget.username }}</el-form-item>
        <el-form-item label="角色">
          <el-select v-model="assignRoles" multiple placeholder="选择角色" style="width: 100%">
            <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
          </el-select>
          <div class="muted">至少保留一个角色，否则该账号登录后没有任何功能可用</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignDialog = false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="onAssign">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../../api';

const tab = ref('role');
const roles = ref([]);
const permissions = ref([]);
const accounts = ref([]);
const loading = ref(false);

const dialog = ref(false);
const saving = ref(false);
const form = ref({ permissions: [] });

const assignDialog = ref(false);
const assigning = ref(false);
const assignTarget = ref({});
const assignRoles = ref([]);

/**
 * 按前缀把权限项摆进四组。
 *
 * 这里用白名单正则，**新增权限码时必须同步加进对应组**，否则该项在弹窗里
 * 根本不会渲染出来（能存进库、却勾不到）—— `dict.manage` 就这样漏过一次。
 */
const groupedPermissions = computed(() => [
  { title: '商品与菜单', items: permissions.value.filter((p) => /^(dish|combo|menu)\./.test(p.code)) },
  {
    title: '用户与地址',
    items: permissions.value.filter((p) => /^(user|region)\./.test(p.code)),
  },
  { title: '订单与收款', items: permissions.value.filter((p) => /^(order|finance)\./.test(p.code)) },
  {
    title: '统计与配置',
    items: permissions.value.filter((p) => /^(prep|config|role|dict|audit)\./.test(p.code)),
  },
]);

function nameOf(code) {
  const found = permissions.value.filter((p) => p.code === code)[0];
  return found ? found.name : code;
}

function load() {
  loading.value = true;
  Promise.all([
    http.get('/admin/role/list'),
    http.get('/admin/role/permissions'),
    http.get('/admin/role/accounts'),
  ])
    .then(([roleList, permList, accountList]) => {
      roles.value = roleList || [];
      permissions.value = permList || [];
      accounts.value = accountList || [];
    })
    .catch(() => {})
    .finally(() => {
      loading.value = false;
    });
}

function openRole(row) {
  form.value = row
    ? {
        id: row.id,
        name: row.name,
        builtin: !!row.builtin,
        permissions: (row.permissions || []).slice(),
      }
    : { id: '', name: '', builtin: false, permissions: [] };
  dialog.value = true;
}

function onSaveRole() {
  if (!form.value.name || !form.value.name.trim()) {
    ElMessage.warning('请填写角色名');
    return;
  }
  saving.value = true;
  http
    .post('/admin/role/save', {
      id: form.value.id || null,
      name: form.value.name.trim(),
      permissions: form.value.permissions,
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

function onDeleteRole(row) {
  ElMessageBox.confirm('删除后不可恢复，确定删除「' + row.name + '」？', '删除角色')
    .then(() => {
      http
        .post('/admin/role/delete', { id: row.id })
        .then(() => {
          ElMessage.success('已删除');
          load();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

function openAssign(row) {
  assignTarget.value = row;
  assignRoles.value = (row.roleIds || []).slice();
  assignDialog.value = true;
}

function onAssign() {
  if (assignRoles.value.length === 0) {
    ElMessage.warning('至少保留一个角色');
    return;
  }
  assigning.value = true;
  http
    .post('/admin/role/assign', { userId: assignTarget.value.userId, roleIds: assignRoles.value })
    .then(() => {
      ElMessage.success('已保存');
      assignDialog.value = false;
      load();
    })
    .catch(() => {})
    .finally(() => {
      assigning.value = false;
    });
}

/** 解除登录锁定：密码连续失败 3 次会锁 30 分钟，这里是提前放行 */
function onUnlock(row) {
  ElMessageBox.confirm(
    '解除「' + (row.displayName || row.username) + '」的登录锁定？解除后可立即重新登录。',
    '解锁账号'
  )
    .then(() => {
      http
        .post('/admin/role/unlock', { userId: row.userId })
        .then(() => {
          ElMessage.success('已解锁');
          load();
        })
        .catch(() => {});
    })
    .catch(() => {});
}

onMounted(load);
</script>

<style scoped>
.tag {
  margin: 0 6px 6px 0;
}

.perm-box {
  width: 100%;
  border: 1px solid #eef0f3;
  border-radius: 6px;
  padding: 10px 12px;
  max-height: 320px;
  overflow-y: auto;
}

.perm-group {
  margin-bottom: 10px;
}

.perm-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2a37;
  margin: 6px 0;
}

.perm-group :deep(.el-checkbox) {
  display: flex;
  height: auto;
  margin-bottom: 6px;
  align-items: flex-start;
}
</style>
