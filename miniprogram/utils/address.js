/**
 * 地址启用状态与可用性的共用文案（N-44）。
 *
 * 可用性 = 审核状态 × 地址启用 × 区域启用，三者在服务端
 * `AddressService.usabilityOf` 里按固定顺序判定；这里把同一组字段翻成一句话，
 * **顺序必须与服务端一致**，否则会出现「列表说可用、下单被拒」的错位。
 *
 * 商家端两个页面（区域管理的抽屉、用户与地址）都用它，避免两处各写一份。
 */

/**
 * 地址当前为什么不能下单；可用时返回空串。
 *
 * @param {object} row /admin/address/list 返回的 AddressRow
 * @returns {string}
 */
function unusableText(row) {
  if (!row) {
    return '';
  }
  if (row.status === 'pending') {
    return '待商家确认';
  }
  if (row.status === 'rejected') {
    return row.rejectReason ? '已驳回：' + row.rejectReason : '已驳回';
  }
  if (!row.enabled) {
    return row.disabledReason ? '已停用：' + row.disabledReason : '已停用';
  }
  if (row.regionStatus === 'disabled') {
    return '所属区域已停用';
  }
  return row.usable ? '' : '不可用';
}

/**
 * 启用状态文案（含停用原因）。
 *
 * @param {object} row
 * @returns {string}
 */
function enabledText(row) {
  if (!row) {
    return '';
  }
  if (row.enabled) {
    return '启用';
  }
  return row.disabledReason ? '停用：' + row.disabledReason : '停用';
}

module.exports = {
  unusableText,
  enabledText,
};
