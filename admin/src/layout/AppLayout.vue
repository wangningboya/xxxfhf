<template>
  <el-container class="layout">
    <el-aside width="208px" class="aside">
      <div class="brand">点餐后台</div>
      <el-menu :default-active="activePath" router class="menu">
        <template v-for="item in menus" :key="item.path">
          <el-menu-item :index="item.path">
            <span>{{ item.title }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="title">{{ currentTitle }}</div>
        <div class="account">
          <span class="name">{{ auth.displayName || auth.username }}</span>
          <el-button link type="primary" @click="onLogout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';
import { useAuthStore } from '../stores/auth';
import { useDictStore } from '../stores/dict';

const auth = useAuthStore();
const dict = useDictStore();

// 字典进后台取一次（接口不鉴权，但排在这里可以保证页面渲染时已有文案）
onMounted(() => {
  dict.load();
});
const route = useRoute();
const router = useRouter();

/** 菜单只列有权限的项；权限项本身就是服务端判定的同一套字符串 */
const ALL_MENUS = [
  { path: '/dashboard', title: '工作台', perms: ['order.view', 'order.accept', 'user.audit'] },
  { path: '/order', title: '订单管理', perms: ['order.view', 'order.accept'] },
  { path: '/user', title: '用户审核', perms: ['user.audit', 'user.view'] },
  { path: '/address', title: '地址维护', perm: 'user.address' },
  { path: '/dish', title: '菜品库', perm: 'dish.manage' },
  { path: '/combo', title: '盒饭管理', perm: 'combo.manage' },
  { path: '/menu', title: '每日选品', perm: 'menu.manage' },
  { path: '/spec', title: '规格组', perm: 'combo.manage' },
  { path: '/region', title: '区域管理', perm: 'region.manage' },
  { path: '/prep', title: '备菜统计', perm: 'prep.view' },
  { path: '/finance', title: '对账视图', perm: 'finance.view' },
  { path: '/role', title: '角色权限', perm: 'role.manage' },
  { path: '/dict', title: '数据字典', perm: 'dict.manage' },
  { path: '/config', title: '系统配置', perm: 'config.manage' },
];

const menus = computed(() =>
  ALL_MENUS.filter((item) => (item.perms ? auth.hasAny(item.perms) : auth.has(item.perm)))
);

const activePath = computed(() => route.path);
const currentTitle = computed(() => (route.meta && route.meta.title) || '');

function onLogout() {
  ElMessageBox.confirm('退出后需要重新输入账号密码，确定吗？', '退出登录')
    .then(() => {
      auth.logout();
      router.push('/login');
    })
    .catch(() => {});
}
</script>

<style scoped>
.layout {
  height: 100vh;
}

.aside {
  background: #1f2a37;
  color: #e5e7eb;
}

.brand {
  height: 56px;
  line-height: 56px;
  text-align: center;
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 1px;
}

.menu {
  border-right: none;
  background: transparent;
}

.menu :deep(.el-menu-item) {
  color: #c7ccd4;
}

.menu :deep(.el-menu-item:hover) {
  background: #2a3644;
  color: #fff;
}

.menu :deep(.el-menu-item.is-active) {
  background: #2a3644;
  color: #ffb64d;
}

.header {
  background: #fff;
  border-bottom: 1px solid #eef0f3;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2a37;
}

.account {
  display: flex;
  align-items: center;
  gap: 12px;
}

.name {
  font-size: 14px;
  color: #6b7280;
}

.main {
  background: #f5f6f8;
  padding: 16px;
}
</style>
