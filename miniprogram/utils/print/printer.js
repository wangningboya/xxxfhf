/**
 * 打印机连接管理：扫描、连接、断开、状态与本地设置。
 *
 * 状态口径：
 * - **连接只存在这台手机上**。蓝牙是手机连手机，设备信息存本机 `storage`，
 *   不同步服务端 —— 老板手机连过，店员手机也不能因此认为「已连接」。
 *   （所以这里没有 `printDevice` 之类的服务端字段，是刻意的。）
 * - `isConnected()` 每次都问 SDK，不用本地标志位。切后台、断电、走远都会静默断开，
 *   本地标志很容易停在「已连接」上，点打印才发现打不出来。
 */

const { JCAPI } = require('./jcapi');
const { capabilityOf } = require('./capability');

const DEVICE_KEY = 'print_device';
const SETTINGS_KEY = 'print_settings';

/** 扫描超过这个时间就放弃：BLE 扫描本身会一直转，不主动收口用户会以为卡死 */
const SCAN_TIMEOUT_MS = 8000;

/** 断开监听的分发表：SDK 只有一个 `setOnPrinterDisconnect` 槽位，多页面订阅必须自己转发 */
const disconnectHandlers = [];
let disconnectWatched = false;

function normalizeDevice(device) {
  if (!device || typeof device.deviceId !== 'string') {
    return null;
  }
  const deviceId = device.deviceId.trim();
  if (!deviceId) {
    return null;
  }
  return {
    deviceId,
    name: device.name || device.localName || '',
    localName: device.localName || device.name || '',
  };
}

/** 展示用名字：部分机型广播名为空，退化成 deviceId，不能显示空白卡片 */
function deviceName(device) {
  const normalized = normalizeDevice(device);
  if (!normalized) {
    return '';
  }
  return normalized.name || normalized.localName || normalized.deviceId;
}

function loadDevice() {
  const saved = wx.getStorageSync(DEVICE_KEY);
  return normalizeDevice(saved);
}

function saveDevice(device) {
  const normalized = normalizeDevice(device);
  if (normalized) {
    wx.setStorageSync(DEVICE_KEY, normalized);
  }
}

function clearDevice() {
  wx.removeStorageSync(DEVICE_KEY);
}

/**
 * 打印设置：出纸类型与浓度。
 *
 * 只存本机。两台手机共用一个账号时可能出现 A 设 3、B 设 5，但浓度调准一次就不用再动，
 * 为它加一个服务端同步字段不值得（已确认的取舍，见需求 7.1 第 4 条）。
 */
function loadSettings(device) {
  const capability = capabilityOf(deviceName(device));
  const saved = wx.getStorageSync(SETTINGS_KEY) || {};
  const darkness = Number(saved.darkness);
  const min = capability.minDensity;
  const max = capability.maxDensity;
  return {
    // 间隙纸是 B3S 的赠品规格，也是唯一采购的纸型；换纸才需要改这里
    paper: Number(saved.paper) || 1,
    darkness: darkness >= min && darkness <= max ? darkness : capability.defaultDensity,
  };
}

function saveSettings(settings) {
  wx.setStorageSync(SETTINGS_KEY, {
    paper: Number(settings.paper) || 1,
    darkness: Number(settings.darkness) || 3,
  });
}

/** 问 SDK 当前是否连着。异常一律当没连 —— 宁可让用户重新点一次连接 */
function isConnected() {
  try {
    return !!JCAPI.getConnectState();
  } catch (e) {
    return false;
  }
}

/**
 * 扫描附近设备。
 *
 * 不用 SDK 自己的超时：它只会在拿到结果时 resolve，没有设备时会一直挂着。
 * 这里用 Promise.race 收口，超时就当「没找到」，让页面能给一句明确的话。
 */
function scan() {
  const timeout = new Promise((resolve) => {
    setTimeout(() => resolve({ timedOut: true, devices: [] }), SCAN_TIMEOUT_MS);
  });
  const scanning = Promise.resolve()
    .then(() => JCAPI.scanPrinters())
    .then((list) => ({ timedOut: false, devices: dedupe(list) }))
    .catch((err) => {
      throw err;
    });
  return Promise.race([scanning, timeout]);
}

function dedupe(list) {
  if (!Array.isArray(list)) {
    return [];
  }
  const seen = {};
  const result = [];
  list.forEach((item) => {
    const normalized = normalizeDevice(item);
    if (!normalized || seen[normalized.deviceId]) {
      return;
    }
    seen[normalized.deviceId] = true;
    result.push(normalized);
  });
  return result;
}

/**
 * 连接指定设备。成功后写入本机 storage。
 *
 * 失败不自动重连：反复弹窗比一次失败更让人困惑，页面会给出「重试」按钮。
 */
function connect(device) {
  const normalized = normalizeDevice(device);
  if (!normalized) {
    return Promise.reject({ message: '设备信息不完整，请重新搜索' });
  }
  return Promise.resolve()
    .then(() => JCAPI.connectWithDeviceId(normalized.deviceId))
    .then((ok) => {
      if (!ok) {
        throw { message: '连接失败，请重试' };
      }
      saveDevice(normalized);
      watchDisconnect();
      return normalized;
    });
}

function disconnect() {
  return Promise.resolve()
    .then(() => JCAPI.closePrinter())
    .then(() => {
      clearDevice();
    })
    .catch(() => {
      // 已经断了或打印机没响应，本地状态必须跟着清掉，否则页面会一直显示「已连接」
      clearDevice();
    });
}

/**
 * 注册断开分发。
 *
 * 只在第一次连接成功后向 SDK 注册一次：SDK 侧后注册会覆盖前面的回调，
 * 若每个页面各注册一次，先打开的页面就再也收不到通知。
 */
function watchDisconnect() {
  if (disconnectWatched) {
    return;
  }
  disconnectWatched = true;
  JCAPI.setOnPrinterDisconnect(() => {
    clearDevice();
    // 复制一份再遍历：回调里可能有人取消订阅
    disconnectHandlers.slice().forEach((handler) => {
      try {
        handler();
      } catch (e) {
        console.warn('[打印] 断开回调异常', e);
      }
    });
  });
}

/** 订阅断开事件，返回取消订阅的函数（页面 onUnload 必须调，否则页面销毁后仍会被回调） */
function onDisconnect(handler) {
  if (typeof handler !== 'function') {
    return function noop() {};
  }
  disconnectHandlers.push(handler);
  return function off() {
    const index = disconnectHandlers.indexOf(handler);
    if (index >= 0) {
      disconnectHandlers.splice(index, 1);
    }
  };
}

/**
 * 蓝牙权限类失败的统一文案（需求 9.2）。
 *
 * 微信不会告诉我们是「蓝牙没开」还是「没给位置权限」，只能按错误文案粗分：
 * 区分不出来时给一句同时覆盖两种情况的提示，比猜错方向强。
 */
function scanErrorText(err) {
  const raw = (err && (err.message || err.errMsg)) || '';
  if (/auth|permission|denied/i.test(raw)) {
    return '需要蓝牙与位置权限才能搜索打印机，请在设置里允许';
  }
  if (/bluetooth|ble/i.test(raw)) {
    return '请先打开手机蓝牙再搜索';
  }
  return '没能搜索到设备，请检查蓝牙与权限后重试';
}

module.exports = {
  deviceName,
  loadDevice,
  saveDevice,
  clearDevice,
  loadSettings,
  saveSettings,
  isConnected,
  scan,
  connect,
  disconnect,
  onDisconnect,
  scanErrorText,
};
