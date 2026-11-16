import { defineStore } from 'pinia';
import http, { setToken, getToken } from '../api';

/**
 * 登录态与权限。
 *
 * 权限不放令牌里：服务端每次请求按库里的角色重新判定，被收回立刻生效。
 * 前端这份 permissions 只用来显隐菜单 —— 真正的边界在服务端拦截器。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken(),
    userId: '',
    username: '',
    displayName: '',
    permissions: [],
    loaded: false,
  }),

  getters: {
    logged: (state) => !!state.token,
    /** 通配：拥有全部权限的账号不必逐项判断 */
    allPermissions: (state) => state.permissions,
  },

  actions: {
    has(perm) {
      if (!perm) {
        return true;
      }
      return this.permissions.indexOf(perm) >= 0;
    },

    hasAny(perms) {
      if (!perms || perms.length === 0) {
        return true;
      }
      return perms.some((perm) => this.has(perm));
    },

    async login(username, password) {
      const result = await http.post('/auth/admin/login', { username, password });
      setToken(result.token);
      this.token = result.token;
      this.userId = result.userId;
      this.username = result.username;
      this.displayName = result.displayName;
      this.permissions = result.permissions || [];
      this.loaded = true;
      return result;
    },

    /** 刷新页面后恢复：令牌还在，但权限要重新从库里取 */
    async fetchMe() {
      if (!this.token) {
        return;
      }
      const me = await http.get('/auth/admin/me');
      this.userId = me.userId;
      this.username = me.username;
      this.displayName = me.displayName;
      this.permissions = me.permissions || [];
      this.loaded = true;
    },

    logout() {
      setToken('');
      this.token = '';
      this.userId = '';
      this.username = '';
      this.displayName = '';
      this.permissions = [];
      this.loaded = false;
    },
  },
});
