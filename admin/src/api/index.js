import axios from 'axios';
import { ElMessage } from 'element-plus';

/**
 * 统一请求封装。
 *
 * 服务端约定：HTTP 200 + body.code === 0 为成功；业务错误也用 200 返回，只是 code 非 0。
 * 因此不能只看 HTTP 状态，必须再看 code —— 否则「密码错误」会被当成成功。
 */
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 20000,
});

const TOKEN_KEY = 'meal_admin_token';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = 'Bearer ' + token;
  }
  return config;
});

http.interceptors.response.use(
  (response) => {
    // 文件流（Excel 导出）直接返回原始响应，交给调用方处理
    if (response.config.responseType === 'blob') {
      return response;
    }
    const body = response.data || {};
    if (body.code === 0) {
      return body.data;
    }
    // notified: 已在本拦截器弹过 ElMessage，main.js 的 unhandledrejection 兜底据此去重
    ElMessage.error(body.message || '操作失败');
    return Promise.reject({ code: body.code, message: body.message, notified: true });
  },
  (error) => {
    const status = error.response ? error.response.status : 0;
    const body = (error.response && error.response.data) || {};
    if (status === 401) {
      setToken('');
      // 令牌失效统一回登录页，避免每个页面自己判断
      if (location.hash.indexOf('/login') < 0) {
        location.hash = '#/login';
      }
      ElMessage.error(body.message || '登录已过期，请重新登录');
    } else if (status === 403) {
      ElMessage.error(body.message || '没有该操作权限');
    } else if (status === 429) {
      // 限流拒绝。nginx 按 IP（响应体由 nginx 的 error_page 统一成 JSON）与应用层按用户
      // 都会返回 429，兜底文案保持一致；body.message 存在时优先用它，避免盖掉更具体的提示。
      ElMessage.error(body.message || '请求过快，请刷新后再试');
    } else {
      ElMessage.error(body.message || '服务暂时不可用，请稍后重试');
    }
    return Promise.reject({ code: status, message: body.message, notified: true });
  }
);

/** 下载 Excel：必须带令牌，所以走 axios blob 再用临时链接触发保存 */
export async function download(url, params, filename) {
  const response = await http.get(url, { params, responseType: 'blob' });
  const blob = new Blob([response.data], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(link.href);
}

export default http;
