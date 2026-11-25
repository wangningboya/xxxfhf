/**
 * 打印机连接管理：扫描、连接、断开、断线重连、状态与本地设置。
 *
 * 状态口径：
 * - **连接只存在这台手机上**。蓝牙是手机连手机，设备信息存本机 `storage`，
 *   不同步服务端 —— 老板手机连过，店员手机也不能因此认为「已连接」。
 *   （所以这里没有 `printDevice` 之类的服务端字段，是刻意的。）
 * - `isConnected()` 每次都问 SDK，不用本地标志位。切后台、断电、走远都会静默断开，
 *   本地标志很容易停在「已连接」上，点打印才发现打不出来。
 *
 * 设备记录拆成两份（v0.9）：
 * - `print_device`：扫描/连接用的「当前设备」。`scan()` 开头若未连接就清掉它 ——
 *   等价于此前「搜不到设备就手动清一次 storage」的流程；
 * - `print_last_device`：断线重连与 `ensureConnected` 专用。扫描全程不读不写，
 *   所以清 `print_device` 不会连带把重连能力清掉。
 */

const { JCAPI } = require('./jcapi');
const { capabilityOf } = require('./capability');
const linkState = require('./link-state');

const DEVICE_KEY = 'print_device';
const LAST_DEVICE_KEY = 'print_last_device';
const SETTINGS_KEY = 'print_settings';

/** 扫描超过这个时间就放弃：BLE 扫描本身会一直转，不主动收口用户会以为卡死 */
const SCAN_TIMEOUT_MS = 8000;

/** 断线重连退避：2s / 6s / 15s，累计约 23 秒；三次都失败就停下，别一直转蓝牙 */
const RECONNECT_DELAYS = [2000, 6000, 15000];

/** `ensureConnected()` 的默认上限：BLE 连接通常 1~3 秒 */
const CONNECT_TIMEOUT_MS = 3000;

/** 断开监听的分发表：SDK 只有一个 `setOnPrinterDisconnect` 槽位，多页面订阅必须自己转发 */
const disconnectHandlers = [];
/** 连接状态订阅：设置页、工作台卡片都靠它刷新，同样是 fan-out */
const stateHandlers = [];
let disconnectWatched = false;

/** 去重：重连、点打印、手动连接可能在同一时刻发起，只允许一个 `connectWithDeviceId` 在跑 */
let connecting = null;
let reconnectTimer = null;
let reconnectAttempt = 0;
/** 手动断开后抑制自动重连，下次显式 connect() 复位 */
let suppressReconnect = false;
/** 打印作业进行中不抢连接：cancelPrint 与 connectWithDeviceId 同时操作 SDK 会互相打架 */
let jobActive = false;
let pendingReconnect = false;

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

function loadLastDevice() {
  const saved = wx.getStorageSync(LAST_DEVICE_KEY);
  return normalizeDevice(saved);
}

function saveLastDevice(device) {
  const normalized = normalizeDevice(device);
  if (normalized) {
    wx.setStorageSync(LAST_DEVICE_KEY, normalized);
  }
}

function clearLastDevice() {
  wx.removeStorageSync(LAST_DEVICE_KEY);
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

function emitState() {
  const state = linkState.read();
  stateHandlers.slice().forEach((handler) => {
    try {
      handler(state);
    } catch (e) {
      console.warn('[打印] 状态回调异常', e);
    }
  });
  return state;
}

function setState(patch) {
  // 名字优先用「当前设备」，没有就退回上次的设备：扫描会清掉当前设备记录，
  // 但页面仍应显示「记住了哪台」，否则用户在设置页看到一片空白。
  const device = loadDevice() || loadLastDevice();
  linkState.write(Object.assign({ deviceName: deviceName(device) || '' }, patch || {}));
  return emitState();
}

/** 订阅连接状态变化，返回取消订阅的函数；订阅时会先收到一次当前状态 */
function onStateChange(handler) {
  if (typeof handler !== 'function') {
    return function noop() {};
  }
  stateHandlers.push(handler);
  try {
    handler(linkState.read());
  } catch (e) {
    console.warn('[打印] 状态回调异常', e);
  }
  return function off() {
    const index = stateHandlers.indexOf(handler);
    if (index >= 0) {
      stateHandlers.splice(index, 1);
    }
  };
}

/**
 * 扫描附近设备。
 *
 * 不用 SDK 自己的超时：它只会在拿到结果时 resolve，没有设备时会一直挂着。
 * 这里用 Promise.race 收口，超时就当「没找到」，让页面能给一句明确的话。
 *
 * 搜索前清掉「记住但没连上」的记录：这正是此前每次搜不到设备时手动清理的那一步。
 * 已连接时不能清 —— 清掉会丢机型，浓度范围会退化成兜底档。
 */
function scan() {
  if (!isConnected()) {
    clearDevice();
  }
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
 * 连接指定设备。成功后写入本机两份 storage。
 *
 * 失败不自动弹窗重试：反复弹窗比一次失败更让人困惑，页面会给出「重试」按钮。
 * 这里同时承担「断线重连」的底层连接，所以不复位 suppressReconnect（手动断开时由
 * 上面的显式 connect 复位）。
 */
function connect(device) {
  const normalized = normalizeDevice(device);
  if (!normalized) {
    return Promise.reject({ message: '设备信息不完整，请重新搜索' });
  }
  if (connecting) {
    return connecting;
  }
  suppressReconnect = false;
  stopReconnect();
  connecting = Promise.resolve()
    .then(() => JCAPI.connectWithDeviceId(normalized.deviceId))
    .then((ok) => {
      if (!ok) {
        throw { message: '连接失败，请重试' };
      }
      saveDevice(normalized);
      saveLastDevice(normalized);
      watchDisconnect();
      reconnectAttempt = 0;
      setState({ connected: true, reconnecting: 0, lost: false, deviceName: deviceName(normalized) });
      return normalized;
    })
    .catch((err) => {
      setState({ connected: false });
      throw err;
    });
  const clear = () => {
    connecting = null;
  };
  connecting.then(clear, clear);
  return connecting;
}

/** 手动断开：同时清两份设备记录并抑制自动重连，否则断开后 2 秒又自己连上 */
function disconnect() {
  suppressReconnect = true;
  stopReconnect();
  return Promise.resolve()
    .then(() => JCAPI.closePrinter())
    .then(() => {
      clearDevice();
      clearLastDevice();
    })
    .catch(() => {
      // 已经断了或打印机没响应，本地状态必须跟着清掉，否则页面会一直显示「已连接」
      clearDevice();
      clearLastDevice();
    })
    .then(() => setState({ connected: false, reconnecting: 0, lost: false, deviceName: '' }));
}

/**
 * 注册断开分发。
 *
 * 只在第一次连接成功后向 SDK 注册一次：SDK 侧后注册会覆盖前面的回调，
 * 若每个页面各注册一次，先打开的页面就再也收不到通知。
 *
 * 被动断开只更新状态、不清设备记录：重连要用 `print_last_device`，
 * 扫描要用「未连接时清 print_device」来复刻手动清理。
 */
function watchDisconnect() {
  if (disconnectWatched) {
    return;
  }
  disconnectWatched = true;
  JCAPI.setOnPrinterDisconnect(() => {
    const device = loadDevice() || loadLastDevice();
    setState({ connected: false, reconnecting: 0, lost: false, deviceName: deviceName(device) });
    // 复制一份再遍历：回调里可能有人取消订阅
    disconnectHandlers.slice().forEach((handler) => {
      try {
        handler();
      } catch (e) {
        console.warn('[打印] 断开回调异常', e);
      }
    });
    scheduleReconnect();
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

function stopReconnect() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  reconnectAttempt = 0;
  pendingReconnect = false;
}

/**
 * 断线后按 2s / 6s / 15s 退避重连。
 *
 * 三条约束：
 * - 手动断开过（suppressReconnect）就不再自动试；
 * - 打印作业进行中（jobActive）先挂起，等 finish 收口后再试，避免和 cancelPrint 抢 SDK；
 * - 三次都失败置 `lost: true` 并保留设备名，点打印时还有 `ensureConnected` 兜底。
 */
function scheduleReconnect() {
  if (suppressReconnect) {
    return;
  }
  reconnectAttempt = 0;
  pendingReconnect = false;
  attemptReconnect();
}

function attemptReconnect() {
  if (suppressReconnect || reconnectTimer || isConnected()) {
    return;
  }
  const device = loadLastDevice() || loadDevice();
  if (!device) {
    setState({ connected: false, reconnecting: 0 });
    return;
  }
  if (jobActive) {
    pendingReconnect = true;
    return;
  }
  if (reconnectAttempt >= RECONNECT_DELAYS.length) {
    setState({ connected: false, reconnecting: 0, lost: true });
    return;
  }
  const attempt = reconnectAttempt + 1;
  setState({ connected: false, reconnecting: attempt, lost: false, deviceName: deviceName(device) });
  const delay = RECONNECT_DELAYS[reconnectAttempt];
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    if (suppressReconnect || isConnected()) {
      return;
    }
    Promise.resolve()
      .then(() => JCAPI.connectWithDeviceId(device.deviceId))
      .then((ok) => {
        if (!ok) {
          throw new Error('reconnect failed');
        }
        reconnectAttempt = 0;
        setState({ connected: true, reconnecting: 0, lost: false, deviceName: deviceName(device) });
      })
      .catch(() => {
        reconnectAttempt = attempt;
        attemptReconnect();
      });
  }, delay);
}

/**
 * 点打印前的「先试一次」（需求 14.4）。
 *
 * 与 `isConnected()` 的分工：这里允许产生副作用（真的连一次），`isConnected()`
 * 保持纯查询。超时返回 false 时连接可能仍在后台继续，成功后由状态回调刷新页面。
 */
function ensureConnected(timeoutMs) {
  if (isConnected()) {
    return Promise.resolve(true);
  }
  const device = loadLastDevice() || loadDevice();
  if (!device || suppressReconnect) {
    return Promise.resolve(false);
  }
  const wait = Number(timeoutMs) > 0 ? Number(timeoutMs) : CONNECT_TIMEOUT_MS;
  return Promise.race([
    connect(device)
      .then(() => true)
      .catch(() => false),
    new Promise((resolve) => {
      setTimeout(() => resolve(false), wait);
    }),
  ]);
}

/** 打印作业开始/结束。结束时若有挂起的重连，在这里补上 */
function setJobActive(active) {
  jobActive = !!active;
  if (!jobActive && pendingReconnect) {
    pendingReconnect = false;
    attemptReconnect();
  }
}

/** 打印结束后记一笔，工作台卡片显示「上次打印 N 分钟前」 */
function notePrintResult(summary) {
  const failed = !!(summary && summary.failCount > 0);
  setState({ lastPrintAt: Date.now(), lastResult: failed ? 'fail' : 'success' });
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
  loadLastDevice,
  saveLastDevice,
  clearLastDevice,
  loadSettings,
  saveSettings,
  isConnected,
  scan,
  connect,
  disconnect,
  ensureConnected,
  setJobActive,
  notePrintResult,
  onDisconnect,
  onStateChange,
  scanErrorText,
  RECONNECT_DELAYS,
};
