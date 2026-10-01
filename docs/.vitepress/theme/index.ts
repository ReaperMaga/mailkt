import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import ArchitectureDiagram from './components/ArchitectureDiagram.vue'
import AuthFlowDiagram from './components/AuthFlowDiagram.vue'
import StateDiagram from './components/StateDiagram.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    app.component('ArchitectureDiagram', ArchitectureDiagram)
    app.component('AuthFlowDiagram', AuthFlowDiagram)
    app.component('StateDiagram', StateDiagram)
  },
} satisfies Theme
