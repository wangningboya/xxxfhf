import { defineStore } from 'pinia';
import http from '../api';

/**
 * 数据字典（`GET /dict`）。
 *
 * 字典是「值固定、显示名会变」的展示配置：商家在后台把「待接单」改成「待处理」，
 * 前端不该为此发版。所以登录后取一次全量，之后各页面同步读。
 *
 * 两条约定：
 * 1. **同步读**。组件里直接 `dict.label(...)`，不 await；没加载完时返回 fallback，
 *    照旧显示代码里的旧文案 —— 绝不出现空白标签。
 * 2. **改完即时生效**。「数据字典」页保存后调 `refresh()`，不用刷新浏览器。
 *
 * 不做 localStorage 持久化：后台是桌面浏览器，一次请求不到 5KB，
 * 缓存反而会让「改完要刷新一次」这种问题藏起来。
 */
export const useDictStore = defineStore('dict', {
  state: () => ({
    /** { typeCode: [{ itemValue, itemLabel, tagType, sort }] } */
    data: {},
    loaded: false,
    loading: null,
  }),

  actions: {
    async load() {
      if (this.loaded) {
        return this.data;
      }
      if (this.loading) {
        return this.loading;
      }
      this.loading = http
        .get('/dict')
        .then((raw) => {
          this.data = this.normalize(raw);
          this.loaded = true;
          return this.data;
        })
        .catch(() => this.data)
        .finally(() => {
          this.loading = null;
        });
      return this.loading;
    },

    /** 字典改完调用：丢掉旧快照重新取，订单列表等处立刻用上新文案 */
    refresh() {
      this.loaded = false;
      return this.load();
    },

    normalize(raw) {
      const result = {};
      if (!raw || typeof raw !== 'object') {
        return result;
      }
      Object.keys(raw).forEach((typeCode) => {
        const list = raw[typeCode];
        if (!Array.isArray(list)) {
          return;
        }
        result[typeCode] = list
          .filter((item) => item && item.itemValue)
          .map((item) => ({
            itemValue: item.itemValue,
            itemLabel: item.itemLabel || item.itemValue,
            tagType: item.tagType || 'default',
            sort: Number(item.sort || 0),
          }))
          .sort((a, b) => a.sort - b.sort);
      });
      return result;
    },

    items(typeCode) {
      return this.data[typeCode] || [];
    },

    /** 存值 → 显示名。缺项回退 fallback，绝不返回空 */
    label(typeCode, value, fallback) {
      if (!value) {
        return fallback || '';
      }
      const found = this.items(typeCode).find((item) => item.itemValue === value);
      return (found && found.itemLabel) || fallback || value;
    },

    /** 存值 → el-tag 的 type（primary / success / warning / danger / info） */
    tagType(typeCode, value) {
      const found = this.items(typeCode).find((item) => item.itemValue === value);
      return (found && found.tagType) || 'info';
    },

    /**
     * 生成筛选下拉的选项。字典没加载出来时用 fallback，两边不一致时字典赢。
     * 停用的项不会出现在这里（服务端只下发 active）。
     */
    options(typeCode, fallback) {
      const list = this.items(typeCode);
      return list.length
        ? list.map((item) => ({ value: item.itemValue, label: item.itemLabel }))
        : fallback || [];
    },
  },
});
