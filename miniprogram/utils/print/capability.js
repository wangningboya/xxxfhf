/**
 * 机型能力表：浓度范围、每毫米像素倍率、DPI。
 *
 * 数据与精臣官方示例 `printer-capability.js` 保持一致，只保留会用到的机型。
 * **B3S / B3S_P = 8 像素每毫米、浓度 1~5、默认 3**，标签坐标表就是按 8px/mm 算的
 * （70×40mm → 560×320px）。
 *
 * 匹配用前缀且按长度倒序：`B21_Pro` 必须排在 `B21`、`B2` 前面，否则会被短前缀抢先命中。
 */

const CAPABILITY_ROWS = [
  ['B32', 8, 1, 15, 11.81, 300],
  ['Z401', 8, 1, 15, 11.81, 300],
  ['M2', 3, 1, 5, 11.81, 300],
  ['M3', 3, 1, 5, 11.81, 300],
  ['B1_Pro', 3, 1, 5, 11.81, 300],
  ['B2_Pro', 3, 1, 5, 11.81, 300],
  ['B21_Pro', 3, 1, 5, 11.81, 300],
  ['B1', 3, 1, 5, 8, 200],
  ['B2', 3, 1, 5, 8, 200],
  ['B21', 3, 1, 5, 8, 200],
  ['B203', 3, 1, 5, 8, 200],
  ['B3S', 3, 1, 5, 8, 200],
  ['B3S_P', 3, 1, 5, 8, 200],
  ['B31', 3, 1, 5, 8, 200],
  ['B4', 3, 1, 5, 8, 200],
  ['B4_Pro', 3, 1, 5, 11.81, 300],
  ['K2', 3, 1, 5, 8, 200],
  ['K3', 3, 1, 5, 8, 200],
  ['K3_W', 3, 1, 5, 8, 200],
  ['', 3, 1, 5, 8, 200],
];

const CAPABILITIES = CAPABILITY_ROWS.map((row) => ({
  modelPrefix: row[0],
  displayName: row[0] || '其他',
  defaultDensity: row[1],
  minDensity: row[2],
  maxDensity: row[3],
  printMultiple: row[4],
  dpi: row[5],
})).sort((left, right) => right.modelPrefix.length - left.modelPrefix.length);

/**
 * 按设备广播名猜机型。
 *
 * 广播名形如 `B3S-E623012991`，取前缀匹配；认不出来就落到空字符串那一行
 * （8px/mm、浓度 1~5），与 B3S 参数一致，不会打出黑块。
 */
function capabilityOf(name) {
  const modelName = String(name || '').trim().toUpperCase();
  return CAPABILITIES.find((capability) => modelName.indexOf(capability.modelPrefix.toUpperCase()) === 0);
}

function densityOptions(capability) {
  const min = capability ? capability.minDensity : 1;
  const max = capability ? capability.maxDensity : 5;
  const options = [];
  for (let density = min; density <= max; density++) {
    options.push(density);
  }
  return options;
}

module.exports = { capabilityOf, densityOptions };
