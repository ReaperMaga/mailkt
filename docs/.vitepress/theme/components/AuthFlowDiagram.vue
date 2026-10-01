<script setup lang="ts">
const lanes = [
  { x: 150, title: 'Browser', sub: 'your frontend', kind: 'data' },
  { x: 345, title: 'Your backend', sub: 'HTTP routes', kind: 'data' },
  { x: 540, title: 'MailKT', sub: 'Gmail / Outlook', kind: 'focus' },
  { x: 725, title: 'Provider', sub: 'Google / Entra', kind: 'external' },
]

type Msg = { y: number; from: number; to: number; label: string; ret?: boolean }
const msgs: Msg[] = [
  { y: 112, from: 0, to: 1, label: 'connect mailbox' },
  { y: 144, from: 1, to: 2, label: 'beginAuthorization(email, uri)' },
  { y: 204, from: 2, to: 1, label: 'authorizationUrl', ret: true },
  { y: 236, from: 1, to: 0, label: 'redirect', ret: true },
  { y: 288, from: 0, to: 3, label: 'sign in and consent' },
  { y: 320, from: 3, to: 0, label: '302 to callback ?code&state', ret: true },
  { y: 372, from: 0, to: 1, label: 'GET /oauth/…/callback' },
  { y: 404, from: 1, to: 2, label: 'completeAuthorization(callback)' },
  { y: 436, from: 2, to: 3, label: 'exchange code + PKCE' },
  { y: 468, from: 3, to: 2, label: 'tokens + identity', ret: true },
  { y: 556, from: 1, to: 2, label: 'open(email)' },
  { y: 588, from: 2, to: 1, label: 'Mailbox', ret: true },
]

const notes = [
  { y: 158, lane: 2, text: 'save one-time state + PKCE' },
  { y: 482, lane: 2, text: 'verify account, save tokens' },
]

const phases = [
  { y: 92, h: 160, label: '1 · Begin' },
  { y: 268, h: 66, label: '2 · Consent' },
  { y: 352, h: 176, label: '3 · Complete' },
  { y: 536, h: 66, label: '4 · Open' },
]

const x = (i: number) => lanes[i].x
const mid = (m: Msg) => (x(m.from) + x(m.to)) / 2
const end = (m: Msg) => x(m.to) + (x(m.to) > x(m.from) ? -4 : 4)
</script>

<template>
  <figure class="mk-diagram">
    <div class="mk-diagram-scroll">
      <svg viewBox="0 0 820 630" role="img" aria-labelledby="mk-auth-title">
        <title id="mk-auth-title">Hosted authorization sequence between browser, backend, MailKT and provider</title>
        <defs>
          <marker id="mk-auth-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" class="mk-head" />
          </marker>
        </defs>

        <g v-for="p in phases" :key="p.label">
          <rect x="8" :y="p.y" width="804" :height="p.h" rx="6" class="mk-phase" />
          <text x="20" :y="p.y + 20" class="mk-phase-label">{{ p.label }}</text>
        </g>

        <g v-for="l in lanes" :key="l.title">
          <line :x1="l.x" y1="62" :x2="l.x" y2="612" class="mk-lifeline" />
          <rect :x="l.x - 78" y="10" width="156" height="50" rx="6" :class="['mk-box', l.kind]" />
          <text :x="l.x" y="32" text-anchor="middle" class="mk-title">{{ l.title }}</text>
          <text :x="l.x" y="49" text-anchor="middle" class="mk-sub">{{ l.sub }}</text>
        </g>

        <g v-for="m in msgs" :key="m.y">
          <line :x1="x(m.from)" :y1="m.y" :x2="end(m)" :y2="m.y" :class="['mk-edge', { ret: m.ret }]" marker-end="url(#mk-auth-arrow)" />
          <text :x="mid(m)" :y="m.y - 7" text-anchor="middle" class="mk-label">{{ m.label }}</text>
        </g>

        <g v-for="n in notes" :key="n.y">
          <rect :x="x(n.lane) - 115" :y="n.y" width="230" height="26" rx="4" class="mk-note" />
          <text :x="x(n.lane)" :y="n.y + 17" text-anchor="middle" class="mk-note-text">{{ n.text }}</text>
        </g>
      </svg>
    </div>
    <figcaption class="mk-legend">
      <span><i class="sw focus" />MailKT</span>
      <span><i class="sw data" />Your application</span>
      <span><i class="sw external" />External provider</span>
      <span><i class="sw ret" />Response</span>
    </figcaption>
  </figure>
</template>
