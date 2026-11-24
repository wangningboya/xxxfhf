const SDKErrorCode = {
	"JCSDK_PRINT_ERR_TIMEOUT": {
		"errCode": 0xff00,
		"msg": "打印超时"
	},
	"JCSDK_PRINT_ERR_DISCONNECT": {
		"errCode": 0xff01,
		"msg": "打印机断开"
	},
	"JCSDK_PRINT_ERR_DATA": {
		"errCode": 0xff02,
		"msg": "数据错误"
	},
	"JCSDK_PRINT_ERR_BUSY": {
		"errCode": 0xff03,
		"msg": "打印机忙碌"
	},
	"JCSDK_PRINT_ERR_GetDATA": {
		"errCode": 0xff04,
		"msg": "画布获取数据错误"
	},
	"JCSDK_PRINT_ERR_SetPaperFailed": {
		"errCode": 0xff05,
		"msg": "设置纸张失败错误"
	},
	"JCSDK_PRINT_ERR_PrinterNotWork": {
		"errCode": 0xff06,
		"msg": "查询打印纸张状态异常"
	},
	"JCSDK_PRINT_ERR_0x01": {
		"errCode": 0xff07,
		"msg": "盒盖打开",
    "errToPause":true
	},
	"JCSDK_PRINT_ERR_0x02": {
		"errCode": 0xff08,
		"msg": "缺纸",
    "errToPause":true
	},
	"JCSDK_PRINT_ERR_0x03": {
		"errCode": 0xff09,
		"msg": "电量不足"
	},
	"JCSDK_PRINT_ERR_0x04": {
		"errCode": 0xff0a,
		"msg": "电池异常"
	},
	"JCSDK_PRINT_ERR_0x05": {
		"errCode": 0xff0b,
		"msg": "手动停止"
	},
	"JCSDK_PRINT_ERR_0x06": {
		"errCode": 0xff0c,
		"msg": "数据错误"
	},
	"JCSDK_PRINT_ERR_0x07": {
		"errCode": 0xff0d,
		"msg": "温度过高"
	},
	"JCSDK_PRINT_ERR_0x08": {
		"errCode": 0xff0e,
		"msg": "出纸异常"
	},
	"JCSDK_PRINT_ERR_0x09": {
		"errCode": 0xff0f,
		"msg": "纸张错误"
	},
	"JCSDK_PRINT_ERR_0x0a": {
		"errCode": 0xff10,
		"msg": "rfid写入失败"
	},
	"JCSDK_PRINT_ERR_0x0b": {
		"errCode": 0xff11,
		"msg": "出纸异常"
	},
	"JCSDK_PRINT_ERR_0x0c": {
		"errCode": 0xff12,
		"msg": "发送epc指令但未检测到RFID标签"
	},
	"JCSDK_PRINT_ERR_0x0d": {
		"errCode": 0xff13,
		"msg": "检测到RFID标签但未发送EPC指令"
	},
	"JCSDK_PRINT_ERR_0x0e": {
		"errCode": 0xff14,
		"msg": "缺碳带"
	},
	"JCSDK_PRINT_ERR_0x0f": {
		"errCode": 0xff15,
		"msg": "打印头未锁紧"
	},
	"JCSDK_PRINT_ERR_0x11": {
		"errCode": 0xff16,
		"msg": "碳带已用完",
    "errToPause":true
	},
	"JCSDK_PRINT_ERR_0x12": {
		"errCode": 0xff17,
		"msg": "碳带不匹配"
	},
	"JCSDK_PRINT_ERR_0x13": {
		"errCode": 0xff18,
		"msg": "标签纸安装异常"
	},
	"JCSDK_PRINT_ERR_0x14": {
		"errCode": 0xff19,
		"msg": "非法标签"
	},
	"JCSDK_PRINT_ERR_0x15": {
		"errCode": 0xff20,
		"msg": "非法碳带和标签"
	},
	"JCSDK_PRINT_ERR_0x16": {
		"errCode": 0xff21,
		"msg": "超时(打印机超时)"
	},
	"JCSDK_PRINT_ERR_0x17": {
		"errCode": 0xff22,
		"msg": "非专业碳带"
	},
  "JCSDK_PRINT_ERR_0x18": {
		"errCode": 0xff23,
		"msg": "拔出电源"
	},
  "JCSDK_PRINT_ERR_0x19": {
		"errCode": 0xff24,
		"msg": "堵管异常"
	},
  "JCSDK_PRINT_ERR_0x1a": {
		"errCode": 0xff25,
		"msg": "适配器电压过低"
	},
  "JCSDK_PRINT_ERR_0x1b": {
		"errCode": 0xff26,
		"msg": "适配器电压过高"
	},
  "JCSDK_PRINT_ERR_0x1c": {
		"errCode": 0xff27,
		"msg": "非正品耗材"
	},
  "JCSDK_PRINT_ERR_0x1d": {
		"errCode": 0xff28,
		"msg": "非正品色带"
	},
  "JCSDK_PRINT_ERR_0x1e": {
		"errCode": 0xff29,
		"msg": "耗材超限"
	},
  "JCSDK_PRINT_ERR_0x1f": {
		"errCode": 0xff2a,
		"msg": "色带超限"
	},
  "JCSDK_PRINT_ERR_0x20": {
		"errCode": 0xff2b,
		"msg": "非正品标签"
	},
  "JCSDK_PRINT_ERR_0x21": {
		"errCode": 0xff2c,
		"msg": "标签超限"
	},
  "JCSDK_PRINT_ERR_setPaper": {
		"errCode": 0xfa01,
		"msg": "纸张类型错误"
	},
  "JCSDK_PRINT_ERR_setDarkness": {
		"errCode": 0xfa02,
		"msg": "浓度值错误"
	},
	"JCSDK_PRINT_ERR_epc": {
		"errCode": 0xfe01,
		"msg": "epc格式不对"
	},
	"JCSDK_PRINT_ERR_Drawing_Timeout": {
		"errCode": 0xfe00,
		"msg": "绘制元素超时错误"
	},
	"JCSDK_PRINT_ERR_noSupport": {
		"errCode": 0xfffd,
		"msg": "打印机不支持"
	},
	"JCSDK_PRINT_ERR_fail": {
		"errCode": 0xfd01,
		"msg": "失败"
	},
	"JCSDK_PRINT_ERR_none": {
		"errCode": 0xfd00,
		"msg": "正常"
	},
}

const SDKPlatForm = {
	'wx': '微信',
	'dd': "钉钉",
	'fs': "飞书",
	'uni': "uniapp"
}


const SDKBleResult = {
	'Ok': 0,
	'Fail': -1,
	'UnResopnse': -2,
	'UnResponse': -2,
	'UnSupport': -3
}

let SDKPrintingProgressData = {
	'count': 1, //打印完成的页码上报
	'tid': null, //部分机器有，默认为 undefind
	'time': 0 //部分机器有，默认为 undefind
}

//1.间隙纸  2.黑标纸  3.连续纸  4.定孔纸  5.透明纸  6.标牌  10.黑标间隙
const SDKLabelType = {
	'gap': 1,
	'gapBlack': 2,
	'nogap': 3,
	'hole': 4,
	'transparent': 5,
	'board': 6,
	'gapBlackGap': 10
}

class SDKOpenPrinterPara {
	constructor() {
		this.printerName = ''//要连接的打印机名称
		this.onDisConnect = ()=>{}//回调恢复结果
	}
}

class SDKStartJobPara {
	constructor() {
		this.paper = SDKLabelType.gap
		this.darkness = 3
		this.totalCount = 1
		this.printProgressCallback = (res) => {} //res 详见 SDKPrintingProgressData
		this.submitDataCallback = (error) => {} //回调中提交打印数据
		this.printErrorCallback = () => {} //详见  SDKErrorCode
		this.onPause = ()=>{}//回调暂停结果
		this.onResume = ()=>{}//回调恢复结果
	}
}


/* 
data: 图片数据的数组
width：图片数据的宽度  int
height：图片数据的高度  int
count：打印份数
epc:部分机器需要，默认不需要  string
hasDash：部分机器需要，默认不需要 bool
 */
class SDKPrintingPagePara {
	constructor() {
		this.data = {}
		this.width = 50
		this.height = 30
		this.rotation = 0
		this.count = 1
		this.epc = null
		this.hasDash = false
	}
}

/* 
 canvasId: canvas-Id
 compent:canvas 所在组件对象
 width：标签宽
 height：标签高
 rotation：标签的出纸方向
 refresh：是否刷新canvas的宽高  ，如果初始化给的canvas宽高大于标签转换像素后的大小，这里不刷新，可以增强性能(推荐)，否则超出部分是黑块
 uni-app 动态更新无效
 */

class SDKDrawLabelTemplate {
	constructor() {
		this.canvasId = 'canvasId in page'
		this.compent = {}
		this.width = 50.
		this.height = 30.
		this.rotation = 0
		this.refresh = true
	}
}

class SDKDrawTemplateBase {
	constructor(x = 0., y = 0., w = 50., h = 30., o = 0) {
		this.x = x
		this.y = y
		this.width = w
		this.height = h
		this.rotation = o
	}
}

const SDKDrawTextAlign = {
	'AlignLeft': 'left',
	'AlignCenter': 'center',
	'AlignRight': 'right'
}

/**
 * 文本绘制行模式枚举。
 * singleLine        (1) 单行模式：不受宽度限制，内容在一行内持续输出。
 * wrapWithEllipsis  (3) 多行模式超出省略：根据宽高和字号计算可容纳行数，
 *                      若最后一行仍无法放下则末尾追加省略号「...」。
 * wrapAndTruncate   (4) 多行模式超出截断：多行模式超出省略，超出部分直接截断，不追加省略号。
 */
const SDKDrawTextLineModel = {
	'singleLine': 1,
	'wrapWithEllipsis': 3,
	'wrapAndTruncate': 4
}

class SDKDrawTextTemplate extends SDKDrawTemplateBase {
	constructor(x, y, w, h, o) {
		super(x, y, w, h, o)
		this.fontSize = 2.2
		this.content = '武汉精臣智慧标识科技有限公司'
		this.bold = false
		this.italic = false
		this.align = SDKDrawTextAlign.AlignLeft
		this.lineModel = SDKDrawTextLineModel.singleLine
		this.family = null //手机内置的字体名称
	}
}

class SDKDrawImageTemplate extends SDKDrawTemplateBase {
	constructor(x, y, w, h, o) {
		super(x, y, w, h, o)
		this.path = 'path internet/base64 without header/local path'
	}
}


const SDKDrawBarcodeTextPostion = {
	'top': 1, // 1-在条码上方显示文本 
	'bottom': 0, //0-在条码下发显示问题 
	'none': 2 //2-不显示文本
}
class SDKDrawBarcodeTemplate extends SDKDrawTemplateBase {
	constructor(x, y, w, h, o) {
		super(x, y, w, h, o)
		this.content = '12345678'
		this.textPostion = SDKDrawBarcodeTextPostion.none
		this.fontSize = 2
		this.fontHeight = 2.5
	}
}

class SDKDrawQrcodeTemplate extends SDKDrawTemplateBase {
	constructor(x, y, w, h, o) {
		super(x, y, w, h, o)
		this.content = 'https://www.niimbot.com'
		this.ecc = 2
	}
}

class SDKDrawLineTemplate extends SDKDrawTemplateBase {
	constructor(x, y, w, h, o) {
		super(x, y, w, h, o)
	}
}

class SDKDrawRectagleTemplate extends SDKDrawTemplateBase {
	constructor(x, y, w, h, o) {
		super(x, y, w, h, o)
		this.filled = false
		this.lineWidth = 1.
	}
}


export default {
	SDKErrorCode,
	SDKBleResult,
	SDKStartJobPara,
	SDKPlatForm,
	SDKDrawLabelTemplate,
	SDKDrawLineTemplate,
	SDKDrawRectagleTemplate,
	SDKDrawRectangleTemplate: SDKDrawRectagleTemplate,
	SDKDrawQrcodeTemplate,
	SDKDrawBarcodeTemplate,
	SDKDrawImageTemplate,
	SDKDrawTextTemplate,
	SDKLabelType,
	SDKDrawTextAlign,
	SDKDrawTextLineModel,
	SDKDrawBarcodeTextPostion
}
