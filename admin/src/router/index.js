import { createRouter, createWebHashHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';

import Login from '../views/Login.vue';
import AppLayout from '../layout/AppLayout.vue';

/**
 * 每个路由标 perm，未标注的只要登录即可。
 * 守卫只挡「没权限的菜单不该点进去」，接口本身另有服务端校验。
 */
const routes = [
  { path: '/login', component: Login, meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: AppLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '工作台', perms: ['order.view', 'order.accept', 'user.audit'] },
      },
      {
        path: 'order',
        component: () => import('../views/order/OrderList.vue'),
        meta: { title: '订单管理', perms: ['order.view', 'order.accept'] },
      },
      {
        path: 'user',
        component: () => import('../views/user/UserList.vue'),
        meta: { title: '用户管理', perms: ['user.audit', 'user.view'] },
      },
      {
        path: 'address',
        component: () => import('../views/user/AddressList.vue'),
        meta: { title: '地址维护', perm: 'user.address' },
      },
      {
        path: 'dish',
        component: () => import('../views/goods/DishList.vue'),
        meta: { title: '菜品库', perm: 'dish.manage' },
      },
      {
        path: 'combo',
        component: () => import('../views/goods/ComboList.vue'),
        meta: { title: '盒饭管理', perm: 'combo.manage' },
      },
      {
        path: 'menu',
        component: () => import('../views/menu/DailyMenu.vue'),
        meta: { title: '每日选品', perm: 'menu.manage' },
      },
      {
        path: 'spec',
        component: () => import('../views/goods/SpecGroup.vue'),
        meta: { title: '规格组', perm: 'combo.manage' },
      },
      {
        path: 'region',
        component: () => import('../views/region/RegionList.vue'),
        meta: { title: '区域管理', perm: 'region.manage' },
      },
      {
        path: 'prep',
        component: () => import('../views/stat/PrepView.vue'),
        meta: { title: '备菜统计', perm: 'prep.view' },
      },
      {
        path: 'finance',
        component: () => import('../views/stat/FinanceView.vue'),
        meta: { title: '对账视图', perm: 'finance.view' },
      },
      {
        path: 'role',
        component: () => import('../views/role/RoleList.vue'),
        meta: { title: '角色权限', perm: 'role.manage' },
      },
      {
        path: 'dict',
        component: () => import('../views/dict/DictList.vue'),
        meta: { title: '数据字典', perm: 'dict.manage' },
      },
      {
        path: 'config',
        component: () => import('../views/config/ConfigView.vue'),
        meta: { title: '系统配置', perm: 'config.manage' },
      },
      {
        path: 'audit',
        component: () => import('../views/audit/AuditLogList.vue'),
        meta: { title: '操作记录', perm: 'audit.view' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
];

const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true;
  }
  const auth = useAuthStore();
  if (!auth.logged) {
    return { path: '/login' };
  }
  // 刷新后第一次进入：令牌在，但权限还没取回来
  if (!auth.loaded) {
    try {
      await auth.fetchMe();
    } catch (e) {
      auth.logout();
      return { path: '/login' };
    }
  }
  const perms = to.meta.perms;
  if (perms && !auth.hasAny(perms)) {
    return { path: firstAllowed(auth) };
  }
  if (to.meta.perm && !auth.has(to.meta.perm)) {
    return { path: firstAllowed(auth) };
  }
  return true;
});

/** 当前账号能进的第一个页面，避免无权限时来回跳登录页 */
export function firstAllowed(auth) {
  const candidates = [
    ['/dashboard', ['order.view', 'order.accept', 'user.audit']],
    ['/order', ['order.view', 'order.accept']],
    ['/user', ['user.audit', 'user.view']],
    ['/address', ['user.address']],
    ['/dish', ['dish.manage']],
    ['/combo', ['combo.manage']],
    ['/menu', ['menu.manage']],
    ['/spec', ['combo.manage']],
    ['/region', ['region.manage']],
    ['/prep', ['prep.view']],
    ['/finance', ['finance.view']],
    ['/role', ['role.manage']],
    ['/dict', ['dict.manage']],
    ['/config', ['config.manage']],
    ['/audit', ['audit.view']],
  ];
  const found = candidates.filter(([, perms]) => auth.hasAny(perms))[0];
  return found ? found[0] : '/login';
}

export default router;
