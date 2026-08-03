import { createDiscreteApi, dateZhCN, zhCN, type GlobalThemeOverrides } from 'naive-ui'

/**
 * Naive UI 主题覆盖：与既有 --vh-* 设计变量保持一致。
 * 主色沿用品牌橙（--vh-primary），圆角与字号沿用控制台现有规范。
 */
export const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#ff4d18',
    primaryColorHover: '#ff6a3d',
    primaryColorPressed: '#d83f12',
    primaryColorSuppl: '#ff6a3d',
    borderRadius: '8px',
    borderRadiusSmall: '6px',
    fontFamily: '"IBM Plex Sans", "PingFang SC", system-ui, sans-serif',
    textColor1: '#16130f',
    textColor2: '#5c564c',
    textColor3: '#8a8377',
    borderColor: 'rgba(22, 19, 15, .12)',
    dividerColor: 'rgba(22, 19, 15, .09)',
  },
  DataTable: {
    thColor: '#f5f5f7',
    thTextColor: '#5c564c',
    tdColorHover: '#fff8f5',
    borderColor: 'rgba(22, 19, 15, .12)',
    thFontWeight: '600',
  },
  Dialog: {
    borderRadius: '10px',
  },
  Card: {
    borderRadius: '8px',
  },
}

/**
 * 根组件自身无法注入 useDialog/useMessage（provider 在模板内），
 * 因此通过离散 API 在 setup 中直接获取确认对话框。
 */
export const { dialog } = createDiscreteApi(['dialog'], {
  configProviderProps: {
    locale: zhCN,
    dateLocale: dateZhCN,
    themeOverrides,
  },
})
