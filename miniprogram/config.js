/**
 * 服务端地址配置。
 *
 * 开发：微信开发者工具需勾选「不校验合法域名」，才能用 http://127.0.0.1 联调。
 * 上线：必须是已备案的 https 域名，且在小程序后台「开发设置 → 服务器域名」里登记。
 */
module.exports = {
  // 本机联调
  apiBase: 'http://127.0.0.1:8080',
  // 上线前改成正式域名，例如：https://order.example.com
  // apiBase: 'https://order.example.com',
};
