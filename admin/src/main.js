import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ElMessage } from 'element-plus';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import App from './App.vue';
import router from './router';
import './styles.css';

const app = createApp(App);

/**
 * 全局错误兜底。
 *
 * admin 是自己人用的，错误细节直接亮出来（toast 显示 message、控制台留完整堆栈），
 * 不做静默吞错——组件里写得再小心，也总有漏网之鱼。
 */
app.config.errorHandler = (err, instance, info) => {
  const componentName = instance && instance.$options ? instance.$options.name || instance.$options.__name : '';
  console.error(`[Vue 错误] 触发点: ${info} 组件: ${componentName || '(匿名)'}`, err);
  ElMessage.error(err && err.message ? err.message : '页面发生未知错误');
};

/** 路由级兜底：懒加载 chunk 失败、守卫抛错都会走到这里 */
router.onError((error, to) => {
  console.error(`[路由错误] 目标: ${to && to.fullPath}`, error);
  ElMessage.error(error && error.message ? `页面加载失败：${error.message}` : '页面加载失败，请刷新重试');
});

/**
 * 未处理的 Promise 拒绝兜底。
 *
 * axios 拦截器 reject 时带 notified 标记且已弹过 ElMessage，
 * 这里只记日志不再弹，避免同一错误弹两条。
 */
window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason;
  console.error('[未处理的 Promise 拒绝]', reason);
  if (!(reason && reason.notified)) {
    ElMessage.error(reason && reason.message ? reason.message : '发生未知错误');
  }
});

app.use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app');
