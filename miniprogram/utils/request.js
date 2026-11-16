const { apiBase } = require('../config');

/** 令牌存在本机，7 天有效；过期后 401，页面引导重新进入小程序即可。 */
const TOKEN_KEY = 'meal_token';

function getToken() {
  return wx.getStorageSync(TOKEN_KEY) || '';
}

function setToken(token) {
  if (token) {
    wx.setStorageSync(TOKEN_KEY, token);
  } else {
    wx.removeStorageSync(TOKEN_KEY);
  }
}

/**
 * 统一请求。
 *
 * 服务端约定：HTTP 200 + body.code === 0 为成功，其余 code 按业务错误处理。
 * 不把原始 HTTP 细节暴露给页面，页面只关心 message，便于直接展示。
 */
function request(options) {
  const { url, method = 'GET', data, auth = true } = options;

  return new Promise((resolve, reject) => {
    const header = { 'Content-Type': 'application/json' };
    const token = getToken();
    if (auth && token) {
      header.Authorization = 'Bearer ' + token;
    }

    wx.request({
      url: apiBase + url,
      method,
      data,
      header,
      timeout: 15000,
      success(res) {
        const body = res.data || {};

        if (res.statusCode === 401) {
          // 令牌失效：清掉本地令牌，让调用方决定是否重新登录
          setToken('');
          reject({ code: 40101, message: body.message || '登录已过期，请重新进入小程序' });
          return;
        }
        if (res.statusCode === 403) {
          reject({ code: 40301, message: body.message || '没有操作权限' });
          return;
        }
        if (res.statusCode !== 200) {
          reject({ code: res.statusCode, message: '服务暂时不可用，请稍后重试' });
          return;
        }
        if (body.code === 0) {
          resolve(body.data);
          return;
        }
        reject({ code: body.code, message: body.message || '操作失败' });
      },
      fail() {
        reject({ code: -1, message: '网络异常，请检查网络后重试' });
      },
    });
  });
}

/** 统一弹错误提示，避免每个页面重复写 showToast 参数。 */
function toastError(err) {
  const message = (err && err.message) || '操作失败';
  wx.showToast({ title: message, icon: 'none', duration: 2500 });
}

module.exports = { request, getToken, setToken, toastError };
