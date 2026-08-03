import type { QuasarPluginOptions } from 'quasar'
import { Dark, Notify, Screen } from 'quasar'
import quasarLang from 'quasar/lang/zh-CN'

/**
 * 全局 Quasar 配置（主色 / 圆角 / 间距 / 动效统一在此维护）。
 * - 主色沿用现有视觉：primary 对应 --coral，secondary 对应 --support
 * - 与 src/quasar-variables.sass（Sass 变量）保持一致
 * - 明暗主题由 Dark 插件驱动，颜色全部跟随主题变量，不硬编码色值
 */
export const quasarBrand = {
  primary: '#ef7048',
  secondary: '#496879',
  accent: '#3e627a',
  positive: '#2f9964',
  negative: '#b94d43',
  info: '#496879',
  warning: '#c98a2b',
} as const

export const quasarOptions: QuasarPluginOptions = {
  lang: quasarLang,
  plugins: { Dark, Notify, Screen },
  config: {
    brand: quasarBrand,
    dark: false,
  },
}

export default quasarOptions
