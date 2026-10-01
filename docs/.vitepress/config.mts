import { defineConfig } from 'vitepress'

const repo = 'https://github.com/ReaperMaga/mailkt'

export default defineConfig({
  title: 'MailKT',
  description: 'Coroutine-first Kotlin/JVM library for reading, watching and sending email, with hosted OAuth2 for Gmail and Outlook.',
  lang: 'en-US',
  base: '/mailkt/',
  cleanUrls: true,
  lastUpdated: true,

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/mailkt/logo.svg' }],
    ['meta', { name: 'theme-color', content: '#d97757' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: 'MailKT' }],
    ['meta', { property: 'og:description', content: 'Coroutine-first email for Kotlin: IMAP, SMTP and hosted OAuth2 for Gmail and Outlook.' }],
  ],

  markdown: {
    theme: { light: 'github-light', dark: 'github-dark' },
  },

  themeConfig: {
    logo: '/logo.svg',

    nav: [
      { text: 'Guide', link: '/guide/introduction', activeMatch: '/guide/' },
      { text: 'Providers', link: '/providers/authorization', activeMatch: '/providers/' },
      { text: 'Reference', link: '/reference/models', activeMatch: '/reference/' },
      {
        text: '0.1.0',
        items: [
          { text: 'Releases', link: `${repo}/releases` },
          { text: 'License (MIT)', link: `${repo}/blob/main/LICENSE` },
        ],
      },
    ],

    sidebar: {
      '/': [
        {
          text: 'Getting started',
          items: [
            { text: 'Introduction', link: '/guide/introduction' },
            { text: 'Installation', link: '/guide/installation' },
            { text: 'Quick start', link: '/guide/quick-start' },
            { text: 'Persistence boundary', link: '/guide/persistence' },
          ],
        },
        {
          text: 'Providers',
          items: [
            { text: 'Hosted authorization', link: '/providers/authorization' },
            { text: 'Gmail', link: '/providers/gmail' },
            { text: 'Outlook / Microsoft 365', link: '/providers/outlook' },
          ],
        },
        {
          text: 'Working with mail',
          items: [
            { text: 'Mailbox lifecycle', link: '/guide/lifecycle' },
            { text: 'Folders', link: '/guide/folders' },
            { text: 'Reading mail', link: '/guide/reading' },
            { text: 'Watching', link: '/guide/watching' },
            { text: 'Conversations', link: '/guide/conversations' },
            { text: 'Composing and sending', link: '/guide/sending' },
          ],
        },
        {
          text: 'Operations',
          items: [
            { text: 'Error handling', link: '/guide/errors' },
            { text: 'Logging', link: '/guide/logging' },
            { text: 'Guarantees', link: '/guide/guarantees' },
          ],
        },
        {
          text: 'Reference',
          items: [
            { text: 'Models', link: '/reference/models' },
            { text: 'Capabilities', link: '/reference/capabilities' },
            { text: 'Configuration', link: '/reference/configuration' },
            { text: 'Exceptions', link: '/reference/exceptions' },
          ],
        },
      ],
    },

    socialLinks: [{ icon: 'github', link: repo }],

    editLink: {
      pattern: `${repo}/edit/main/docs/:path`,
      text: 'Edit this page on GitHub',
    },

    search: { provider: 'local' },

    outline: { level: [2, 3] },

    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © ReaperMaga',
    },
  },
})
