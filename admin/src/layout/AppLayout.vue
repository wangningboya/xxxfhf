<template>
  <el-container class="layout">
    <el-aside width="208px" class="aside">
      <div class="brand">点餐后台</div>
      <el-menu
        :default-active="activePath"
        :default-openeds="defaultOpeneds"
        router
        class="menu"
      >
        <el-menu-item v-if="showDashboard" :index="DASHBOARD.path">
          <span>{{ DASHBOARD.title }}</span>
        </el-menu-item>

        <template v-for="group in visibleGroups" :key="group.key">
          <!-- 组里只剩一项直接平铺：点开一次只为点一个链接，多这一层是负担 -->
          <el-menu-item v-if="group.items.length === 1" :index="group.items[0].path">
            <span>{{ group.items[0].title }}</span>
          </el-menu-item>
          <el-sub-menu v-else :index="group.key">
            <template #title>
              <el-icon class="group-icon"><component :is="group.icon" /></el-icon>
              <span>{{ group.title }}</span>
            </template>
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <span>{{ item.title }}</span>
            </el-menu-item>
          </el-sub-menu>
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
import { DataLine, Goods, Setting, Tickets } from '@element-plus/icons-vue';

const auth = useAuthStore();
const dict = useDictStore();

// 字典进后台取一次（接口不鉴权，但排在这里可以保证页面渲染时已有文案）
onMounted(() => {
  dict.load();
});
const route = useRoute();
const router = useRouter();

/** 工作台单独摆在最上面，不归任何组 —— 它是落地页，不在分类里找 */
const DASHBOARD = {
  path: '/dashboard',
  title: '工作台',
  perms: ['order.view', 'order.accept', 'user.audit'],
};

/**
 * 菜单按「干什么」分四组，权限项本身就是服务端判定的同一套字符串。
 *
 * 分组的收益是扫视成本，不是高度：全展开时组标题反而多占了行。
 * 因此两处配套规则：
 * 1. 组内可见项只剩一个时平铺、不显示组标题 —— 小角色（如接单员只认领单）
 *    看到的仍是短平列表，不会为了一个链接多点一次；
 * 2. 组内一项都不可见的整组隐藏，不出现空组。
 */
const MENU_GROUPS = [
  {
    key: 'daily',
    title: '日常经营',
    icon: Tickets,
    items: [
      { path: '/order', title: '订单管理', perms: ['order.view', 'order.accept'] },
      { path: '/user', title: '用户审核', perms: ['user.audit', 'user.view'] },
      { path: '/address', title: '地址维护', perm: 'user.address' },
      { path: '/region', title: '区域管理', perm: 'region.manage' },
    ],
  },
  {
    key: 'goods',
    title: '商品与菜单',
    icon: Goods,
    items: [
      { path: '/dish', title: '菜品库', perm: 'dish.manage' },
      { path: '/combo', title: '盒饭管理', perm: 'combo.manage' },
      { path: '/spec', title: '规格组', perm: 'combo.manage' },
      { path: '/menu', title: '每日选品', perm: 'menu.manage' },
    ],
  },
  {
    key: 'data',
    title: '数据与对账',
    icon: DataLine,
    items: [
      { path: '/prep', title: '备菜统计', perm: 'prep.view' },
      { path: '/finance', title: '对账视图', perm: 'finance.view' },
    ],
  },
  {
    key: 'system',
    title: '系统设置',
    icon: Setting,
    items: [
      { path: '/role', title: '角色权限', perm: 'role.manage' },
      { path: '/dict', title: '数据字典', perm: 'dict.manage' },
      { path: '/config', title: '系统配置', perm: 'config.manage' },
      { path: '/audit', title: '操作记录', perm: 'audit.view' },
    ],
  },
];

function canSee(item) {
  return item.perms ? auth.hasAny(item.perms) : auth.has(item.perm);
}

const showDashboard = computed(() => canSee(DASHBOARD));

const visibleGroups = computed(() =>
  MENU_GROUPS.map((group) => ({ ...group, items: group.items.filter(canSee) })).filter(
    (group) => group.items.length > 0
  )
);

/** 默认全展开，可手动收起；只有真分组的才需要展开 */
const defaultOpeneds = computed(() =>
  visibleGroups.value.filter((group) => group.items.length > 1).map((group) => group.key)
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
  overflow-y: auto;
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
  /* 行高压到 38：原来吃 element 默认的 56，14 项就 784px，
     一屏放不下只能滚 —— 菜单「太多」的观感有一半来自这里 */
  --el-menu-item-height: 38px;
  --el-menu-sub-item-height: 38px;
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

/* 组标题比菜单项轻一档：小一号字、偏灰，靠层级而不是粗分割线分组 */
.menu :deep(.el-sub-menu) {
  border-top: 0.5px solid rgba(255, 255, 255, 0.07);
}

.menu :deep(.el-sub-menu__title) {
  height: 34px;
  line-height: 34px;
  font-size: 12px;
  letter-spacing: 0.5px;
  color: #8792a2;
}

.menu :deep(.el-sub-menu__title:hover) {
  background: #2a3644;
  color: #c7ccd4;
}

/* 收起状态下点进子项，组标题也要有反应，否则看不出自己在哪一组 */
.menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #c7ccd4;
}

.menu :deep(.el-sub-menu__title .el-icon) {
  margin-right: 7px;
  font-size: 14px;
}

.menu :deep(.el-sub-menu__icon-arrow) {
  font-size: 12px;
  color: #6b7a8d;
}

/* 子项缩进，跟组标题拉开层次 */
.menu :deep(.el-sub-menu .el-menu-item) {
  min-width: 0;
  padding-left: 38px !important;
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
