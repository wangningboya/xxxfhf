/**
 * JCAPI 接入包入口（入口版本标记：4.0.0；实际运行版本以 getVersion() 为准）。
 *
 * 微信小程序的典型调用顺序：
 * 1. setPlatform("微信")
 * 2. scanPrinters()，再使用返回设备的 deviceId 调用 connectWithDeviceId(deviceId)
 * 3. startJob()，在 submitDataCallback 中按页执行绘制和 print()
 *
 * 本对象为单例入口，所有方法共享同一个连接、画布和打印任务状态。
 */

import JCSDKManager from "./JCAPIManager"
import SDKElum from "./JCAPICommonData"

let manager = new JCSDKManager();


const JCAPI = {

  /**
   * 获取 SDK 版本字符串。
   * @returns {string} 当前SDK报告的版本号。
   */
  getVersion() {
    return manager.getVersion();
  },
  /**
   * 设置底层平台适配器。必须在扫描、连接、绘制或打印前调用。
   * @param {string} [platform='uniapp'] 平台标识：微信、钉钉、飞书或 uniapp；
   * 原生微信小程序传入“微信”。
   * @returns {void} 仅更新平台和运行环境信息，不返回 Promise。
   */
  setPlatform(platform = SDKElum.SDKPlatForm.uni) {
    manager.setPlatform(platform);
  },
  /**
   * 是否由接入方接管蓝牙特征值监听。需要在 openPrinter 前设置。
   * 设为 true 后，接入方必须注册 wx.onBLECharacteristicValueChange，并将每次
   * 收到的事件转发给 bleValueChanged；页面销毁时应自行解除微信监听。
   * @param {boolean} use true 表示使用外部监听，false 表示由 SDK 内部监听；默认 true。
   * @returns {void}
   */
  setUseThirdBleListen(use = true) {
    manager.setUseBleListen(use);
  },
  /**
   * 将外部蓝牙监听事件转交给 SDK。仅当 setUseThirdBleListen(true) 时有效。
   * @example
   * wx.onBLECharacteristicValueChange((res) => JCAPI.bleValueChanged(res));
   * @param {Object} res wx.onBLECharacteristicValueChange 的原始事件对象。
   * @returns {void}
   */
  bleValueChanged(res) {
    manager.onBleValueChange(res);
  },
  /**
   * 获取当前打印机连接状态。
   * @returns {boolean} 已连接且连接资源仍有效时为 true，否则为 false。
   */
  getConnectState() {
    return manager.getConnectState()
  },

  /**
   * 注册打印机被动断开回调。重复调用会替换之前注册的回调；主动调用
   * closePrinter() 不应依赖此回调作为成功通知。
   * @param {Function} callback 检测到蓝牙连接被动断开时调用，当前实现不保证传入参数。
   * @returns {void}
   */
  setOnPrinterDisconnect(callback) {
    manager.setOnPrinterDisconnect(callback)
  },

  /**
   * 按广播名称连接打印机（兼容旧调用；推荐使用 connectWithDeviceId）。
   * @param {string} printerName 扫描结果中的设备名称，必须与目标设备名称匹配。
   * @returns {Promise<boolean>} 连接成功为 true，连接失败为 false。
   */
  openPrinter(printerName) {
    return manager.connectPrinter(printerName)
  },
  /**
   * 按蓝牙设备 ID 连接打印机。
   * @param {string} deviceId scanPrinters() 返回的设备 deviceId。
   * @returns {Promise<boolean>} 连接成功为 true，连接失败为 false。
   */
  connectWithDeviceId(deviceId) {
    return manager.connectPrinterDeviceId(deviceId)
  },
  /**
   * 断开当前打印机并释放连接资源。
   * @returns {Promise<boolean>} 底层蓝牙连接关闭结果。
   */
  closePrinter() {
    return manager.closePrinter()
  },
  /**
   * 搜索附近的蓝牙设备。历史拼写保留用于兼容，新项目推荐调用 scanPrinters()。
   * @param {number} [time=5000] 搜索时长，单位为毫秒。
   * @returns {Promise<Array<Object>>} 设备数组；设备项通常包含 name、deviceId 等蓝牙字段。
   */
  scanedPrinters(time) {
    return manager.scanedPrinters(time)
  },
  /**
   * 搜索附近蓝牙设备的推荐拼写；scanedPrinters() 继续保留用于兼容旧项目。
   * @param {number} [time=5000] 搜索时长，单位为毫秒。
   * @returns {Promise<Array<Object>>} 设备数组。
   */
  scanPrinters(time) {
    return manager.scanedPrinters(time)
  },
  /**
   * 开始一次标签画布绘制。必须先调用本方法，再调用一个或多个 draw* 方法，
   * 最后调用 endDrawLabel 获取图片数据。
   * @param {Object} para 标签画布参数。
   * @param {string} para.canvasId Canvas 组件 ID。
   * @param {Object} para.component Canvas 所在的页面或组件实例。
   * @param {Object} [para.compent] component 的历史拼写，继续兼容旧项目。
   * @param {number} para.width 标签宽度，单位为毫米。
   * @param {number} para.height 标签高度，单位为毫米。
   * @param {number} para.rotation 标签出纸方向角度；模板初始值为 0。
   * @param {boolean} para.refresh 是否按标签尺寸刷新 Canvas 宽高；模板初始值为 true。
   * @returns {Promise<boolean>} 画布初始化完成后解析为 true。
   */
  startDrawLabel(para) {
    const compatiblePara = para && para.component
      ? Object.assign({}, para, {compent: para.component})
      : para;
    return manager.startDrawLabel(compatiblePara);
  },
  /**
   * 在当前标签画布上绘制文本。
   * @param {Object} para 文本绘制参数。
   * @param {number} para.x 左上角横坐标，单位为毫米。
   * @param {number} para.y 左上角纵坐标，单位为毫米。
   * @param {number} para.width 文本区域宽度，单位为毫米。
   * @param {number} para.height 文本区域高度，单位为毫米。
   * @param {number} para.rotation 旋转角度；模板初始值为 0。
   * @param {number} para.fontSize 字号，单位为毫米；模板初始值为 2.2。
   * @param {string} para.content 要绘制的文本。
   * @param {boolean} para.bold 是否使用粗体。
   * @param {boolean} para.italic 是否使用斜体。
   * @param {string} para.align 水平对齐方式：left、center 或 right。
   * @param {number} para.lineModel 行模式：1=单行，3=多行超出省略，4=多行超出截断。
   * @param {string|null} para.family 手机内置字体名称；null 表示使用默认字体。
   * @returns {Promise<boolean>} 绘制操作完成后解析为 true。
   */
  drawText(para) {
    return manager.drawText(para);
  },

  /**
   * 在当前标签画布上绘制线段。
   * @param {Object} para 线段绘制参数。
   * @param {number} para.x 起点横坐标，单位为毫米。
   * @param {number} para.y 起点纵坐标，单位为毫米。
   * @param {number} para.width 线段宽度，单位为毫米。
   * @param {number} para.height 线段高度，单位为毫米。
   * @param {number} para.rotation 旋转角度；模板初始值为 0。
   * @returns {Promise<boolean>} 绘制操作完成后解析为 true。
   */
  drawLine(para) {
    return manager.drawLine(para);
  },

  /**
   * 在当前标签画布上绘制矩形。
   * @param {Object} para 矩形绘制参数。
   * @param {number} para.x 左上角横坐标，单位为毫米。
   * @param {number} para.y 左上角纵坐标，单位为毫米。
   * @param {number} para.width 矩形宽度，单位为毫米。
   * @param {number} para.height 矩形高度，单位为毫米。
   * @param {number} para.rotation 旋转角度；模板初始值为 0。
   * @param {boolean} para.filled 是否填充矩形；模板初始值为 false。
   * @param {number} para.lineWidth 未填充时的边框宽度，单位为毫米；模板初始值为 1。
   * @returns {Promise<boolean>} 绘制操作完成后解析为 true。
   */
  drawRectangle(para) {
    return manager.drawRectangle(para);
  },

  /**
   * 在当前标签画布上绘制二维码。
   * @param {Object} para 二维码绘制参数。
   * @param {number} para.x 左上角横坐标，单位为毫米。
   * @param {number} para.y 左上角纵坐标，单位为毫米。
   * @param {number} para.width 二维码区域宽度，单位为毫米。
   * @param {number} para.height 二维码区域高度，单位为毫米。
   * @param {number} para.rotation 旋转角度；模板初始值为 0。
   * @param {string} para.content 二维码内容。
   * @param {number} para.ecc 纠错等级：0=M、1=L、2=H、3=Q；模板初始值为 2。
   * @returns {Promise<boolean>} 绘制操作完成后解析为 true。
   */
  drawQRCode(para) {
    return manager.drawQRCode(para);
  },

  /**
   * 在当前标签画布上绘制条码。
   * @param {Object} para 条码绘制参数。
   * @param {number} para.x 左上角横坐标，单位为毫米。
   * @param {number} para.y 左上角纵坐标，单位为毫米。
   * @param {number} para.width 条码区域宽度，单位为毫米。
   * @param {number} para.height 条码区域高度，单位为毫米。
   * @param {number} para.rotation 旋转角度；模板初始值为 0。
   * @param {string} para.content 条码内容。
   * @param {number} para.textPosition 条码文字位置：0=下方，1=上方，2=不显示。
   * @param {number} [para.textPostion] textPosition 的历史拼写，继续兼容旧项目。
   * @param {number} para.fontSize 条码文字字号，单位为毫米；模板初始值为 2。
   * @param {number} para.fontHeight 条码文字区域高度，单位为毫米；模板初始值为 2.5。
   * @returns {Promise<boolean>} 绘制操作完成后解析为 true；参数不满足条码尺寸要求时可能为 false。
   */
  drawBarcode(para) {
    const compatiblePara = para && para.textPosition !== undefined
      ? Object.assign({}, para, {textPostion: para.textPosition})
      : para;
    return manager.drawBarcode(compatiblePara);
  },

  /**
   * 在当前标签画布上绘制图片。
   * @param {Object} para 图片绘制参数。
   * @param {number} para.x 左上角横坐标，单位为毫米。
   * @param {number} para.y 左上角纵坐标，单位为毫米。
   * @param {number} para.width 图片宽度，单位为毫米。
   * @param {number} para.height 图片高度，单位为毫米。
   * @param {number} para.rotation 旋转角度；模板初始值为 0。
   * @param {string} para.path 网络图片地址、无数据头的 Base64 数据或本地临时路径。
   * @returns {Promise<boolean>} 绘制操作完成后解析为 true；图片读取失败时由打印错误回调报告。
   */
  drawImage(para) {
    return manager.drawImage(para);
  },

  /**
   * 结束当前标签绘制并读取画布图片数据。label 应与后续打印任务使用的纸张类型一致。
   * @param {number} [label=1] 纸张类型：1=间隙纸、2=黑标纸、3=连续纸、4=定孔纸、
   * 5=透明纸、6=标牌、10=黑标间隙纸。
   * @returns {Promise<{data: Array|TypedArray, width: number, height: number}>} 包含 RGBA 图片数据
   * 以及像素宽高；结果可直接整理为 print() 或 printImageData() 的参数。
   */
  endDrawLabel(label = 1) {
    return manager.endDrawLabel(label)
  },

  /**
   * 初始化一次打印任务。该方法只建立任务状态，不提交图片数据；图片数据由
   * submitDataCallback 驱动，通过 print() 或 printImageData() 逐页提交。
   * @param {Object} para 打印任务参数。
   * @param {number} para.paper 纸张类型：1=间隙纸、2=黑标纸、3=连续纸、
   * 4=定孔纸、5=透明纸、6=标牌、10=黑标间隙纸。
   * @param {number} para.darkness 打印浓度；模板初始值为 3。
   * @param {number} para.totalCount 本次任务的总打印份数；模板初始值为 1。
   * @param {Function} para.printProgressCallback 打印进度回调；参数通常包含 count，
   * 部分机型还会提供 tid 和 time。
   * @param {Function} para.submitDataCallback SDK 请求下一页数据时调用，调用方应在回调中执行 print()
   * 或 printImageData()。
   * @param {Function} para.printErrorCallback 打印错误回调；参数包含 errCode、msg，部分可恢复错误
   * 还包含 errToPause=true。
   * @param {Function} para.onPause 打印暂停回调。
   * @param {Function} para.onResume 打印恢复回调。
   * @returns {void} 打印进度、错误、暂停和恢复结果通过参数中的回调上报。
   */
  startJob(para) {
    manager.startJob(para);
  },

  /**
   * 向当前打印任务提交一页四通道图片数据。
   * @param {Object} para 打印页参数。
   * @param {Array|TypedArray} para.data 四通道 RGBA 图片数据，长度应为 width * height * 4；
   * 推荐直接使用 endDrawLabel() 返回的 data。
   * @param {number} para.width 图片像素宽度。
   * @param {number} para.height 图片像素高度。
   * @param {number} para.count 当前页的打印份数；模板初始值为 1。
   * @param {number} para.rotation 打印方向角度；模板初始值为 0。
   * @param {string|null} para.epc 部分 RFID 机型使用的 EPC；普通打印传 null。
   * @param {boolean} para.hasDash 部分机型使用的虚线标志；模板初始值为 false。
   * @returns {void} 提交结果通过 startJob() 中的进度或错误回调上报。
   */
  print(para) {
    manager.startPrint(para);
  },

  /**
   * 向当前打印任务提交一页单通道图片数据。
   * @param {Object} para 打印页参数。
   * @param {Array|TypedArray} para.data 单通道图片数据，长度应为 width * height。
   * @param {number} para.width 图片像素宽度。
   * @param {number} para.height 图片像素高度。
   * @param {number} para.count 当前页的打印份数；模板初始值为 1。
   * @param {number} para.rotation 打印方向角度；模板初始值为 0。
   * @param {string|null} para.epc 部分 RFID 机型使用的 EPC；普通打印传 null。
   * @param {boolean} para.hasDash 部分机型使用的虚线标志；模板初始值为 false。
   * @returns {void} 提交结果通过 startJob() 中的进度或错误回调上报。
   */
  printImageData(para) {
    manager.startPrintImageData(para);
  },

  /**
   * 取消正在执行的打印任务。
   * @param {Function} [callback] 取消成功后回调；
   * @returns {void}
   */
  cancelPrint(callback) {
    manager.cancelPrint(callback);
  },

  /**
   * 暂停正在打印的流程。结果通过 startJob() 参数的 onPause 回调上报。
   * @returns {void}
   */
  pausePrint() {
    manager.pausePrint();
  },

  /**
   * 恢复已暂停的打印流程。结果通过 startJob() 参数的 onResume 回调上报。
   * @returns {void}
   */
  resumePrint() {
    manager.resumePrint();
  },

  /**
   * 获取当前打印机序列号。
   * @returns {Promise<{code: number, value?: string}>} code：0=成功、-1=失败、-2=无响应、
   * -3=不支持；code=0 时 value 为 SN。
   */
  getSN() {
    return manager.getSN();
  },

  /**
   * 获取当前打印机固件版本号。
   * @returns {Promise<{code: number, value?: string}>} code：0=成功、-1=失败、-2=无响应、
   * -3=不支持；code=0 时 value 为固件版本号。
   */
  getSoftVersion() {
    return manager.getSoftVersion();
  },

  /**
   * 获取当前打印机硬件版本号。
   * @returns {Promise<{code: number, value?: string}>} code：0=成功、-1=失败、-2=无响应、
   * -3=不支持；code=0 时 value 为硬件版本号。
   */
  getHardVersion() {
    return manager.getHardVersion();
  },

  /**
   * 获取当前打印机的每毫米像素换算值。
   * @returns {number} 当前实现对 300～349 DPI 返回 11.81，其余情况返回 8；该值不是 DPI。
   */
  getMultiple() {
    var dpi = manager.getMultiple()
    return (dpi >= 300 && dpi < 350) ? 11.81 : 8;
  },

  /**
   * 获取标签信息，目前仅支持M2
   * @param {function} callback - 回调函数，用于返回标签信息。标签信息包含以下内容：
   *   - 索引 0: 间隙高度，单位像素
   *   - 索引 1: 标签高度（含间隙高度），单位像素
   *   - 索引 2: 纸张类型 
   *     - 1: 间隙纸
   *     - 2: 黑标纸
   *     - 3: 连续纸
   *     - 4: 定孔纸
   *     - 5: 透明纸
   *     - 6: 标牌
   *     - 10: 黑标间隙纸
   *   - 索引 3: 间隙高度，单位毫米
   *   - 索引 4: 标签高度（含间隙高度），单位毫米
   *   - 索引 5: 标签宽度，单位像素
   *   - 索引 6: 标签宽度，单位毫米
   *   - 索引 7: 尾巴方向（线缆标签、珠宝标签等）
   *     - 1: 上
   *     - 2: 下
   *     - 3: 左
   *     - 4: 右
   *   - 索引 8: 尾巴长度，单位像素
   *   - 索引 9: 尾巴长度，单位毫米
   */
  getPageInfos() {
    return manager.getPageInfos();
  },

  /**
   * 获取当前已连接打印机设备信息
   * @returns {Object|null} 已连接设备对象，无设备时返回null
   * @property {string} deviceId - 设备MAC地址
   * @property {string} serviceId - 蓝牙服务UUID
   * @property {string} writeC - 写特征值UUID
   * @property {string} readC - 读特征值UUID
   * @property {string} name - 打印机名称
   * 示例返回：
   * {
   *   "deviceId": "C8:E6:01:29:23:91",
   *   "serviceId": "E7810A71-73AE-499D-8C15-FAA9AEF0C3F2",
   *   "writeC": "BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F",
   *   "readC": "BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F",
   *   "name": "B3S-E623012991"
   * }
   */
  getConnName() {
    return manager.getConnName();
  }

}

export default JCAPI;
