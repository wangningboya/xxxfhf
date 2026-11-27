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
 *
 * 业务异常与系统异常按状态码区分（状态码由后端 ErrorCode 枚举逐条定义）：
 * - 4xx：业务异常，body.message 是后端给用户的原话，照原样抛给页面展示；
 * - 5xx：系统异常（空指针、网关错误等），只提示兜底文案，不暴露内部细节。
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
        if (res.statusCode === 429) {
          // 限流拒绝。两层都可能给 429，文案在这里统一：
          //   nginx 按来源 IP —— 响应体由 nginx 的 error_page 改写成同格式 JSON；
          //   应用层按用户 —— 复用 ErrorCode.TOO_MANY_ATTEMPTS。
          // 优先用 body.message：应用层的 429 有更具体的提示（如商家账号锁定
          // 「失败次数过多，账号已锁定，约 N 分钟后自动解锁」），不能被笼统文案盖掉。
          // 拿不到 message 才兜底（例如 nginx 未 reload、仍返回 HTML），
          // 否则会落到下面的系统兜底，变成「服务暂时不可用」，把原因说错。
          reject({ code: body.code || 42901, message: body.message || '请求过快，请刷新后再试' });
          return;
        }
        if (res.statusCode !== 200) {
          // 4xx 是业务异常（后端 GlobalExceptionHandler 按 ErrorCode 映射），message 直接展示；
          // 5xx、网关错误页、body 不是统一响应体都属于系统故障，统一兜底
          if (res.statusCode >= 400 && res.statusCode < 500 && body.message) {
            reject({ code: body.code || res.statusCode, message: body.message });
            return;
          }
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
