const app = getApp();
const { request } = require('../../utils/request');
const { dateStr, addDays } = require('../../utils/format');

Page({
  data: {
    serveDate: '',
    tab: 'dish',
    view: {},
    dishes: [],
    regions: [],
    loading: true,
  },

  onLoad() {
    this.setData({ serveDate: addDays(dateStr(), 1) });
  },

  onShow() {
    this.load();
  },

  onPullDownRefresh() {
    this.load(() => wx.stopPullDownRefresh());
  },

  load(done) {
    this.setData({ loading: true });
    request({ url: '/admin/prep?serveDate=' + this.data.serveDate })
      .then((view) => {
        const dishes = (view.dishes || []).map((row) => ({
          key: row.dishId + '|' + (row.groupName || '') + '|' + row.kind,
          name: row.name,
          kind: row.kind,
          // 规格项（如白米饭 / 杂粮饭）要分列，做的时候是两锅
          groupName: row.groupName || '',
          qty: row.qty,
          comboNames: (row.comboNames || []).join('、'),
        }));
        const regions = (view.regions || []).slice();
        if (view.selfPickup) {
          regions.push(Object.assign({}, view.selfPickup, { selfPickup: true }));
        }
        this.setData({ view, dishes, regions, loading: false });
      })
      .catch((err) => {
        this.setData({ loading: false });
        app.toastError(err);
      })
      .then(() => {
        if (done) {
          done();
        }
      });
  },

  onDateChange(e) {
    this.setData({ serveDate: e.detail.value });
    this.load();
  },

  onSwitchTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab });
  },
});
