/**
 * 打印相关的用户文案：SDK 错误码、服务端禁打原因、连接失败原因。
 *
 * 集中在一处的原因：同一个错误码会出现在三个地方（进度层、结果页、详情页打印记录），
 * 分散写迟早出现「缺纸」在这里叫「标签纸用完了」、在那里叫「无纸」。
 */

/** SDK 错误码 → 给商家的提示（需求 9.1，只映射会遇到的） */
const ERROR_TEXT = {
  65281: '打印机连接断了，请靠近后重连',
  65287: '打印机盖子没关好',
  65288: '标签纸用完了，装纸后重打',
  65289: '打印机快没电了，先充电',
  65293: '打印机太热，等两分钟再打',
  65294: '纸张卡住了，检查出纸口',
  65295: '标签纸可能没装好或间距不对，请在打印机 App 里做一次「纸张校准」后重打',
  65304: '标签纸没装好，重新装纸并校准间距',
  65305: '纸张类型和设置不符，检查是否换了不同规格的标签纸',
  65286: '纸张状态异常，先校准纸张再重打',
  65280: '打印超时，请重试',
  65283: '打印机还在忙上一单，稍等再试',
  65533: '这台打印机不支持该设置',
};

/** 端上自己产生的失败原因（不是 SDK 错误码），用负数避开真实码段 */
const LOCAL_ERROR = {
  cancelled: -1,
  interrupted: -2,
  render: -3,
  offline: -4,
  timeout: -5,
};

/**
 * 错误码换成一句人话。
 *
 * 认不出来的码统一兜底并带上原始码：热敏机型的错误码表随固件变化，
 * 硬编一份「完整表」只会越编越错，带出原始码反而便于事后查。
 */
function errorText(errCode) {
  if (errCode == null) {
    return '';
  }
  if (errCode === LOCAL_ERROR.cancelled) {
    return '已取消';
  }
  if (errCode === LOCAL_ERROR.interrupted) {
    return '打印中断';
  }
  if (errCode === LOCAL_ERROR.render) {
    return '标签生成失败';
  }
  if (errCode === LOCAL_ERROR.offline) {
    return '打印机未连接';
  }
  if (errCode === LOCAL_ERROR.timeout) {
    return '打印超时';
  }
  return ERROR_TEXT[errCode] || '打印失败（错误码 ' + errCode + '）';
}

/**
 * 结果页里的一行只显示短原因。
 *
 * 长文案（比如「请在打印机 App 里做一次纸张校准」）放进详情提示，不塞进列表，
 * 否则一行会被撑成三行，扫一眼看不出缺哪几张。
 */
function shortErrorText(errCode) {
  if (errCode == null) {
    return '';
  }
  if (errCode === LOCAL_ERROR.cancelled) {
    return '已取消';
  }
  if (errCode === LOCAL_ERROR.interrupted) {
    return '中断';
  }
  if (errCode === LOCAL_ERROR.render) {
    return '生成失败';
  }
  if (errCode === LOCAL_ERROR.timeout) {
    return '超时';
  }
  const full = ERROR_TEXT[errCode];
  if (!full) {
    return '错误 ' + errCode;
  }
  // 取第一句，去掉后面的处置建议
  return full.split(/[，。]/)[0];
}

/** 服务端 skipped 的原因码 → 文案（需求 7.3 第 5 条） */
const SKIP_TEXT = {
  cancelled: '已取消',
  refunded: '已退款',
  refunding: '退款处理中',
  refundRequested: '用户申请取消',
  addressMissing: '缺收货地址',
  noItems: '没有菜品明细',
  notFound: '订单不存在',
};

function skipText(reason) {
  return SKIP_TEXT[reason] || '不可打印';
}

module.exports = { errorText, shortErrorText, skipText, LOCAL_ERROR };
