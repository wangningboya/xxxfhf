/**
 * 精臣 JCAPI 引入适配层。
 *
 * 两个目的：
 * 1. SDK 是 ES Module（`export default`），项目其余文件都是 CommonJS。
 *    微信编译器把 ES Module 转成 CommonJS 后默认导出落在 `exports.default`，
 *    这里两种形态兼容一次取出来，页面里就不必到处写 `.default ||`。
 * 2. `setPlatform` 必须在**任何** SDK 调用之前执行：SDK 内部按平台选择蓝牙 API，
 *    设置晚了会走到非微信分支，现象是「扫描永远返回空列表」，很难查。
 *    放在模块顶层，第一次 require 就完成设置。
 *
 * 注意：本项目 `setting.es6` 为 true，SDK 里的 import/export 依赖这一步转译。
 * 若以后把它关掉，这里会直接报语法错误——不是本文件的 bug。
 */

const JCAPI_MODULE = require('../JCAPI/JCAPI.js');
const JCAPI = JCAPI_MODULE && JCAPI_MODULE.default ? JCAPI_MODULE.default : JCAPI_MODULE;

const COMMON_MODULE = require('../JCAPI/JCAPICommonData.js');
const JCAPICommon = COMMON_MODULE && COMMON_MODULE.default ? COMMON_MODULE.default : COMMON_MODULE;

JCAPI.setPlatform(JCAPICommon.SDKPlatForm.wx);

module.exports = { JCAPI, JCAPICommon };
